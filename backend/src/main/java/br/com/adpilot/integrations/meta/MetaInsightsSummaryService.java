package br.com.adpilot.integrations.meta;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class MetaInsightsSummaryService {
    static final int MAX_PAGES = 20;
    private final MetaAdsClient client;

    public MetaInsightsSummaryService(MetaAdsClient client) {
        this.client = client;
    }

    public MetaInsightsSummary summary(LocalDate since, LocalDate until, int pageSize, int maxPages) {
        if (pageSize < 1 || pageSize > 100 || maxPages < 1 || maxPages > MAX_PAGES) {
            throw new MetaApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                    "pageSize deve estar entre 1 e 100 e maxPages entre 1 e 20.");
        }

        var totals = new MetaInsightsMetrics.Accumulator();
        var campaigns = new LinkedHashMap<String, CampaignAccumulator>();
        var seenCursors = new HashSet<String>();
        String after = null;
        int pages = 0;
        int records = 0;

        while (pages < maxPages) {
            MetaPage page = client.insights(since, until, pageSize, after);
            pages++;
            if (page.data().isEmpty() && page.after() != null) {
                throw new MetaApiException(HttpStatus.BAD_GATEWAY, "META_INVALID_PAGINATION",
                        "A Meta retornou uma pagina vazia com cursor de continuacao.");
            }
            for (Map<String, Object> row : page.data()) {
                totals.add(row);
                String id = row.get("campaign_id") instanceof String value ? value : "";
                String name = row.get("campaign_name") instanceof String value ? value : "";
                var campaign = campaigns.computeIfAbsent(id, key -> new CampaignAccumulator(id, name));
                campaign.metrics.add(row);
                records++;
            }

            String nextAfter = page.after();
            if (nextAfter == null) {
                return output(since, until, true, pages, records, null, totals, campaigns);
            }
            if (nextAfter.length() > 2048 || !seenCursors.add(nextAfter)) {
                throw new MetaApiException(HttpStatus.BAD_GATEWAY, "META_INVALID_PAGINATION",
                        "A Meta retornou cursor de paginacao invalido ou repetido.");
            }
            after = nextAfter;
        }

        // Nao apresenta valores parciais como se representassem a conta inteira.
        return output(since, until, false, pages, records, after, totals, campaigns);
    }

    private static MetaInsightsSummary output(LocalDate since, LocalDate until,
            boolean complete, int pages, int records, String after,
            MetaInsightsMetrics.Accumulator totals, Map<String, CampaignAccumulator> campaigns) {
        var results = campaigns.values().stream()
                .map(c -> new MetaCampaignInsights(c.id, c.name, c.metrics.snapshot()))
                .toList();
        return new MetaInsightsSummary(since, until, complete, pages, records,
                after, totals.snapshot(), results);
    }

    private static final class CampaignAccumulator {
        final String id;
        final String name;
        final MetaInsightsMetrics.Accumulator metrics = new MetaInsightsMetrics.Accumulator();

        CampaignAccumulator(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}
