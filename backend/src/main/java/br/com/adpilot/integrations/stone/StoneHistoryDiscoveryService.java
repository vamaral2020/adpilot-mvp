package br.com.adpilot.integrations.stone;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Read-only, explicitly bounded discovery. Reconciles only the relationship
 * inside the Pagar.me provider sample. It does NOT match Nuvemshop orders.
 */
@Service
public class StoneHistoryDiscoveryService {
    private final StoneHistoryClient client;

    public StoneHistoryDiscoveryService(StoneHistoryClient client) {
        this.client=client;
    }

    public record Population(int pagesFetched,int rowsFetched,int uniqueIds,
            int duplicateIds,Long providerReportedTotal,
            boolean paginationExhausted,boolean paginationReconciled,
            Map<String,Integer> statusCounts) {}
    public record Report(String source,String keyEnvironment,
            Population providerOrders,Population providerCharges,
            int ordersWithExternalCode,
            int chargesWithProviderOrderReference,
            int chargesWithMissingProviderOrderReference,
            int chargesLinkedToObservedProviderOrders,
            int chargesReferencingProviderOrdersOutsideSample,
            int observedProviderOrdersWithMultipleCharges,
            Map<String,Integer> paymentMethodCounts,
            boolean financiallyVerified,boolean paymentSettlementVerified,
            boolean ordersMatchedToNuvemshop,boolean ordersMatchedToCampaigns,
            List<String> warnings) {}

    private record Orders(Population population,Set<String> ids,int withCode) {}
    private record Charges(Population population,int withRef,int missingRef,
            int inSample,int outside,int multiple,
            Map<String,Integer> methods) {}

    private static final class Accumulator {
        int pages,rows,duplicates;
        Long total;
        boolean consistent=true,pagingPresent=true,exhausted=false;
        Set<String> seen=new HashSet<>();
        Map<String,Integer> statuses=new TreeMap<>();

        boolean visit(StoneHistoryClient.Page<?> page,List<String> ids,
                List<String> states,int pageSize) {
            pages++;
            if(page.data()==null || page.data().size()>pageSize
                    || ids.size()!=page.data().size()
                    || states.size()!=ids.size()
                    || page.hasNext() && page.data().isEmpty())
                throw invalid();
            rows+=ids.size();
            if(!page.pagingMetadataPresent()) pagingPresent=false;
            if(page.total()==null) consistent=false;
            else if(total==null) total=page.total();
            else if(!total.equals(page.total())) consistent=false;
            for(int i=0;i<ids.size();i++) {
                if(!seen.add(ids.get(i))) {
                    duplicates++;
                } else {
                    statuses.merge(states.get(i),1,Integer::sum);
                }
            }
            // Provider paging.next is only an indicator; never follow its URL.
            boolean continuation=page.hasNext()
                    || page.total()!=null && seen.size()<page.total()
                    && page.data().size()==pageSize;
            if(!continuation) {
                exhausted=page.pagingMetadataPresent()
                        && !page.hasNext()
                        && page.total()!=null && page.total()==seen.size();
            }
            return continuation;
        }

        Population result() {
            boolean reconciled=exhausted && pagingPresent && consistent
                    && duplicates==0 && total!=null && total==seen.size();
            return new Population(pages,rows,seen.size(),duplicates,
                    consistent?total:null,exhausted,reconciled,Map.copyOf(statuses));
        }
    }

    public Report inspect(int pageSize,int maxPages) {
        if(pageSize<1||pageSize>50||maxPages<1||maxPages>10)
            throw new StoneChargeException(HttpStatus.BAD_REQUEST,
                    "STONE_INVALID_PAGINATION",
                    "pageSize 1..50, maxPages 1..10.");
        var orders=orders(pageSize,maxPages);
        var charges=charges(pageSize,maxPages,orders.ids());
        var warnings=new ArrayList<String>();
        warnings.add("PROVIDER_HISTORY_IS_A_BOUNDED_SAMPLE");
        warnings.add("PROVIDER_CODE_NOT_VERIFIED_AGAINST_NUVEMSHOP_ORDER");
        warnings.add("PROVIDER_ORDER_LINK_NOT_NUVEMSHOP_MATCH");
        warnings.add("NO_PAYMENT_SETTLEMENT_FEE_REFUND_OR_DISPUTE_VERIFICATION");
        warnings.add("NO_META_CAMPAIGN_ATTRIBUTION");
        warnings.add("UPSTREAM_PAGES_NOT_POINT_IN_TIME");
        if(!orders.population().paginationReconciled()
                || !charges.population().paginationReconciled())
            warnings.add("INCOMPLETE_PROVIDER_PAGINATION");
        if(charges.outside()>0)
            warnings.add("CHARGE_PARENT_OUTSIDE_OBSERVED_ORDER_SAMPLE");
        if(charges.missingRef()>0)
            warnings.add("SOME_CHARGES_MISSING_PROVIDER_ORDER_ID");
        if(charges.multiple()>0)
            warnings.add("MULTIPLE_CHARGES_FOR_PROVIDER_ORDER_OBSERVED");
        return new Report("PAGARME_CORE_V5",client.keyEnvironment(),
                orders.population(),charges.population(),orders.withCode(),
                charges.withRef(),charges.missingRef(),charges.inSample(),
                charges.outside(),charges.multiple(),charges.methods(),
                false,false,false,false,List.copyOf(warnings));
    }

    private Orders orders(int size,int limit) {
        var acc=new Accumulator();
        int withCode=0;
        for(int page=1;page<=limit;page++) {
            var response=client.orders(page,size);
            var ids=new ArrayList<String>();
            var status=new ArrayList<String>();
            for(var record:response.data()) {
                ids.add(record.id());status.add(record.status());
                // Count once per unique provider order ID.
                if(!acc.seen.contains(record.id()) && record.codePresent()) withCode++;
            }
            boolean more=acc.visit(response,ids,status,size);
            if(!more) break;
        }
        return new Orders(acc.result(),Set.copyOf(acc.seen),withCode);
    }

    private Charges charges(int size,int limit,Set<String> orderIds) {
        var acc=new Accumulator();
        var refs=new HashMap<String,Integer>();
        var methods=new TreeMap<String,Integer>();
        int hasRef=0,missing=0,inSample=0,outside=0;
        for(int page=1;page<=limit;page++) {
            var response=client.charges(page,size);
            var ids=new ArrayList<String>();
            var statuses=new ArrayList<String>();
            for(var record:response.data()) {
                ids.add(record.id());statuses.add(record.status());
                if(acc.seen.contains(record.id())) continue;
                methods.merge(record.method(),1,Integer::sum);
                if(record.providerOrderId()==null) missing++;
                else {
                    hasRef++;
                    refs.merge(record.providerOrderId(),1,Integer::sum);
                    if(orderIds.contains(record.providerOrderId())) inSample++;
                    else outside++;
                }
            }
            boolean more=acc.visit(response,ids,statuses,size);
            if(!more) break;
        }
        int multiple=(int)refs.values().stream().filter(v->v>1).count();
        return new Charges(acc.result(),hasRef,missing,inSample,outside,multiple,
                Map.copyOf(methods));
    }

    private static StoneChargeException invalid() {
        return new StoneChargeException(HttpStatus.BAD_GATEWAY,
                "STONE_INVALID_COLLECTION_RESPONSE",
                "Dados de listagem Stone inconsistentes.");
    }
}
