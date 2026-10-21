package br.com.adpilot.analytics;

import br.com.adpilot.analytics.RecommendationModels.Advice;
import br.com.adpilot.analytics.RecommendationModels.DailyCampaign;
import br.com.adpilot.analytics.RecommendationModels.Metric;
import br.com.adpilot.analytics.RecommendationModels.Report;
import br.com.adpilot.analytics.RecommendationModels.Request;
import br.com.adpilot.analytics.RecommendationModels.SyncState;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import org.springframework.stereotype.Component;

/**
 * PR 017 version 1: deterministic, explainable, advisory-only rules.
 * No action code in this engine authorizes pausing, scaling or updating Meta ads.
 * Coverage is not verified by a successful sync; all advice stays preliminary.
 */
@Component
public class RecommendationRuleEngine {
    public static final int RULE_VERSION = 1;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final BigDecimal FIVE = BigDecimal.valueOf(5);
    private static final BigDecimal TEN = BigDecimal.TEN;
    private static final BigDecimal CTR_DECLINE_RATIO = new BigDecimal("0.70");
    private static final BigDecimal CPC_RISE_RATIO = new BigDecimal("1.30");
    private static final String LIMITATION =
            "Meta attributed, not reconciled with Nuvemshop; coverage unverified";

    public Report evaluate(Request request, List<DailyCampaign> data,
            SyncState sync, Instant generatedAt) {
        var warnings = new ArrayList<String>();
        warnings.add("HISTORICAL_COVERAGE_NOT_VERIFIED");
        warnings.add("META_ATTRIBUTION_CAN_CHANGE");
        warnings.add("META_PURCHASES_NOT_RECONCILED_WITH_STORE");
        warnings.add("NO_AUTOMATIC_AD_CHANGES");
        if (sync.lastSuccess() == null || !"SUCCEEDED".equals(sync.lastStatus()))
            warnings.add("META_SYNC_NOT_CONFIRMED_CURRENT");
        if (request.targetRoas() == null && request.targetCpa() == null)
            warnings.add("BUSINESS_GOALS_NOT_CONFIGURED");

        var advice = new ArrayList<Advice>();
        if (data.isEmpty()) {
            advice.add(advice("NO_META_INSIGHTS",null,null,null,null,"LOW",
                    "INSUFFICIENT","REVIEW_DATA","Sem dados da Meta no período",
                    "Não existem registros diários persistidos para avaliar campanhas. "
                    + "Ausência de registros não significa investimento ou compras iguais a zero.",
                    List.of(),List.of("HISTORICAL_COVERAGE_NOT_VERIFIED")));
        } else {
            var grouped = new LinkedHashMap<String,List<DailyCampaign>>();
            for (DailyCampaign day : data) {
                String key = day.accountId() + ":" + day.campaignId();
                grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(day);
            }
            for (var rows : grouped.values()) {
                advice.addAll(forCampaign(request, rows));
            }
        }

        if (request.targetRoas() == null && request.targetCpa() == null) {
            advice.add(advice("CONFIGURE_GOALS",null,null,null,null,"LOW",
                    "INSUFFICIENT","CONFIGURE_TARGETS","Configure metas comerciais",
                    "Informe ROAS mínimo e/ou CPA máximo para comparar desempenho com "
                    + "objetivos reais do negócio. Não serão usados limites financeiros arbitrários.",
                    List.of(),List.of("BUSINESS_GOALS_NOT_CONFIGURED")));
        }
        return new Report(request.since(),request.until(),request.targetRoas(),
                request.targetCpa(),false,generatedAt,sync.lastStatus(),sync.lastSuccess(),
                List.copyOf(warnings),List.copyOf(advice));
    }

