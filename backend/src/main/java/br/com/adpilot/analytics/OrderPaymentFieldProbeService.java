package br.com.adpilot.analytics;

import br.com.adpilot.integrations.nuvemshop.NuvemshopClient;
import br.com.adpilot.integrations.nuvemshop.NuvemshopException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Bounded side-by-side LIST vs DETAIL observation of order payment fields.
 * It never infers receipt, refund, fees, payout or payment settlement.
 */
@Service
public class OrderPaymentFieldProbeService {
    private static final List<String> FIELDS = List.of(
            "total_paid_by_customer","total_paid_by_customer_including_fees");
    private static final int MAX_PAGES=20;
    private static final int MAX_DETAILS=5;
    private final NuvemshopClient client;

    public OrderPaymentFieldProbeService(NuvemshopClient client) {
        this.client=client;
    }

    private record Candidate(String id, Map<String,Object> listing) {}

    public record FieldCoverage(String field, int sampled,
            int listAbsent, int listNull, int listNumeric, int listInvalid,
            int detailAbsent, int detailNull, int detailNumeric, int detailInvalid,
            int numericInDetailWhenListAbsentOrNull) {}

    public record Report(boolean upstreamPaginationReconciled,
            boolean financiallyVerified, boolean paymentSettlementVerified,
            boolean ordersMatchedToCampaigns,
            int pagesFetched, int upstreamRowsFetched, int uniqueUpstreamOrderIds,
            Long upstreamReportedTotal, int duplicateOrderIds,
            int eligibleCandidatesSeen, int detailsQueried, int stateChangedDuringProbe,
            List<FieldCoverage> fields, List<String> warnings) {}

    private static final class Counter {
        int sampled;
        int[] list = new int[4], detail=new int[4];
        int recovered;

        void observe(Map<String,Object> listing, Map<String,Object> detail, String field) {
            sampled++;
            int listKind = kind(listing,field);
            int detailKind = kind(detail,field);
            list[listKind]++;
            this.detail[detailKind]++;
            if ((listKind==0 || listKind==1) && detailKind==2) recovered++;
        }

        FieldCoverage report(String field) {
            return new FieldCoverage(field,sampled,list[0],list[1],list[2],list[3],
                    detail[0],detail[1],detail[2],detail[3],recovered);
        }
    }

    /** Kind: absent=0, explicit null=1, valid nonnegative money=2, invalid=3. */
    private static int kind(Map<String,Object> row, String field) {
        if (!row.containsKey(field)) return 0;
        Object value = row.get(field);
        if (value==null) return 1;
        if (!(value instanceof String || value instanceof Number)) return 3;
        try {
            String str=value.toString();
            if (str.length()>40 || !str.matches("[0-9]+(?:\\.[0-9]+)?")) return 3;
            var money=new BigDecimal(str).setScale(2,RoundingMode.UNNECESSARY);
            return money.precision()<=18 ? 2 : 3;
        } catch(RuntimeException e) {
            return 3;
        }
    }

