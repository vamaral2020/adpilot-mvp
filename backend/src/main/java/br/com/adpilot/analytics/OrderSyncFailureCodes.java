package br.com.adpilot.analytics;

import br.com.adpilot.integrations.nuvemshop.NuvemshopException;
import org.springframework.dao.DataAccessException;

/**
 * Safe, bounded failure taxonomy. Never expose provider response bodies, exception
 * messages, request URLs, customer data or credentials in diagnostics.
 */
final class OrderSyncFailureCodes {
    private OrderSyncFailureCodes() {}

    enum Stage { COLLECT, PERSIST }

    static String classify(RuntimeException failure, Stage stage) {
        if (stage == Stage.PERSIST) {
            return failure instanceof DataAccessException
                    ? "ORDER_DB_WRITE_FAILED" : "ORDER_PERSIST_FAILED";
        }

        if (failure instanceof NuvemshopException upstream) {
            Integer status = upstream.upstreamStatus();
            if (status != null && status >= 400 && status <= 599) {
                return "ORDER_API_HTTP_" + status;
            }
            return switch (upstream.code()) {
                case "NUVEMSHOP_NOT_CONNECTED" -> "ORDER_API_NOT_CONNECTED";
                case "NUVEMSHOP_UNAVAILABLE" -> "ORDER_API_UNAVAILABLE";
                case "NUVEMSHOP_EMPTY_RESPONSE" -> "ORDER_API_EMPTY_RESPONSE";
                case "NUVEMSHOP_INVALID_PAGINATION" -> "ORDER_API_INVALID_PAGINATION";
                case "INVALID_SYNC_WINDOW" -> "ORDER_API_INVALID_WINDOW";
                default -> "ORDER_API_FAILURE";
            };
        }

        if (failure instanceof IllegalStateException) {
            // Exact allowlist only. Unknown messages may contain upstream data.
            return switch (failure.getMessage()) {
                case "ORDER_BEFORE_LOWER_BOUND" -> "ORDER_BEFORE_LOWER_BOUND";
                case "ORDER_AFTER_UPPER_BOUND" -> "ORDER_AFTER_UPPER_BOUND";
                case "INVALID_ORDER_WINDOW" -> "ORDER_WINDOW_INVALID";
                case "SHOP_TOTAL_INCOMPLETE" -> "ORDER_PAGE_TOTAL_INCOMPLETE";
                case "SHOP_PAGE_LIMIT" -> "ORDER_PAGE_LIMIT";
                case "INVALID_SHOP_PAGINATION" -> "ORDER_PAGE_INVALID";
                case "INVALID_SHOP_TOTAL" -> "ORDER_PAGE_TOTAL_INVALID";
                case "DUPLICATE_ORDER_IN_WINDOW" -> "ORDER_PAGE_DUPLICATE";
                case "ORDER_DETAIL_LIMIT" -> "ORDER_DETAIL_LIMIT";
                case "ORDER_DETAIL_MISMATCH" -> "ORDER_DETAIL_MISMATCH";
                case "ORDER_PAID_AT_UNKNOWN" -> "ORDER_PAID_AT_UNKNOWN";
                case "INVALID_ELIGIBLE_SALE" -> "ORDER_SALE_INVALID";
                case "INVALID_SOURCE_IDENTIFIER", "INVALID_SOURCE_STATUS",
                        "INVALID_SOURCE_AMOUNT", "SOURCE_UPDATED_AT_MISSING",
                        "INVALID_SOURCE_DATE", "INVALID_ORDER_DATA"
                        -> "ORDER_SOURCE_DATA_INVALID";
                default -> "ORDER_COLLECTION_FAILED";
            };
        }
        return "ORDER_COLLECTION_FAILED";
    }
}
