package br.com.adpilot.integrations.meta;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * PR055: on-demand, read-only funnel observations from already authorized Meta
 * campaign insights. Ratios compare attributed aggregate EVENTS, not user
 * cohorts, and are never causal conversion rates or verified shop orders.
 */
@Service
public class MetaFunnelDiagnosticsService {
    private final MetaDeliveryInterpreterService delivery;

    public MetaFunnelDiagnosticsService(MetaDeliveryInterpreterService delivery) {
        this.delivery=delivery;
    }

    public record Funnel(
            String level,String id,String adsetId,
            BigDecimal spend,Long impressions,Long allClicks,Long linkClicks,
            BigDecimal landingPageViews,BigDecimal addToCarts,
            BigDecimal initiatedCheckouts,BigDecimal attributedPurchases,
            BigDecimal visitsPerLinkClickPercent,BigDecimal cartsPerVisitPercent,
            BigDecimal checkoutsPerCartPercent,BigDecimal purchasesPerCheckoutPercent,
            BigDecimal costPerLandingPageView,BigDecimal costPerAddToCart,
            BigDecimal costPerInitiatedCheckout,BigDecimal attributedCpa,
            List<String> warnings) {}

    public record Finding(String ruleCode,String priority,String confidence,
            String observation,String suggestedCheck,List<String> evidence) {}

    public record Report(String campaignId,String campaignObjective,String currency,
            LocalDate since,LocalDate until,String analysisMode,boolean partialCurrentDay,
            boolean complete,MetaDeliveryInterpreterService.Coverage adsetCoverage,
            MetaDeliveryInterpreterService.Coverage adCoverage,
            Funnel campaign,List<Funnel> adsets,List<Funnel> ads,
            List<Finding> findings,List<String> warnings,List<String> principles) {}

    public Report inspect(String campaignId,LocalDate since,LocalDate until,
            int pageSize,int maxPages) {
        // Existing service verifies campaign/account ownership, input, limits and paging.
        return analyze(delivery.inspect(campaignId,since,until,pageSize,maxPages));
    }

