package br.com.adpilot.integrations.meta;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Read-only view of the Purchase action taxonomies already present in the
 * existing Ads Insights response. Does not read Pixel configuration, Events
 * Manager, CAPI delivery, deduplication or individual customer events.
 */
@Service
public class MetaPurchaseAuditService {
    private static final int MAX_PAGES = 20;
    static final List<String> PURCHASE_TYPES = List.of(
            "purchase", "omni_purchase",
            "offsite_conversion.fb_pixel_purchase", "onsite_web_purchase");
    private final MetaAdsClient client;

    public MetaPurchaseAuditService(MetaAdsClient client) {
        this.client = client;
    }

    public Audit audit(LocalDate since, LocalDate until, int pageSize, int maxPages) {
        if (since == null || until == null || since.isAfter(until)
                || ChronoUnit.DAYS.between(since, until) > 89
                || until.isAfter(LocalDate.now(ZoneOffset.UTC).plusDays(1))) {
            throw new MetaApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "Informe periodo valido de ate 90 dias.");
        }
        if (pageSize < 1 || pageSize > 100 || maxPages < 1 || maxPages > MAX_PAGES) {
            throw new MetaApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                    "pageSize deve ser 1 a 100 e maxPages deve ser 1 a 20.");
        }

        var categories = new LinkedHashMap<String, MutableCategory>();
        for (String type : PURCHASE_TYPES) categories.put(type, new MutableCategory());
        var selectedCountTypes = new LinkedHashMap<String, Integer>();
        var selectedValueTypes = new LinkedHashMap<String, Integer>();
        var seenCursors = new HashSet<String>();
        BigDecimal selectedPurchases = BigDecimal.ZERO;
        BigDecimal selectedRevenue = BigDecimal.ZERO;
        int pages = 0, rows = 0, rowsWithoutActions = 0, rowsWithoutActionValues = 0;
        int rowsWithDifferentSelectedTypes = 0;
        String after = null;
        boolean complete = false;
        do {
            MetaPage page = client.insights(since, until, pageSize, after);
            pages++;
            if (page.data() == null || (page.data().isEmpty() && page.after() != null)) {
                throw invalid("Paginacao inconsistente nos Insights da Meta.");
            }
            for (Map<String, Object> row : page.data()) {
                if (row == null) throw invalid("Linha ausente nos Insights da Meta.");
                rows++;
                Map<String, BigDecimal> actions = extract(row.get("actions"));
                Map<String, BigDecimal> actionValues = extract(row.get("action_values"));
                if (actions.isEmpty()) rowsWithoutActions++;
                if (actionValues.isEmpty()) rowsWithoutActionValues++;
                for (String type : PURCHASE_TYPES) {
                    var bucket = categories.get(type);
                    if (actions.containsKey(type)) {
                        bucket.rowsWithCount++;
                        bucket.count = bucket.count.add(actions.get(type));
                    }
                    if (actionValues.containsKey(type)) {
                        bucket.rowsWithValue++;
                        bucket.value = bucket.value.add(actionValues.get(type));
                    }
                }
                String chosenCount = firstAvailable(actions);
                String chosenValue = firstAvailable(actionValues);
                if (chosenCount != null) {
                    selectedCountTypes.merge(chosenCount, 1, Integer::sum);
                    selectedPurchases = selectedPurchases.add(actions.get(chosenCount));
                }
                if (chosenValue != null) {
                    selectedValueTypes.merge(chosenValue, 1, Integer::sum);
                    selectedRevenue = selectedRevenue.add(actionValues.get(chosenValue));
                }
                if (chosenCount != null && chosenValue != null
                        && !chosenCount.equals(chosenValue)) rowsWithDifferentSelectedTypes++;
            }
            after = page.after();
            if (after == null) {
                complete = true;
                break;
            }
            if (after.isBlank() || after.length() > 2048 || !seenCursors.add(after)) {
                throw invalid("Cursor repetido ou invalido na auditoria da Meta.");
            }
        } while (pages < maxPages);

        var warnings = new ArrayList<String>();
        warnings.add("TAXONOMIES_NOT_ADDITIVE");
        warnings.add("PIXEL_CAPI_DEDUP_NOT_VERIFIED");
        warnings.add("META_ATTRIBUTION_NOT_PAID_STORE_ORDERS");
        if (!complete) warnings.add("INSIGHTS_INCOMPLETE");
        if (rowsWithDifferentSelectedTypes > 0) warnings.add("COUNT_VALUE_TYPES_DIFFER");
        if (selectedPurchases.signum() == 0) warnings.add("NO_ATTRIBUTED_PURCHASES");
        var breakdown = new ArrayList<Category>();
        for (var entry : categories.entrySet()) {
            var a = entry.getValue();
            breakdown.add(new Category(entry.getKey(), a.rowsWithCount,
                    a.count, a.rowsWithValue, a.value));
        }
        // Incomplete samples may be inspected as samples, never as account totals.
        return new Audit(since, until, complete, pages, rows,
                complete ? selectedPurchases : null,
                complete ? selectedRevenue : null,
                rowsWithoutActions, rowsWithoutActionValues,
                rowsWithDifferentSelectedTypes, Map.copyOf(selectedCountTypes),
                Map.copyOf(selectedValueTypes), List.copyOf(breakdown), List.copyOf(warnings));
    }

    private static String firstAvailable(Map<String, BigDecimal> values) {
        for (String type : PURCHASE_TYPES) if (values.containsKey(type)) return type;
        return null;
    }

    /**
     * Extracts only a documented action taxonomy and its numeric aggregate.
     * All unrelated actions, event IDs and user data are dropped.
     */
    static Map<String, BigDecimal> extract(Object raw) {
        if (raw == null) return Map.of();
        if (!(raw instanceof List<?> items)) throw invalid("Acoes Meta em formato inesperado.");
        var safe = new LinkedHashMap<String, BigDecimal>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> action)) throw invalid("Acao Meta invalida.");
            Object type = action.get("action_type");
            if (!(type instanceof String label) || !PURCHASE_TYPES.contains(label)) continue;
            Object rawValue = action.get("value");
            BigDecimal value;
            try {
                if (rawValue == null) throw new NumberFormatException("missing");
                value = new BigDecimal(rawValue.toString());
                if (value.signum() < 0 || value.scale() > 8) throw new NumberFormatException("invalid");
            } catch (NumberFormatException e) {
                throw invalid("Metrica de compra invalida na Meta.");
            }
            // Same first-occurrence precedence as MetaInsightsMetrics.Accumulator.
            safe.putIfAbsent(label, value);
        }
        return safe;
    }

    private static MetaApiException invalid(String message) {
        return new MetaApiException(HttpStatus.BAD_GATEWAY, "META_INVALID_PURCHASE_AUDIT", message);
    }

    private static final class MutableCategory {
        int rowsWithCount;
        BigDecimal count = BigDecimal.ZERO;
        int rowsWithValue;
        BigDecimal value = BigDecimal.ZERO;
    }
    public record Category(String actionType, int rowsWithCount, BigDecimal count,
                           int rowsWithValue, BigDecimal value) {}
    public record Audit(LocalDate since, LocalDate until, boolean complete,
            int pagesFetched, int recordsFetched,
            BigDecimal selectedPurchases, BigDecimal selectedRevenue,
            int rowsWithoutPurchaseActions, int rowsWithoutPurchaseValues,
            int rowsWithDifferentSelectedTypes,
            Map<String, Integer> selectedCountTypes,
            Map<String, Integer> selectedValueTypes,
            List<Category> actionBreakdown, List<String> warnings) {}
}
