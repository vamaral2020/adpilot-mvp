package br.com.adpilot.integrations.meta;

import static org.junit.jupiter.api.Assertions.*;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;

class MetaClientFailureDetailsTest {
    @Test
    void extractsOnlyExceptionClassNamesWithoutSecretMessages() {
        var error = new ResourceAccessException(
                "Failed GET https://example.invalid/?access_token=SECRET-123",
                new SSLHandshakeException("sensitive SSL details SECRET-123"));
        var details = MetaClientFailureDetails.from(error);
        assertEquals("ResourceAccessException", details.clientException());
        assertEquals("SSLHandshakeException", details.rootException());

        var response = new MetaApiExceptionHandler().handle(new MetaApiException(
                HttpStatus.SERVICE_UNAVAILABLE, "META_UNAVAILABLE",
                "Nao foi possivel acessar a API da Meta.",
                details.clientException(), details.rootException()));
        assertEquals(503, response.getStatusCode().value());
        assertEquals("ResourceAccessException", response.getBody().get("client_exception"));
        assertEquals("SSLHandshakeException", response.getBody().get("root_exception"));
        assertFalse(response.getBody().toString().contains("SECRET-123"));
    }

    @Test
    void canIdentifyDnsFailure() {
        var error = new ResourceAccessException("get failed",
                new UnknownHostException("graph.facebook.com"));
        assertEquals("UnknownHostException", MetaClientFailureDetails.from(error).rootException());
    }

    @Test
    void handlesNoNestedCause() {
        var error = new org.springframework.web.client.RestClientException("SECRET");
        var details = MetaClientFailureDetails.from(error);
        assertEquals("RestClientException", details.clientException());
        assertEquals("RestClientException", details.rootException());
        assertFalse(details.toString().contains("SECRET"));
    }
}
