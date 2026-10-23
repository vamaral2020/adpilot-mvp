package br.com.adpilot.analytics;

import br.com.adpilot.analytics.AnalyticsHistoryModels.CampaignResult;
import br.com.adpilot.analytics.AnalyticsHistoryModels.Coverage;
import br.com.adpilot.analytics.AnalyticsHistoryModels.MetaDay;
import br.com.adpilot.analytics.AnalyticsHistoryModels.SourceState;
import br.com.adpilot.analytics.AnalyticsHistoryModels.StoreDay;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compares independent, already-sanitized Meta and Nuvemshop snapshots.
 * NO order-to-campaign identity, cross-source attribution, organic revenue,
 * profit, CAC, or verified MER can be calculated from this data model.
 * A complete Meta fetch window is NOT a complete payment-date ledger.
 */
@Service
public class CommercialReconciliationService {
    private final AnalyticsHistoryService history;
    private final Clock clock;

    @Autowired
    public CommercialReconciliationService(AnalyticsHistoryService history) {
        this(history,Clock.systemUTC());
    }

    CommercialReconciliationService(AnalyticsHistoryService history,Clock clock) {
        this.history=history;
        this.clock=clock;
    }

    public record MetaObserved(String currency,BigDecimal spend,
            BigDecimal attributedPurchases,BigDecimal attributedRevenue,int reportedDays) {}
    public record StoreObserved(String currency,Long identifiedPaidOrders,
            BigDecimal identifiedPaidRevenue,int reportedDays) {}
    public record DayComparison(LocalDate date,List<MetaDay> meta,List<StoreDay> store) {}
    public record Report(LocalDate since,LocalDate until,String comparisonStatus,
            boolean financiallyVerified,boolean ordersMatchedToCampaigns,
            MetaObserved meta,StoreObserved store,
            BigDecimal indicativeMer,BigDecimal indicativeAttributedRoas,
            List<DayComparison> daily,List<CampaignResult> campaigns,
            Coverage coverage,List<String> warnings) {}

    @Transactional(readOnly=true)
    public Report compare(LocalDate since,LocalDate until) {
        // Delegates all range validation to the existing historical boundary.
        var source=history.history(since,until);
        var meta=aggregateMeta(source.meta());
        var shop=aggregateStore(source.store());
        var warnings=new LinkedHashSet<String>(source.warnings());
        warnings.add("NOT_ORDER_TO_CAMPAIGN_RECONCILIATION");
        warnings.add("STORE_SNAPSHOTS_UPDATED_AT_NOT_PAID_AT_LEDGER");
        warnings.add("META_ATTRIBUTION_NOT_CONFIRMED_PAYMENT");
        warnings.add("NO_ORGANIC_REVENUE_INFERENCE");
        warnings.add("NO_CAC_PROFIT_OR_FINANCIAL_ROI");
        boolean todayOrFuture=!until.isBefore(LocalDate.now(clock.withZone(ZoneOffset.UTC)));
        boolean metaReceipts=source.coverage()!=null
                && source.coverage().meta()!=null
                && "WINDOWS_OBSERVED".equals(source.coverage().meta().status())
                && source.coverage().meta().observedWithoutRows().isEmpty()
                && !todayOrFuture;
        boolean shopReceipts=source.coverage()!=null
                && source.coverage().store()!=null
                && source.coverage().store().successfulUpdateWindows()>0;
        boolean statesOk=latestSuccessful(source.sources(),"META_ADS","INSIGHTS_DAILY")
                && latestSuccessful(source.sources(),"NUVEMSHOP","ORDERS");
        boolean accountOk=source.coverage()!=null && source.coverage().meta()!=null
                && source.coverage().meta().accountId()!=null
                && source.campaigns().stream().allMatch(c ->
                    source.coverage().meta().accountId().equals(c.accountId()));
        boolean comparableCurrencies=meta.currency()!=null && shop.currency()!=null
                && meta.currency().equals(shop.currency());
        boolean ratioPossible=metaReceipts && shopReceipts && statesOk
                && accountOk && comparableCurrencies && meta.spend()!=null
                && meta.spend().signum()>0 && shop.identifiedPaidRevenue()!=null
                && meta.attributedRevenue()!=null;
        BigDecimal mer=ratioPossible?divide(shop.identifiedPaidRevenue(),meta.spend()):null;
        BigDecimal roas=metaReceipts && statesOk && accountOk && meta.currency()!=null
                && meta.spend()!=null && meta.spend().signum()>0
                && meta.attributedRevenue()!=null
                ?divide(meta.attributedRevenue(),meta.spend()):null;

        String status;
        if(meta.reportedDays()==0 || shop.reportedDays()==0) {
            status="INSUFFICIENT_OBSERVATIONS";
        } else if(meta.currency()==null || shop.currency()==null) {
            status="AMBIGUOUS_CURRENCY";
        } else if(!comparableCurrencies) {
            status="CURRENCY_MISMATCH";
        } else if(!metaReceipts || !accountOk) {
            status="META_COVERAGE_UNVERIFIED";
        } else if(!shopReceipts || !statesOk) {
            status="SYNC_EVIDENCE_INCOMPLETE";
        } else {
            status="INDICATIVE_ONLY";
        }
        if(!comparableCurrencies) warnings.add("CROSS_CURRENCY_COMPARISON_BLOCKED");
        if(!metaReceipts) warnings.add("META_DAYS_OR_FINALITY_NOT_VERIFIED");
        if(!shopReceipts) warnings.add("SHOP_UPDATE_WINDOWS_NOT_OBSERVED");
        if(!statesOk) warnings.add("LAST_PROVIDER_SYNC_NOT_SUCCESSFUL");
        if(!accountOk) warnings.add("META_ACCOUNT_CONSISTENCY_NOT_VERIFIED");
        if(todayOrFuture) warnings.add("CURRENT_UTC_DAY_INCOMPLETE");
        if(mer!=null) warnings.add("MER_USES_IDENTIFIED_SNAPSHOTS_ONLY_NOT_AUDITED");
        return new Report(since,until,status,false,false,meta,shop,mer,roas,
                daily(source.meta(),source.store()),source.campaigns(),
                source.coverage(),List.copyOf(warnings));
    }

