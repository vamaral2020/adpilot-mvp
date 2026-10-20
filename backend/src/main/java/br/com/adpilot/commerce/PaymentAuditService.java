package br.com.adpilot.commerce;

import br.com.adpilot.integrations.nuvemshop.NuvemshopClient;
import br.com.adpilot.integrations.nuvemshop.NuvemshopException;
import br.com.adpilot.integrations.nuvemshop.NuvemshopPage;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class PaymentAuditService {
    private static final DateTimeFormatter BASIC_OFFSET =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ");
    private static final int MAX_PAGES = 100;

    private final NuvemshopClient shop;

    public PaymentAuditService(NuvemshopClient shop) {
        this.shop = shop;
    }

    public PaymentAuditResponse audit(int pageSize, int maxPages) {
        if (pageSize < 1 || pageSize > 100 || maxPages < 1 || maxPages > MAX_PAGES) {
            throw new NuvemshopException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                    "pageSize e maxPages devem estar entre 1 e 100.");
        }

        Set<String> seenIds = new HashSet<>();
        Map<String, Long> statuses = new HashMap<>();
        Map<String, Long> creationMonths = new HashMap<>();
        Map<String, Long> gatewayGroups = new HashMap<>();
        Map<String, Long> storefrontGroups = new HashMap<>();
        Map<String, Long> appGroups = new HashMap<>();
        Map<String, Long> orderStatusGroups = new HashMap<>();
        Map<String, Long> paymentMonths = new HashMap<>();
        int pages = 0;
        int fetched = 0;
        int duplicates = 0;
        long paid = 0;
        long withDate = 0;
        long withoutDate = 0;
        long invalidDate = 0;
        Long total = null;
        boolean reachedEnd = false;

        for (int currentPage = 1; currentPage <= maxPages; currentPage++) {
            NuvemshopPage page = shop.paymentAuditOrders(currentPage, pageSize);
            pages++;
            if (page.data() == null || page.data().isEmpty() && page.hasNext()) {
                throw new NuvemshopException(HttpStatus.BAD_GATEWAY, "NUVEMSHOP_INVALID_PAGINATION",
                        "A Nuvemshop retornou paginacao de auditoria inconsistente.");
            }
            fetched = Math.addExact(fetched, page.data().size());
            if (page.totalCount() != null) total = page.totalCount();

            for (Map<String, Object> order : page.data()) {
                if (order == null || !validId(order.get("id"))) {
                    throw new NuvemshopException(HttpStatus.BAD_GATEWAY, "NUVEMSHOP_INVALID_ORDER_DATA",
                            "A auditoria recebeu um identificador de pedido invalido.");
                }
                String id = order.get("id").toString(); // Internal dedup only; never serialized or logged.
                if (!seenIds.add(id)) {
                    duplicates++;
                    continue;
                }
                String status = paymentStatus(order.get("payment_status"));
                increase(statuses, status);
                if (!"paid".equals(status)) continue;

                paid++;
                String paidAt = order.get("paid_at") instanceof String s ? s : null;
                if (paidAt == null || paidAt.isBlank()) {
                    withoutDate++;
                } else {
                    OffsetDateTime parsed = parseDate(paidAt);
                    if (parsed == null) {
                        invalidDate++;
                    } else {
                        withDate++;
                        increase(paymentMonths, YearMonth.from(parsed).toString());
                        continue;
                    }
                }

                // Non-PII correlations. These categories do not establish the
                // actual payment event, its cause or its timestamp.
                increase(creationMonths, monthOrUnknown(order.get("created_at")));
                increase(gatewayGroups, gateway(order.get("gateway")));
                increase(storefrontGroups, storefront(order.get("storefront")));
                increase(appGroups, appAssociation(order.get("app_id")));
                increase(orderStatusGroups, orderStatus(order.get("status")));
            }
            if (!page.hasNext()) {
                reachedEnd = true;
                break;
            }
        }

        boolean complete = reachedEnd && (total == null || seenIds.size() >= total);
        var warnings = new ArrayList<String>();
        if (!complete) warnings.add("PAGINATION_INCOMPLETE");
        if (duplicates > 0) warnings.add("DUPLICATE_IDS_IGNORED");
        if (withoutDate > 0) warnings.add("PAID_AT_MISSING");
        if (invalidDate > 0) warnings.add("PAID_AT_INVALID");
        warnings.add("CREATED_AT_IS_NOT_PAYMENT_DATE");
        warnings.add("ORIGIN_GROUPS_ARE_CORRELATIONS_ONLY");
        warnings.add("TRANSACTION_HISTORY_NOT_QUERIED");

        return new PaymentAuditResponse(complete, pages, fetched,
                seenIds.size(), total, duplicates,
                paid, withDate, withoutDate, invalidDate,
                buckets(statuses), buckets(creationMonths), buckets(gatewayGroups),
                buckets(storefrontGroups), buckets(appGroups),
                buckets(orderStatusGroups), buckets(paymentMonths), List.copyOf(warnings));
    }

    private static void increase(Map<String, Long> map, String key) {
        map.merge(key, 1L, Long::sum);
    }

    private static List<PaymentAuditResponse.Bucket> buckets(Map<String, Long> source) {
        var sorted = new TreeMap<>(source);
        return sorted.entrySet().stream()
                .map(e -> new PaymentAuditResponse.Bucket(e.getKey(), e.getValue()))
                .toList();
    }

    private static boolean validId(Object value) {
        return value != null && value.toString().matches("[0-9]{1,30}");
    }

    private static String paymentStatus(Object status) {
        if (!(status instanceof String s)) return "missing";
        return switch (s) {
            case "paid", "pending", "authorized", "partially_paid", "refunded",
                    "partially_refunded", "voided", "abandoned" -> s;
            default -> s.isBlank() ? "missing" : "other";
        };
    }

    private static String gateway(Object source) {
        if (!(source instanceof String s) || s.isBlank()) return "missing";
        return switch (s) {
            case "offline", "not-provided" -> s;
            default -> "other";
        };
    }

    private static String storefront(Object source) {
        if (!(source instanceof String s) || s.isBlank()) return "missing";
        return switch (s) {
            case "store", "api" -> s;
            default -> "other";
        };
    }

    private static String orderStatus(Object source) {
        if (!(source instanceof String s) || s.isBlank()) return "missing";
        return switch (s) {
            case "open", "closed", "cancelled" -> s;
            default -> "other";
        };
    }

    private static String appAssociation(Object id) {
        return id == null || id.toString().isBlank() ? "no_app_id" : "has_app_id";
    }

    private static String monthOrUnknown(Object input) {
        if (!(input instanceof String s)) return "unknown";
        OffsetDateTime timestamp = parseDate(s);
        return timestamp == null ? "unknown" : YearMonth.from(timestamp).toString();
    }

    private static OffsetDateTime parseDate(String input) {
        try {
            return OffsetDateTime.parse(input, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(input, BASIC_OFFSET);
            } catch (DateTimeParseException invalid) {
                return null;
            }
        }
    }
}