    Report analyze(MetaDeliveryInterpreterService.Report source) {
        var campaign=aggregate("campaign",source.campaignId(),null,source.adsetInsights());
        var adsets=source.adsetInsights().stream()
                .map(row->aggregate("adset",row.id(),row.adsetId(),List.of(row)))
                .toList();
        var ads=source.adInsights().stream()
                .map(row->aggregate("ad",row.id(),row.adsetId(),List.of(row)))
                .toList();

        var warnings=new ArrayList<String>(List.of(
                "FUNNEL_EVENT_COUNTS_NOT_UNIQUE_USERS_OR_TRACKED_COHORT",
                "FUNNEL_RATIOS_NOT_CAUSAL_CONVERSION_RATES",
                "MISSING_ACTION_MEANS_UNKNOWN_NOT_ZERO",
                "META_PURCHASES_NOT_VERIFIED_NUVEMSHOP_PAID_ORDERS",
                "NO_VERIFIED_ORDER_TO_AD_IDENTITY",
                "NO_AUTOMATIC_PAUSE_SCALE_OR_BUDGET_CHANGE",
                "META_ATTRIBUTION_AND_ACCOUNT_TIMEZONE_MAY_DIFFER"));
        if(!source.complete()) warnings.add("FUNNEL_PAGINATION_INCOMPLETE");
        if(source.partialCurrentDay()) warnings.add("FUNNEL_CURRENT_DAY_PROVISIONAL");
        if(source.adsetInsights().isEmpty()) warnings.add("NO_ADSET_INSIGHT_ROWS");
        if(source.adsetInsights().stream().anyMatch(e->
                "UNKNOWN".equals(e.attributionSetting())))
            warnings.add("ATTRIBUTION_SETTING_UNKNOWN");
        if(campaign.attributedPurchases()==null)
            warnings.add("PURCHASE_ACTION_UNAVAILABLE_NOT_ZERO");
        if(campaign.initiatedCheckouts()==null)
            warnings.add("CHECKOUT_ACTION_UNAVAILABLE_NOT_ZERO");
        var findings=new ArrayList<Finding>();
        if(!source.complete()||source.adsetInsights().isEmpty()) {
            findings.add(new Finding("FUNNEL_INCOMPLETE_EVIDENCE","HIGH",
                    "INSUFFICIENT","A consulta nao cobre integralmente os insights de conjuntos.",
                    "Complete a paginacao e confirme a cobertura antes de comparar conjuntos.",
                    List.of("PAGINATION_OR_ROWS_INCOMPLETE")));
        } else {
            if(source.partialCurrentDay()) {
                findings.add(new Finding("PROVISIONAL_CURRENT_DAY","HIGH","INSUFFICIENT",
                        "A janela inclui o dia corrente; contagens e atribuicoes podem mudar.",
                        "Use dias encerrados e confirme o fuso da conta antes de tomar decisoes.",
                        List.of("PARTIAL_CURRENT_DAY")));
            }
            if("OUTCOME_TRAFFIC".equals(source.campaignObjective())) {
                findings.add(new Finding("TRAFFIC_OPTIMIZES_VISITS_NOT_PURCHASES",
                        "MEDIUM","LOW",
                        "O objetivo de trafego nao otimiza necessariamente para compradores.",
                        "Avalie o papel comercial desta campanha antes de cobrar CPA de compras.",
                        List.of("OBJECTIVE_OUTCOME_TRAFFIC")));
            }
            if(campaign.attributedPurchases()==null) {
                findings.add(new Finding("PURCHASE_ACTION_NOT_REPORTED","HIGH",
                        "INSUFFICIENT",
                        "Nenhuma contagem reconhecida de Purchase foi informada nos Insights.",
                        "Confira Events Manager, Pixel/CAPI, janela de atribuicao e pedidos da loja separadamente.",
                        List.of("ATTRIBUTED_PURCHASES_NULL")));
            }
            if(campaign.initiatedCheckouts()==null && campaign.addToCarts()!=null
                    && campaign.addToCarts().signum()>0) {
                findings.add(new Finding("CHECKOUT_ACTION_NOT_REPORTED","MEDIUM",
                        "INSUFFICIENT",
                        "Ha eventos de carrinho, mas o evento de inicio de checkout nao foi informado.",
                        "Verifique se InitiateCheckout e enviado e reconhecido pela Meta; nao conclua abandono.",
                        List.of("ADD_TO_CART_OBSERVED","INITIATE_CHECKOUT_NULL")));
            }
            // Exploratory floor, not an industry benchmark: at least 100 visits
            // and fewer than five cart actions merits inspection, not a verdict.
            if("OUTCOME_SALES".equals(source.campaignObjective())
                    && campaign.landingPageViews()!=null
                    && campaign.landingPageViews().compareTo(BigDecimal.valueOf(100))>=0
                    && campaign.addToCarts()!=null
                    && campaign.addToCarts().compareTo(BigDecimal.valueOf(5))<0) {
                findings.add(new Finding("SPARSE_CART_ACTIONS","MEDIUM",
                        "INSUFFICIENT",
                        "Ha pelo menos 100 visualizacoes de destino e menos de cinco eventos de carrinho.",
                        "Revise mensuracao, oferta e pagina de produto; amostra nao permite diagnostico causal.",
                        List.of("LANDING_PAGE_VIEWS_GE_100","ADD_TO_CART_LT_5")));
            }
            if(campaign.warnings().contains("DOWNSTREAM_EVENTS_EXCEED_UPSTREAM")) {
                findings.add(new Finding("NON_MONOTONIC_EVENT_COUNTS","MEDIUM",
                        "INSUFFICIENT",
                        "Algumas contagens posteriores excedem as anteriores no agregado.",
                        "Confira atribuicao e taxonomias; nao trate estes dados como jornada unica.",
                        List.of("EVENT_COUNT_RELATION")));
            }
        }
        return new Report(source.campaignId(),source.campaignObjective(),
                source.currency(),source.since(),source.until(),source.analysisMode(),
                source.partialCurrentDay(),source.complete(),
                source.adsetInsightsCoverage(),source.adInsightsCoverage(),campaign,
                List.copyOf(adsets),List.copyOf(ads),List.copyOf(findings),
                List.copyOf(warnings),List.of(
                        "EVENT_COUNTS_ARE_NON_UNIQUE_AND_NOT_SEQUENTIAL_COHORTS",
                        "OBSERVATIONS_ARE_META_ATTRIBUTED_NOT_SETTLED_SALES",
                        "NO_CROSS_OBJECTIVE_RANKING_WITHOUT_COMMERCIAL_CONTEXT",
                        "PROVISIONAL_OR_INCOMPLETE_DATA_NEVER_JUSTIFY_BUDGET_DECISIONS"));
    }

