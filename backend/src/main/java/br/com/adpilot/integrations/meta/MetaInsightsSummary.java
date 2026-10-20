package br.com.adpilot.integrations.meta;

import java.time.LocalDate;
import java.util.List;

/**
 * complete=false indica limites locais de paginacao. after e cursor opaco
 * para continuar a busca; nao e URL e nunca contem access_token.
 */
public record MetaInsightsSummary(
        LocalDate since,
        LocalDate until,
        boolean complete,
        int pagesFetched,
        int recordsFetched,
        String after,
        MetaInsightsMetrics totals,
        List<MetaCampaignInsights> campaigns) { }
