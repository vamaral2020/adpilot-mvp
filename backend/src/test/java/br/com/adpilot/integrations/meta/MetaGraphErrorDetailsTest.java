package br.com.adpilot.integrations.meta;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MetaGraphErrorDetailsTest {
    @Test
    void extractsCodesFromMetaErrorsWithoutReturningCredentials() {
        String upstream = """
            {"error":{"message":"Access denied, access_token=SECRET123",
              "type":"OAuthException","code":200,
              "error_subcode":1234567,"fbtrace_id":"abc"}}
            """;
        var details = MetaGraphErrorDetails.fromJson(upstream);
        assertEquals(200, details.code());
        assertEquals(1234567, details.subcode());

        var response = new MetaApiExceptionHandler().handle(
                new MetaApiException(HttpStatus.BAD_GATEWAY, "META_PERMISSION_OR_REQUEST",
                        "A Meta rejeitou a requisicao.", 403, details.code(), details.subcode()));
        assertEquals(502, response.getStatusCode().value());
        assertEquals(403, response.getBody().get("meta_http_status"));
        assertEquals(200, response.getBody().get("meta_error_code"));
        assertEquals(1234567, response.getBody().get("meta_error_subcode"));
        assertFalse(response.getBody().toString().contains("SECRET123"));
        assertFalse(response.getBody().toString().contains("Access denied"));
    }

    @Test
    void handlesAbsentOrMalformedErrorsWithoutExceptions() {
        assertNull(MetaGraphErrorDetails.fromJson("").code());
        assertNull(MetaGraphErrorDetails.fromJson(null).subcode());
        assertNull(MetaGraphErrorDetails.fromJson("unauthorized").code());
        assertNull(MetaGraphErrorDetails.fromJson("{\"code\":999999999999999999}").code());

        var response = new MetaApiExceptionHandler().handle(
                new MetaApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "Invalid range"));
        assertFalse(response.getBody().containsKey("meta_error_code"));
        assertFalse(response.getBody().containsKey("meta_http_status"));
    }
}