    private static boolean latestSuccessful(List<SourceState> sources,
            String provider,String resource) {
        return sources.stream().anyMatch(s -> provider.equals(s.provider())
                && resource.equals(s.resource()) && "SUCCEEDED".equals(s.lastStatus())
                && s.lastSuccess()!=null);
    }

    private static BigDecimal divide(BigDecimal dividend,BigDecimal divisor) {
        return dividend.divide(divisor,4,RoundingMode.HALF_UP);
    }

    private static MetaObserved aggregateMeta(List<MetaDay> rows) {
        if(rows.isEmpty()) return new MetaObserved(null,null,null,null,0);
        var currencies=new TreeSet<String>();
        BigDecimal spend=BigDecimal.ZERO,purchases=BigDecimal.ZERO,revenue=BigDecimal.ZERO;
        var dates=new TreeSet<LocalDate>();
        for(var row:rows) {
            if(row.currency()==null || row.spend()==null || row.attributedPurchases()==null
                    || row.attributedRevenue()==null || row.date()==null
                    || row.spend().signum()<0 || row.attributedPurchases().signum()<0
                    || row.attributedRevenue().signum()<0)
                return new MetaObserved(null,null,null,null,0);
            currencies.add(row.currency());
            dates.add(row.date());
            spend=spend.add(row.spend());
            purchases=purchases.add(row.attributedPurchases());
            revenue=revenue.add(row.attributedRevenue());
        }
        // Preserve observed per-currency numbers only when a single unit applies.
        return currencies.size()!=1 ?new MetaObserved(null,null,null,null,dates.size())
                :new MetaObserved(currencies.first(),spend,purchases,revenue,dates.size());
    }

    private static StoreObserved aggregateStore(List<StoreDay> rows) {
        if(rows.isEmpty()) return new StoreObserved(null,null,null,0);
        var currencies=new TreeSet<String>();
        long count=0;
        BigDecimal revenue=BigDecimal.ZERO;
        var dates=new TreeSet<LocalDate>();
        for(var row:rows) {
            if(row.currency()==null || row.paidRevenue()==null || row.date()==null
                    || row.paidRevenue().signum()<0 || row.paidOrders()<0)
                return new StoreObserved(null,null,null,0);
            currencies.add(row.currency());
            dates.add(row.date());
            count=Math.addExact(count,row.paidOrders());
            revenue=revenue.add(row.paidRevenue());
        }
        return currencies.size()!=1 ?new StoreObserved(null,null,null,dates.size())
                :new StoreObserved(currencies.first(),count,revenue,dates.size());
    }

    private static List<DayComparison> daily(List<MetaDay> meta,List<StoreDay> store) {
        var dates=new TreeMap<LocalDate, DayBuilder>();
        for(var item:meta) dates.computeIfAbsent(item.date(),x->new DayBuilder())
                .meta.add(item);
        for(var item:store) dates.computeIfAbsent(item.date(),x->new DayBuilder())
                .store.add(item);
        var rows=new ArrayList<DayComparison>();
        for(var entry:dates.entrySet())
            rows.add(new DayComparison(entry.getKey(),
                    List.copyOf(entry.getValue().meta),
                    List.copyOf(entry.getValue().store)));
        return List.copyOf(rows);
    }

    private static class DayBuilder {
        final List<MetaDay> meta=new ArrayList<>();
        final List<StoreDay> store=new ArrayList<>();
    }
}