    private List<Advice> forCampaign(Request request, List<DailyCampaign> input) {
        var rows = input.stream().sorted(Comparator.comparing(DailyCampaign::day)).toList();
        var first = rows.getFirst();
        String account = first.accountId(), campaign = first.campaignId();
        String name = first.campaignName();
        String currency = first.currency();
        Set<LocalDate> dates = new HashSet<>();
        for (DailyCampaign row : rows) {
            if (!Objects.equals(currency,row.currency()) || !dates.add(row.day())
                    || !valid(row,request)) {
                return List.of(advice("INVALID_CAMPAIGN_DATA",account,campaign,name,null,
                        "MEDIUM","INSUFFICIENT","REVIEW_DATA",
                        "Dados da campanha não comparáveis",
                        "Há moedas diferentes, dias duplicados ou métricas inválidas. "
                        + "Nenhum cálculo financeiro foi utilizado para sugerir uma ação.",
                        List.of(),List.of("CAMPAIGN_DATA_INVALID")));
            }
        }
        BigDecimal spend = ZERO, purchases = ZERO, revenue = ZERO;
        long impressions = 0, clicks = 0;
        for (DailyCampaign row : rows) {
            spend = spend.add(row.spend());
            purchases = purchases.add(row.attributedPurchases());
            revenue = revenue.add(row.attributedRevenue());
            impressions = Math.addExact(impressions,row.impressions());
            clicks = Math.addExact(clicks,row.clicks());
        }
        BigDecimal roas = ratio(revenue,spend);
        BigDecimal cpa = ratio(spend,purchases);
        BigDecimal ctr = impressions == 0 ? null :
                ratio(BigDecimal.valueOf(clicks).multiply(BigDecimal.valueOf(100)),
                        BigDecimal.valueOf(impressions));
        BigDecimal cpc = ratio(spend,BigDecimal.valueOf(clicks));
        var evidence = List.of(metric("SPEND",spend),metric("ATTRIBUTED_PURCHASES",purchases),
                metric("ATTRIBUTED_REVENUE",revenue),metric("IMPRESSIONS",impressions),
                metric("CLICKS",clicks),metric("OBSERVED_DAYS",dates.size()));
        var out = new ArrayList<Advice>();
        long periodDays = ChronoUnit.DAYS.between(request.since(),request.until()) + 1;
        boolean enough = periodDays >= 7 && dates.size() >= 3 && purchases.compareTo(FIVE) >= 0;
        String confidence = enough ? "LOW" : "INSUFFICIENT";

        if (spend.signum() == 0) {
            out.add(advice("NO_RECORDED_SPEND",account,campaign,name,currency,"LOW",
                    "INSUFFICIENT","REVIEW_DATA","Sem investimento registrado",
                    "A campanha possui registros mas nenhum gasto persistido neste período. "
                    + "Isso não comprova que ela ficou sem veiculação.",
                    evidence,List.of("HISTORICAL_COVERAGE_NOT_VERIFIED")));
            return out;
        }

        if (!enough) {
            out.add(advice("LOW_SAMPLE",account,campaign,name,currency,"LOW",
                    "INSUFFICIENT","MONITOR_ONLY","Acompanhar antes de alterar orçamento",
                    "Foram registradas " + purchases.toPlainString() + " compra(s) "
                    + "atribuídas em " + dates.size() + " dia(s) com registros. "
                    + "A amostra ou o período é insuficiente para orientar aumento, redução "
                    + "ou pausa de orçamento. ROAS e CPA podem oscilar.",
                    append(evidence,metric("ROAS",roas),metric("CPA",cpa)),
                    List.of("HISTORICAL_COVERAGE_NOT_VERIFIED","SMALL_OR_SHORT_SAMPLE")));
        }

        if (purchases.signum() == 0 && request.targetCpa() != null &&
                dates.size() >= 3 && spend.compareTo(request.targetCpa()
                        .multiply(BigDecimal.valueOf(2))) >= 0) {
            out.add(advice("SPEND_WITHOUT_PURCHASES",account,campaign,name,currency,
                    "MEDIUM","LOW","INVESTIGATE","Investimento sem compras atribuídas",
                    "O gasto excedeu duas vezes o CPA máximo informado e não há compras "
                    + "atribuídas nos registros disponíveis. Verifique o rastreamento, "
                    + "o destino dos anúncios e a janela de atribuição; não pause automaticamente.",
                    append(evidence,metric("TARGET_CPA",request.targetCpa())),
                    List.of("ATTRIBUTION_MAY_ARRIVE_LATER","HISTORICAL_COVERAGE_NOT_VERIFIED")));
        }

        if (enough && roas != null && request.targetRoas() != null &&
                roas.compareTo(request.targetRoas()) < 0) {
            out.add(advice("ROAS_BELOW_TARGET",account,campaign,name,currency,"MEDIUM",
                    confidence,"INVESTIGATE","Retorno atribuído abaixo da meta",
                    "O ROAS atribuído está abaixo do ROAS-alvo informado. "
                    + "Investigue o desempenho antes de investir mais; este dado não "
                    + "demonstra a causa da diferença.",
                    append(evidence,metric("ROAS",roas),
                            metric("TARGET_ROAS",request.targetRoas())),
                    List.of("HISTORICAL_COVERAGE_NOT_VERIFIED","META_ATTRIBUTION_CAN_CHANGE")));
        }

        if (enough && cpa != null && request.targetCpa() != null &&
                cpa.compareTo(request.targetCpa()) > 0) {
            out.add(advice("CPA_ABOVE_TARGET",account,campaign,name,currency,"MEDIUM",
                    confidence,"INVESTIGATE","Custo por compra acima da meta",
                    "O custo por compra atribuída supera o CPA máximo informado. "
                    + "Investigue a eficiência sem presumir problema no criativo ou no público.",
                    append(evidence,metric("CPA",cpa),metric("TARGET_CPA",request.targetCpa())),
                    List.of("HISTORICAL_COVERAGE_NOT_VERIFIED","META_ATTRIBUTION_CAN_CHANGE")));
        }

        if (periodDays >= 14) {
            // Comparable calendar halves. Do not infer zero for missing dates.
            LocalDate secondHalf = request.since().plusDays(periodDays/2);
            var earlier = totals(rows.stream().filter(r->r.day().isBefore(secondHalf)).toList());
            var later = totals(rows.stream().filter(r->!r.day().isBefore(secondHalf)).toList());
            if (earlier.days >= 2 && later.days >= 2 &&
                    earlier.impressions >= 500 && later.impressions >= 500 &&
                    earlier.clicks >= 10 && later.clicks >= 10) {
                BigDecimal beforeCtr = ratio(BigDecimal.valueOf(earlier.clicks).multiply(
                        BigDecimal.valueOf(100)),BigDecimal.valueOf(earlier.impressions));
                BigDecimal afterCtr = ratio(BigDecimal.valueOf(later.clicks).multiply(
                        BigDecimal.valueOf(100)),BigDecimal.valueOf(later.impressions));
                BigDecimal beforeCpc = ratio(earlier.spend,BigDecimal.valueOf(earlier.clicks));
                BigDecimal afterCpc = ratio(later.spend,BigDecimal.valueOf(later.clicks));
                if (beforeCtr.signum()>0 && afterCtr.compareTo(beforeCtr.multiply(
                        CTR_DECLINE_RATIO)) <= 0) {
                    out.add(advice("CTR_DECLINING",account,campaign,name,currency,
                            "LOW","LOW","INVESTIGATE","Taxa de cliques em queda",
                            "O CTR calculado com cliques totais caiu pelo menos 30% "
                            + "entre duas metades do período. Investigue a comunicação "
                            + "e a distribuição antes de concluir que o criativo é a causa.",
                            append(evidence,metric("CTR_PREVIOUS_PCT",beforeCtr),
                                    metric("CTR_RECENT_PCT",afterCtr)),
                            List.of("ALL_CLICKS_NOT_LINK_CLICKS","HISTORICAL_COVERAGE_NOT_VERIFIED")));
                }
                if (beforeCpc != null && beforeCpc.signum()>0 &&
                        afterCpc.compareTo(beforeCpc.multiply(CPC_RISE_RATIO)) >= 0) {
                    out.add(advice("CPC_INCREASING",account,campaign,name,currency,
                            "LOW","LOW","INVESTIGATE","Custo por clique em alta",
                            "O CPC calculado com cliques totais subiu pelo menos 30% "
                            + "entre as metades do período. Investigue a tendência, "
                            + "sem atribuir a causa a um anúncio específico.",
                            append(evidence,metric("CPC_PREVIOUS",beforeCpc),
                                    metric("CPC_RECENT",afterCpc)),
                            List.of("ALL_CLICKS_NOT_LINK_CLICKS","HISTORICAL_COVERAGE_NOT_VERIFIED")));
                }
            }
        }

        boolean goalsPresent = request.targetRoas() != null || request.targetCpa() != null;
        boolean goalsMet = (request.targetRoas() == null ||
                roas != null && roas.compareTo(request.targetRoas()) >= 0)
                && (request.targetCpa() == null ||
                cpa != null && cpa.compareTo(request.targetCpa()) <= 0);
        if (enough && goalsPresent && goalsMet) {
            boolean candidate = purchases.compareTo(TEN) >= 0;
            out.add(advice(candidate ? "FAVORABLE_PERFORMANCE" : "WITHIN_TARGETS",
                    account,campaign,name,currency,"LOW","LOW","MONITOR_ONLY",
                    candidate ? "Desempenho favorável: observar possível oportunidade" :
                            "Indicadores dentro das metas informadas",
                    candidate ? "As metas foram atendidas e há pelo menos 10 compras "
                            + "atribuídas. A cobertura não verificada impede recomendar "
                            + "escala automática. Monitore a estabilidade." :
                            "As metas informadas foram atendidas nos registros atuais. "
                            + "Não há evidência suficiente para recomendar escala.",
                    append(evidence,metric("ROAS",roas),metric("CPA",cpa)),
                    List.of("HISTORICAL_COVERAGE_NOT_VERIFIED","NO_AUTOMATIC_BUDGET_CHANGE")));
        }
        if (out.isEmpty()) {
            out.add(advice("MONITOR_PERFORMANCE",account,campaign,name,currency,"LOW",
                    confidence,"MONITOR_ONLY","Continuar monitorando a campanha",
                    "Os indicadores persistidos não acionaram uma regra de investigação "
                    + "conclusiva. Isso não significa que a campanha esteja otimizada.",
                    append(evidence,metric("ROAS",roas),metric("CPA",cpa),
                            metric("CTR_PCT",ctr),metric("CPC",cpc)),
                    List.of("HISTORICAL_COVERAGE_NOT_VERIFIED")));
        }
        return out;
    }

