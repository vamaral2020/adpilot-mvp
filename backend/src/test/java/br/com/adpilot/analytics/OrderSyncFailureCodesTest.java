package br.com.adpilot.analytics;

import static org.junit.jupiter.api.Assertions.*;
import br.com.adpilot.integrations.nuvemshop.NuvemshopException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

class OrderSyncFailureCodesTest {
    @Test void allowsOnlyKnownValidationCodes() {
        assertEquals("ORDER_BEFORE_LOWER_BOUND",
                OrderSyncFailureCodes.classify(
                        new IllegalStateException("ORDER_BEFORE_LOWER_BOUND"),
                        OrderSyncFailureCodes.Stage.COLLECT));
        assertEquals("ORDER_PAGE_TOTAL_INCOMPLETE",
                OrderSyncFailureCodes.classify(
                        new IllegalStateException("SHOP_TOTAL_INCOMPLETE"),
                        OrderSyncFailureCodes.Stage.COLLECT));
        assertEquals("ORDER_SOURCE_DATA_INVALID",
                OrderSyncFailureCodes.classify(
                        new IllegalStateException("INVALID_SOURCE_DATE"),
                        OrderSyncFailureCodes.Stage.COLLECT));
    }

    @Test void neverEchoesArbitraryMessagesOrProviderCodes() {
        var exception=new IllegalStateException("customer.email=user@example.com token=secret");
        assertEquals("ORDER_COLLECTION_FAILED",
                OrderSyncFailureCodes.classify(exception,OrderSyncFailureCodes.Stage.COLLECT));
        assertEquals("ORDER_COLLECTION_FAILED",
                OrderSyncFailureCodes.classify(
                        new IllegalStateException((String)null),
                        OrderSyncFailureCodes.Stage.COLLECT));
        assertEquals("ORDER_API_FAILURE",
                OrderSyncFailureCodes.classify(
                        new NuvemshopException(HttpStatus.BAD_GATEWAY,
                                "unexpected_token_value", "secrets"),
                        OrderSyncFailureCodes.Stage.COLLECT));
    }

    @Test void classifiesHttpAndDatabaseFailuresSeparately() {
        assertEquals("ORDER_API_HTTP_429",
                OrderSyncFailureCodes.classify(
                        new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE,
                                "NUVEMSHOP_RATE_LIMIT","Rate limited",429),
                        OrderSyncFailureCodes.Stage.COLLECT));
        assertEquals("ORDER_DB_WRITE_FAILED",
                OrderSyncFailureCodes.classify(
                        new DataIntegrityViolationException("PII in driver message"),
                        OrderSyncFailureCodes.Stage.PERSIST));
        assertEquals("ORDER_PERSIST_FAILED",
                OrderSyncFailureCodes.classify(
                        new IllegalStateException("some unsafe error"),
                        OrderSyncFailureCodes.Stage.PERSIST));
    }


    @Test void differentiatesRecurringOrder404ByWhitelistedReadOperation() {
        var list404 = new NuvemshopException(HttpStatus.BAD_GATEWAY,
                "NUVEMSHOP_UPSTREAM_ERROR", "secret order URL and customer data", 404);
        var detail404 = new NuvemshopException(HttpStatus.BAD_GATEWAY,
                "NUVEMSHOP_ORDER_DETAIL_ERROR", "secret order URL and customer data", 404);
        var unknown404 = new NuvemshopException(HttpStatus.BAD_GATEWAY,
                "SECRET_DYNAMIC_PROVIDER_CODE", "secret order URL and customer data", 404);

        assertEquals("ORDER_LIST_HTTP_404",
                OrderSyncFailureCodes.classify(list404,OrderSyncFailureCodes.Stage.COLLECT));
        assertEquals("ORDER_DETAIL_HTTP_404",
                OrderSyncFailureCodes.classify(detail404,OrderSyncFailureCodes.Stage.COLLECT));
        assertEquals("ORDER_API_HTTP_404",
                OrderSyncFailureCodes.classify(unknown404,OrderSyncFailureCodes.Stage.COLLECT));
        // Non-404 codes retain previous public contract to minimize behavioral change.
        assertEquals("ORDER_API_HTTP_422",
                OrderSyncFailureCodes.classify(
                        new NuvemshopException(HttpStatus.BAD_GATEWAY,
                                "NUVEMSHOP_UPSTREAM_ERROR","secret",422),
                        OrderSyncFailureCodes.Stage.COLLECT));
        for (var error: new RuntimeException[]{list404,detail404,unknown404}) {
            var safe = OrderSyncFailureCodes.classify(error,OrderSyncFailureCodes.Stage.COLLECT);
            assertTrue(safe.length() <= 64 && safe.matches("[A-Z0-9_]+"));
            assertFalse(safe.contains("secret"));
        }
    }

    @Test void everyCategoryFitsPersistentErrorCodeColumn() {
        RuntimeException[] examples={
                new IllegalStateException("ORDER_BEFORE_LOWER_BOUND"),
                new IllegalStateException("INVALID_SOURCE_IDENTIFIER"),
                new IllegalStateException("ORDER_PAID_AT_UNKNOWN"),
                new NuvemshopException(HttpStatus.BAD_GATEWAY,
                        "NUVEMSHOP_UPSTREAM_ERROR","hidden",503),
                new IllegalStateException("unknown")
        };
        for (RuntimeException e : examples) {
            String code=OrderSyncFailureCodes.classify(e,OrderSyncFailureCodes.Stage.COLLECT);
            assertTrue(code.length()<=64);
            assertTrue(code.matches("[A-Z0-9_]+"));
        }
    }
}
