package br.com.adpilot.integrations.meta;

public record MetaCampaignInsights(
        String campaignId,
        String campaignName,
        MetaInsightsMetrics metrics) { }