    private static boolean valid(DailyCampaign row, Request request) {
        return row.day() != null && !row.day().isBefore(request.since()) &&
                !row.day().isAfter(request.until()) &&
                row.currency() != null && row.currency().matches("[A-Z]{3}") &&
                row.spend() != null && row.spend().signum() >= 0 &&
                row.attributedPurchases() != null && row.attributedPurchases().signum() >= 0 &&
                row.attributedRevenue() != null && row.attributedRevenue().signum() >= 0 &&
                row.clicks() >= 0 && row.impressions() >= 0;
    }

    private record Half(long days, long impressions, long clicks, BigDecimal spend) {}
    private static Half totals(List<DailyCampaign> rows) {
        long impressions=0,clicks=0;
        BigDecimal spend=ZERO;
        for(var row:rows){
            impressions=Math.addExact(impressions,row.impressions());
            clicks=Math.addExact(clicks,row.clicks());
            spend=spend.add(row.spend());
        }
        return new Half(rows.size(),impressions,clicks,spend);
    }

    private static BigDecimal ratio(BigDecimal numerator,BigDecimal denominator) {
        return denominator == null || denominator.signum() == 0 ? null :
                numerator.divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private static Metric metric(String name,BigDecimal value) {
        return new Metric(name,value,LIMITATION);
    }
    private static Metric metric(String name,long value) {
        return metric(name,BigDecimal.valueOf(value));
    }

    private static List<Metric> append(List<Metric> metrics,Metric... additions) {
        var result=new ArrayList<>(metrics);
        for(var metric:additions) if(metric.value()!=null) result.add(metric);
        return List.copyOf(result);
    }

    private static Advice advice(String code,String account,String campaign,String name,
            String currency,String priority,String confidence,String action,
            String title,String justification,List<Metric> metrics,List<String> limits) {
        return new Advice(code,RULE_VERSION,account,campaign,name,currency,priority,
                confidence,action,title,justification,List.copyOf(metrics),List.copyOf(limits));
    }
}