    private static Funnel aggregate(String level,String id,String adsetId,
            List<MetaDeliveryInterpreterService.Evidence> rows) {
        BigDecimal spend=null;
        Long impressions=null,allClicks=null,linkClicks=null;
        if(!rows.isEmpty()) {
            spend=BigDecimal.ZERO; impressions=0L;allClicks=0L;
            for(var e:rows) {
                spend=spend.add(e.spend());
                impressions=Math.addExact(impressions,e.impressions());
                allClicks=Math.addExact(allClicks,e.allClicks());
            }
            if(rows.stream().allMatch(e->e.linkClicks()!=null)) {
                linkClicks=0L;
                for(var e:rows) linkClicks=Math.addExact(linkClicks,e.linkClicks());
            }
        }
        var lpv=sumOptional(rows,MetaDeliveryInterpreterService.Evidence::landingPageViews);
        var carts=sumOptional(rows,MetaDeliveryInterpreterService.Evidence::addToCarts);
        var checkouts=sumOptional(rows,MetaDeliveryInterpreterService.Evidence::initiatedCheckouts);
        var purchases=sumOptional(rows,MetaDeliveryInterpreterService.Evidence::attributedPurchases);
        var alerts=new ArrayList<String>();
        if(rows.isEmpty()) alerts.add("NO_INSIGHT_ROWS_NOT_ZERO");
        if(linkClicks==null) alerts.add("LINK_CLICKS_UNAVAILABLE");
        if(lpv==null) alerts.add("LANDING_PAGE_VIEWS_UNAVAILABLE");
        if(carts==null) alerts.add("ADD_TO_CART_UNAVAILABLE");
        if(checkouts==null) alerts.add("INITIATE_CHECKOUT_UNAVAILABLE");
        if(purchases==null) alerts.add("PURCHASE_ACTION_UNAVAILABLE");
        if(exceeds(lpv,linkClicks==null?null:BigDecimal.valueOf(linkClicks))
                ||exceeds(carts,lpv)||exceeds(checkouts,carts)
                ||exceeds(purchases,checkouts))
            alerts.add("DOWNSTREAM_EVENTS_EXCEED_UPSTREAM");
        return new Funnel(level,id,adsetId,spend,impressions,allClicks,linkClicks,
                lpv,carts,checkouts,purchases,
                percent(lpv,linkClicks==null?null:BigDecimal.valueOf(linkClicks)),
                percent(carts,lpv),percent(checkouts,carts),percent(purchases,checkouts),
                divided(spend,lpv),divided(spend,carts),
                divided(spend,checkouts),divided(spend,purchases),
                List.copyOf(alerts));
    }

    private static boolean exceeds(BigDecimal later,BigDecimal earlier) {
        return later!=null&&earlier!=null&&later.compareTo(earlier)>0;
    }
    private static BigDecimal percent(BigDecimal numerator,BigDecimal denominator) {
        if(numerator==null||denominator==null||denominator.signum()==0)return null;
        return numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator,4,RoundingMode.HALF_UP);
    }
    private static BigDecimal divided(BigDecimal numerator,BigDecimal denominator) {
        if(numerator==null||denominator==null||denominator.signum()==0)return null;
        return numerator.divide(denominator,4,RoundingMode.HALF_UP);
    }
    private static BigDecimal sumOptional(
            List<MetaDeliveryInterpreterService.Evidence> rows,
            Function<MetaDeliveryInterpreterService.Evidence,BigDecimal> field) {
        if(rows.isEmpty()) return null;
        BigDecimal sum=BigDecimal.ZERO;
        for(var row:rows) {
            BigDecimal value=field.apply(row);
            if(value==null)return null; // partial missing never becomes zero
            sum=sum.add(value);
        }
        return sum;
    }
}
