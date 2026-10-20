package br.com.adpilot.integrations.nuvemshop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Temporary, explicitly enabled diagnostic. Upstream full-order JSON may have
 * address/card/contact data, so ONLY a strict, scalar allowlist may leave
 * this class through HTTP or structured logging.
 */
@Service
public class NuvemshopPaymentDiagnosticService {
    private static final Logger LOG = LoggerFactory.getLogger(NuvemshopPaymentDiagnosticService.class);
    private static final List<String> FIELDS = List.of(
            "id", "number", "status", "payment_status", "created_at", "paid_at", "updated_at");
    private final NuvemshopClient client;
    private final JsonMapper jsonMapper;
    private final boolean enabled;

    public NuvemshopPaymentDiagnosticService(NuvemshopClient client, JsonMapper jsonMapper,
            @Value("${adpilot.nuvemshop.payment-diagnostics-enabled:false}") boolean enabled) {
        this.client = client;
        this.jsonMapper = jsonMapper;
        this.enabled = enabled;
    }

    public Diagnostic diagnose(int number) {
        if (!enabled) {
            throw new NuvemshopException(HttpStatus.NOT_FOUND, "DIAGNOSTICS_DISABLED",
                    "Diagnostico desabilitado. Habilite temporariamente em ambiente local.");
        }
        if (number < 1 || number > 999999999) {
            throw new NuvemshopException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_NUMBER",
                    "Numero de pedido invalido.");
        }

        // Reproduce the exact existing /orders?fields=... projection used by
        // CommerceSummaryService, not a synthetic data source.
        Map<String, Object> listed = null;
        boolean paginationComplete = false;
        for (int pageNumber = 1; pageNumber <= 20; pageNumber++) {
            NuvemshopPage page = client.orders(pageNumber, 100);
            for (Map<String, Object> row : page.data()) {
                if (matchesNumber(row.get("number"), number)) {
                    listed = row;
                    break;
                }
            }
            if (listed != null) break;
            if (!page.hasNext()) {
                paginationComplete = true;
                break;
            }
        }
        if (listed == null) {
            throw new NuvemshopException(paginationComplete ? HttpStatus.NOT_FOUND
                    : HttpStatus.CONFLICT, "ORDER_NOT_FOUND_IN_LIST",
                    "Pedido nao encontrado na listagem consultada.");
        }

        long orderId = numericId(listed.get("id"));
        Map<String, Object> individualFiltered = client.diagnosticOrder(orderId, true);
        Map<String, Object> individualFull = client.diagnosticOrder(orderId, false);
        if (numericId(individualFiltered.get("id")) != orderId
                || numericId(individualFull.get("id")) != orderId
                || !matchesNumber(individualFiltered.get("number"), number)
                || !matchesNumber(individualFull.get("number"), number)) {
            throw new NuvemshopException(HttpStatus.BAD_GATEWAY, "ORDER_DIAGNOSTIC_MISMATCH",
                    "Respostas individuais nao correspondem ao pedido solicitado.");
        }

        Diagnostic result = new Diagnostic(number,
                snapshot(listed), snapshot(individualFiltered), snapshot(individualFull),
                "LIST_USES_EXISTING_ORDER_FIELDS; INDIVIDUAL_FILTERED_USES_MINIMAL_FIELDS; "
                + "INDIVIDUAL_FULL_WITHOUT_FIELDS; NO_PAYMENT_DATE_INFERRED");
        // Jackson escapes quotes/control chars. Only whitelisted scalar fields are serialized.
        // One compact JSON line is easy to extract from 'docker compose logs api'.
        LOG.info("NUVEMSHOP_PAYMENT_DIAGNOSTIC {}", jsonMapper.writeValueAsString(result));
        return result;
    }

    public record Field(boolean present, String type, Object value) {}
    public record Diagnostic(int orderNumber,
            Map<String, Field> list, Map<String, Field> individualFiltered,
            Map<String, Field> individualFull, String comparison) {}

    static Map<String, Field> snapshot(Map<String, Object> raw) {
        var result = new LinkedHashMap<String, Field>();
        for (String key : FIELDS) {
            if (!raw.containsKey(key)) {
                result.put(key, new Field(false, "missing", null));
                continue;
            }
            Object value = raw.get(key);
            if (value == null) {
                result.put(key, new Field(true, "null", null));
            } else if (value instanceof Number n
                    && ("id".equals(key) || "number".equals(key))
                    && n.toString().matches("[0-9]{1,30}")) {
                result.put(key, new Field(true, "number", n));
            } else if (value instanceof String s && safeScalar(key, s)) {
                result.put(key, new Field(true, "string", s));
            } else {
                // Never echo unexpected upstream data in a diagnostic log.
                result.put(key, new Field(true, "unexpected", null));
            }
        }
        return result;
    }

    private static boolean safeScalar(String key, String value) {
        if ("id".equals(key) || "number".equals(key)) {
            return value.matches("[0-9]{1,30}");
        }
        if ("created_at".equals(key) || "updated_at".equals(key) || "paid_at".equals(key)) {
            // Retain the exact raw date string, including its offset, if syntactically safe.
            return value.matches("[0-9TZ:+.\\-]{1,64}");
        }
        if ("payment_status".equals(key)) {
            return List.of("paid", "pending", "authorized", "partially_paid",
                    "abandoned", "refunded", "partially_refunded", "voided").contains(value);
        }
        if ("status".equals(key)) {
            return List.of("open", "closed", "cancelled").contains(value);
        }
        return false;
    }

    private static boolean matchesNumber(Object value, int expected) {
        return value != null && Integer.toString(expected).equals(value.toString());
    }

    private static long numericId(Object raw) {
        if (raw == null || !raw.toString().matches("[0-9]{1,18}")) {
            throw new NuvemshopException(HttpStatus.BAD_GATEWAY, "ORDER_DIAGNOSTIC_INVALID_ID",
                    "Identificador da Nuvemshop ausente ou invalido.");
        }
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            throw new NuvemshopException(HttpStatus.BAD_GATEWAY, "ORDER_DIAGNOSTIC_INVALID_ID",
                    "Identificador da Nuvemshop ausente ou invalido.");
        }
    }
}