    public Report inspect(int pageSize, int maxPages, int maxDetailOrders) {
        if(pageSize<1 || pageSize>100 || maxPages<1 || maxPages>MAX_PAGES
                || maxDetailOrders<1 || maxDetailOrders>MAX_DETAILS)
            throw new NuvemshopException(HttpStatus.BAD_REQUEST,"INVALID_PAGINATION",
                    "pageSize 1..100, maxPages 1..20, maxDetailOrders 1..5.");
        var seen=new HashSet<String>();
        var candidates=new ArrayList<Candidate>();
        Long expected=null;
        boolean totalsPresent=true, totalsStable=true, exhausted=false;
        int pages=0, rows=0, duplicate=0;
        for(int p=1;p<=maxPages;p++) {
            var page=client.orderPaymentSignals(p,pageSize);
            pages++;
            if(page.data()==null || page.data().size()>pageSize
                    || page.data().isEmpty() && page.hasNext())
                throw new NuvemshopException(HttpStatus.BAD_GATEWAY,
                        "NUVEMSHOP_INVALID_PAGINATION","Paginacao de pedidos inconsistente.");
            rows=Math.addExact(rows,page.data().size());
            if(page.totalCount()==null) totalsPresent=false;
            else if(page.totalCount()<0) totalsStable=false;
            else if(expected==null) expected=page.totalCount();
            else if(!expected.equals(page.totalCount())) totalsStable=false;

            for(var row:page.data()) {
                if(row==null || row.get("id")==null
                        || !row.get("id").toString().matches("[0-9]{1,30}"))
                    throw invalidData();
                String id=row.get("id").toString();
                String state=state(row.get("status"));
                String payment=state(row.get("payment_status"));
                if(state==null || payment==null) throw invalidData();
                if(!seen.add(id)) {duplicate++;continue;}
                if("paid".equals(payment)
                        && ("open".equals(state)||"closed".equals(state))) {
                    candidates.add(new Candidate(id,row));
                }
            }
            if(!page.hasNext()) {exhausted=true;break;}
        }
        boolean reconciled=exhausted && totalsPresent && totalsStable && expected!=null
                && expected==seen.size() && duplicate==0;

        // Prefer orders that were missing the buyer-paid amount in LIST.
        candidates.sort((a,b)->Boolean.compare(
                hasMissingBuyerPaid(b.listing()),hasMissingBuyerPaid(a.listing())));
        int count=Math.min(candidates.size(),maxDetailOrders);
        var counters=new Counter[]{new Counter(),new Counter()};
        int changed=0;
        for(int i=0;i<count;i++) {
            var candidate=candidates.get(i);
            var detail=client.orderPaymentSignalDetails(Long.parseLong(candidate.id()));
            if(detail==null || detail.get("id")==null
                    || !candidate.id().equals(detail.get("id").toString()))
                throw invalidData();
            if(!java.util.Objects.equals(candidate.listing().get("status"),detail.get("status"))
                    || !java.util.Objects.equals(candidate.listing().get("payment_status"),
                            detail.get("payment_status"))) changed++;
            for(int k=0;k<FIELDS.size();k++)
                counters[k].observe(candidate.listing(),detail,FIELDS.get(k));
        }

        var observed=new ArrayList<FieldCoverage>();
        for(int k=0;k<FIELDS.size();k++) observed.add(counters[k].report(FIELDS.get(k)));
        var warnings=new ArrayList<String>();
        warnings.add("ORDER_LIST_AND_DETAIL_NOT_POINT_IN_TIME");
        warnings.add("SMALL_PURPOSIVE_SAMPLE_NOT_REPRESENTATIVE");
        warnings.add("REPORTED_BUYER_PAID_NOT_PROVIDER_SETTLEMENT");
        warnings.add("NO_REFUND_FEE_OR_PAYOUT_VERIFICATION");
        warnings.add("NO_ORDER_TO_META_CAMPAIGN_ASSOCIATION");
        if(!reconciled) warnings.add("UPSTREAM_PAGINATION_NOT_RECONCILED");
        if(count<candidates.size()) warnings.add("DETAIL_LOOKUP_LIMITED_SAMPLE");
        if(changed>0) warnings.add("ORDER_STATE_CHANGED_DURING_PROBE");
        return new Report(reconciled,false,false,false,pages,rows,seen.size(),
                totalsPresent&&totalsStable ? expected:null,duplicate,candidates.size(),
                count,changed,List.copyOf(observed),List.copyOf(warnings));
    }

    private static boolean hasMissingBuyerPaid(Map<String,Object> row) {
        return row.get("total_paid_by_customer")==null;
    }

    private static String state(Object raw) {
        return raw instanceof String s && s.matches("[a-z_]{1,32}")?s:null;
    }

    private static NuvemshopException invalidData() {
        return new NuvemshopException(HttpStatus.BAD_GATEWAY,"NUVEMSHOP_INVALID_ORDER_DATA",
                "Pedido de diagnostico invalido.");
    }
}
