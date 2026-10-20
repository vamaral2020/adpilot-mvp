package br.com.adpilot.integrations.nuvemshop;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class NuvemshopClient {
    private static final String PRODUCT_FIELDS = "id,name,handle,variants";
    private static final String ORDER_FIELDS =
            "id,number,status,payment_status,shipping_status,total,currency,created_at,paid_at,products";
    // Audit projection excludes names, amounts, addresses, transaction details and PII.
    private static final String PAYMENT_AUDIT_FIELDS =
            "id,payment_status,paid_at,created_at,updated_at,status,shipping_status,gateway,storefront,app_id";
    private static final List<String> PAYMENT_AUDIT_PROJECTION =
            List.of("id", "payment_status", "paid_at", "created_at", "updated_at",
                    "status", "shipping_status", "gateway", "storefront", "app_id");
    private static final List<String> PRODUCT_PROJECTION = List.of("id", "name", "handle", "variants");
    private static final List<String> VARIANT_PROJECTION =
            List.of("id", "sku", "price", "promotional_price", "stock", "stock_management");
    private static final List<String> ORDER_PROJECTION =
            List.of("id", "number", "status", "payment_status", "shipping_status", "total",
                    "currency", "created_at", "paid_at", "products");
    private static final List<String> ORDER_ITEM_PROJECTION =
            List.of("product_id", "variant_id", "name", "quantity", "price", "sku");

    private final NuvemshopSettings settings;
    private final NuvemshopRepository repository;
    private final NuvemshopCrypto crypto;
    private final RestClient client;

    public NuvemshopClient(NuvemshopSettings settings, NuvemshopRepository repository,
                           NuvemshopCrypto crypto, RestClient.Builder builder) {
        this.settings = settings;
        this.repository = repository;
        this.crypto = crypto;
        var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        this.client = builder.requestFactory(requestFactory).build();
    }

    public NuvemshopPage products(int page, int perPage) {
        return read("products", PRODUCT_FIELDS, page, perPage, PRODUCT_PROJECTION,
                "variants", VARIANT_PROJECTION);
    }

    public NuvemshopPage orders(int page, int perPage) {
        return read("orders", ORDER_FIELDS, page, perPage, ORDER_PROJECTION,
                "products", ORDER_ITEM_PROJECTION);
    }

    /**
     * Minimal, read-only projection for aggregate payment audits; never includes
     * customer records, order products, monetary totals or transaction details.
     */
    public NuvemshopPage paymentAuditOrders(int page, int perPage) {
        return read("orders", PAYMENT_AUDIT_FIELDS, page, perPage,
                PAYMENT_AUDIT_PROJECTION, "", List.of());
    }


    /**
     * Temporary diagnostic: fetch the upstream order JSON without the fields
     * filter or with a minimal field filter. The caller MUST whitelist the
     * response before logging or returning it: full responses may contain PII.
     */
    Map<String, Object> diagnosticOrder(long id, boolean filtered) {
        if (id < 1) {
            throw new NuvemshopException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_ID",
                    "Identificador invalido.");
        }
        var installation = repository.installation().orElseThrow(() ->
                new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE, "NUVEMSHOP_NOT_CONNECTED",
                        "Conecte a loja Nuvemshop antes da consulta."));
        String path = "/" + settings.apiVersion() + "/" + installation.storeId() + "/orders/" + id;
        String fields = "id,number,status,payment_status,created_at,paid_at,updated_at";
        URI uri = URI.create(settings.apiBase() + path +
                (filtered ? "?fields=" + URLEncoder.encode(fields, StandardCharsets.UTF_8) : ""));
        try {
            var data = client.get().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + crypto.decrypt(installation.tokenEncrypted()))
                    .header(HttpHeaders.USER_AGENT, settings.userAgent())
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            if (data == null) {
                throw new NuvemshopException(HttpStatus.BAD_GATEWAY, "NUVEMSHOP_EMPTY_RESPONSE",
                        "Pedido retornado sem conteudo pela Nuvemshop.");
            }
            return data;
        } catch (RestClientResponseException e) {
            int upstream = e.getStatusCode().value();
            throw new NuvemshopException(upstream == 429 ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.BAD_GATEWAY, upstream == 429 ? "NUVEMSHOP_RATE_LIMIT"
                    : "NUVEMSHOP_ORDER_DIAGNOSTIC_ERROR",
                    "Falha ao consultar pedido individual na Nuvemshop.", upstream);
        } catch (RestClientException e) {
            throw new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE, "NUVEMSHOP_UNAVAILABLE",
                    "A API da Nuvemshop esta indisponivel.");
        }
    }

    private NuvemshopPage read(String resource, String fields, int page, int perPage,
                               List<String> allowed, String nested, List<String> allowedNested) {
        if (page < 1 || page > 100 || perPage < 1 || perPage > 100
                || (long) page * perPage > 10_000) {
            throw new NuvemshopException(HttpStatus.BAD_REQUEST, "NUVEMSHOP_INVALID_PAGINATION",
                    "page deve estar entre 1 e 100, perPage entre 1 e 100, ate 10000 resultados.");
        }
        var installation = repository.installation().orElseThrow(() ->
                new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE, "NUVEMSHOP_NOT_CONNECTED",
                        "Conecte a loja Nuvemshop antes de consultar produtos e pedidos."));
        String token = crypto.decrypt(installation.tokenEncrypted());
        String path = "/" + settings.apiVersion() + "/" + installation.storeId() + "/" + resource;
        URI uri = URI.create(settings.apiBase() + path + "?page=" + page + "&per_page=" + perPage
                + "&fields=" + URLEncoder.encode(fields, StandardCharsets.UTF_8));
        try {
            var result = client.get().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .header(HttpHeaders.USER_AGENT, settings.userAgent())
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<List<Map<String, Object>>>() {});
            var rows = new ArrayList<Map<String, Object>>();
            if (result.getBody() != null) {
                for (Map<String, Object> item : result.getBody()) {
                    rows.add(sanitize(item, allowed, nested, allowedNested));
                }
            }
            Long total = null;
            try {
                String count = result.getHeaders().getFirst("x-total-count");
                if (count != null) total = Long.parseLong(count);
            } catch (NumberFormatException ignored) {
                // Missing or invalid upstream count is represented as null.
            }
            String link = result.getHeaders().getFirst(HttpHeaders.LINK);
            boolean hasNext = link != null && (link.contains("rel=\"next\"") || link.contains("rel=next"));
            return new NuvemshopPage(rows, page, perPage, total, hasNext);
        } catch (RestClientResponseException e) {
            int upstream = e.getStatusCode().value();
            String error = upstream == 401 || upstream == 403 ? "NUVEMSHOP_PERMISSION_ERROR"
                    : upstream == 402 ? "NUVEMSHOP_PAYMENT_REQUIRED"
                    : upstream == 429 ? "NUVEMSHOP_RATE_LIMIT" : "NUVEMSHOP_UPSTREAM_ERROR";
            throw new NuvemshopException(upstream == 429 ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.BAD_GATEWAY, error,
                    "Nao foi possivel consultar " + resource + " na Nuvemshop.", upstream);
        } catch (RestClientException e) {
            throw new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE, "NUVEMSHOP_UNAVAILABLE",
                    "A API da Nuvemshop esta indisponivel para consulta.");
        }
    }

    static Map<String, Object> sanitize(Map<?, ?> source, List<String> fields,
                                        String nestedField, List<String> nestedAllowed) {
        var safe = new LinkedHashMap<String, Object>();
        for (String field : fields) {
            Object value = source.get(field);
            if (field.equals(nestedField) && value instanceof List<?> entries) {
                var nested = new ArrayList<Map<String, Object>>();
                for (Object entry : entries) {
                    if (entry instanceof Map<?, ?> entryMap) {
                        var projected = new LinkedHashMap<String, Object>();
                        for (String allow : nestedAllowed) {
                            if (entryMap.containsKey(allow)) projected.put(allow, entryMap.get(allow));
                        }
                        nested.add(projected);
                    }
                }
                safe.put(field, nested);
            } else if (source.containsKey(field)) {
                safe.put(field, value);
            }
        }
        return safe;
    }
}
