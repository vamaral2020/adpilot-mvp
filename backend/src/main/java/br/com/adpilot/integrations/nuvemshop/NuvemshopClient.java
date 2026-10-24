package br.com.adpilot.integrations.nuvemshop;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
    private static final String ORDER_SYNC_FIELDS =
            "id,number,status,payment_status,total,currency,created_at,updated_at,paid_at";
    private static final List<String> ORDER_SYNC_PROJECTION = List.of(
            "id","number","status","payment_status","total","currency",
            "created_at","updated_at","paid_at");
    private static final String ORDER_FIELDS =
            "id,number,status,payment_status,shipping_status,total,currency,created_at,paid_at,products";
    // Audit projection excludes names, amounts, addresses, transaction details and PII.
    private static final String PAYMENT_AUDIT_FIELDS =
            "id,payment_status,paid_at,created_at,updated_at,status,shipping_status,gateway,storefront,app_id";
    private static final List<String> PAYMENT_AUDIT_PROJECTION =
            List.of("id", "payment_status", "paid_at", "created_at", "updated_at",
                    "status", "shipping_status", "gateway", "storefront", "app_id");
    // PR040: read gross order fields only; exclude customer, items and transactions.
    private static final String ORDER_VALUE_AUDIT_FIELDS =
            "id,status,payment_status,total,currency";
    private static final List<String> ORDER_VALUE_AUDIT_PROJECTION =
            List.of("id", "status", "payment_status", "total", "currency");
    // PR042: provider + buyer-reported amounts. No transaction IDs or buyer PII.
    private static final String ORDER_PAYMENT_SIGNALS_FIELDS =
            "id,status,payment_status,total,currency,gateway,gateway_name,"
            + "total_paid_by_customer,total_paid_by_customer_including_fees,payment_details";
    private static final List<String> ORDER_PAYMENT_SIGNALS_PROJECTION = List.of(
            "id","status","payment_status","total","currency","gateway","gateway_name",
            "total_paid_by_customer","total_paid_by_customer_including_fees","payment_details");
    private static final List<String> PAYMENT_METHOD_PROJECTION = List.of("method");
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

    /** Time-bounded incremental window, no customer or line-item PII. */
    public NuvemshopPage ordersUpdatedBetween(java.time.Instant since,
            java.time.Instant until, int page, int perPage) {
        if (since == null || until == null || !since.isBefore(until)) {
            throw new NuvemshopException(HttpStatus.BAD_REQUEST, "INVALID_SYNC_WINDOW",
                    "Janela incremental invalida.");
        }
        return read("orders", ORDER_SYNC_FIELDS, page, perPage, ORDER_SYNC_PROJECTION,
                "", List.of(), Map.of("updated_at_min", since.toString(),
                        "updated_at_max", until.toString()));
    }

    private static final String PAID_DETAIL_FIELDS =
            "id,number,status,payment_status,paid_at,total,currency";
    private static final List<String> PAID_DETAIL_PROJECTION = List.of(
            "id", "number", "status", "payment_status", "paid_at", "total", "currency");

    /**
     * Read-only detail lookup: the order listing can report paid_at=null
     * while GET /orders/{id} contains the actual payment timestamp.
     * The returned map is a strict allowlist, never the upstream full payload.
     */
    public Map<String, Object> paidOrderDetails(long id) {
        if (id <= 0) {
            throw new NuvemshopException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_ID",
                    "Identificador de pedido invalido.");
        }
        var installation = repository.installation().orElseThrow(() ->
                new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE, "NUVEMSHOP_NOT_CONNECTED",
                        "Conecte a loja Nuvemshop antes de consultar pedidos."));
        String path = "/" + settings.apiVersion() + "/" + installation.storeId() + "/orders/" + id;
        URI uri = URI.create(settings.apiBase() + path + "?fields="
                + URLEncoder.encode(PAID_DETAIL_FIELDS, StandardCharsets.UTF_8));
        try {
            var result = client.get().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + crypto.decrypt(installation.tokenEncrypted()))
                    .header(HttpHeaders.USER_AGENT, settings.userAgent())
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            if (result == null) {
                throw new NuvemshopException(HttpStatus.BAD_GATEWAY, "NUVEMSHOP_EMPTY_RESPONSE",
                        "A Nuvemshop retornou pedido sem conteudo.");
            }
            return sanitize(result, PAID_DETAIL_PROJECTION, "", List.of());
        } catch (RestClientResponseException e) {
            int upstream = e.getStatusCode().value();
            throw new NuvemshopException(upstream == 429 ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.BAD_GATEWAY,
                    upstream == 429 ? "NUVEMSHOP_RATE_LIMIT" : "NUVEMSHOP_ORDER_DETAIL_ERROR",
                    "Falha na consulta individual do pedido da Nuvemshop.", upstream);
        } catch (RestClientException e) {
            throw new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE, "NUVEMSHOP_UNAVAILABLE",
                    "A API da Nuvemshop esta indisponivel.");
        }
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
     * Operator-only gross-order value comparison; read-only and unfiltered.
     * No PII, line items, payment transaction amounts or refunds are fetched.
     * Does not advance the analytics cursor or modify any saved snapshots.
     */
    public NuvemshopPage orderValueAuditOrders(int page, int perPage) {
        return read("orders", ORDER_VALUE_AUDIT_FIELDS, page, perPage,
                ORDER_VALUE_AUDIT_PROJECTION, "", List.of());
    }

    /**
     * Provider/method classification and buyer-reported totals; no payment
     * transaction references, credentials, cardholder or refund ledger.
     */
    public NuvemshopPage orderPaymentSignals(int page, int perPage) {
        return read("orders", ORDER_PAYMENT_SIGNALS_FIELDS, page, perPage,
                ORDER_PAYMENT_SIGNALS_PROJECTION, "payment_details", PAYMENT_METHOD_PROJECTION);
    }


    /**
     * Order VALUE history, not a Payment Transaction ledger or settlement proof.
     * This merchant-order endpoint differs from /orders/{id}/transactions,
     * which is reserved for Payment Apps. Never logs or exposes raw events.
     */
    public List<Map<String,Object>> orderValueHistory(long id) {
        if (id <= 0) {
            throw new NuvemshopException(HttpStatus.BAD_REQUEST, "INVALID_ORDER_ID",
                    "Identificador de pedido invalido.");
        }
        var installation = repository.installation().orElseThrow(() ->
                new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE, "NUVEMSHOP_NOT_CONNECTED",
                        "Conecte a loja Nuvemshop antes da consulta."));
        String path = "/" + settings.apiVersion() + "/" + installation.storeId()
                + "/orders/" + id + "/history/values";
        URI uri = URI.create(settings.apiBase() + path);
        try {
            var result = client.get().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + crypto.decrypt(installation.tokenEncrypted()))
                    .header(HttpHeaders.USER_AGENT, settings.userAgent())
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String,Object>>>() {});
            if (result == null) {
                throw new NuvemshopException(HttpStatus.BAD_GATEWAY, "ORDER_VALUE_HISTORY_EMPTY_RESPONSE",
                        "A Nuvemshop nao retornou uma lista de historico.");
            }
            if (result.size() > 100) {
                throw new NuvemshopException(HttpStatus.BAD_GATEWAY, "ORDER_VALUE_HISTORY_TOO_LARGE",
                        "Historico individual excedeu o limite de diagnostico.");
            }
            var allowed = List.of("status","total_paid_diff");
            var safe = new ArrayList<Map<String,Object>>();
            for (Map<String,Object> entry : result) {
                if (entry == null) {
                    throw new NuvemshopException(HttpStatus.BAD_GATEWAY, "ORDER_VALUE_HISTORY_INVALID",
                            "Historico individual invalido.");
                }
                safe.add(sanitize(entry,allowed,"",List.of()));
            }
            return List.copyOf(safe);
        } catch (RestClientResponseException e) {
            int upstream = e.getStatusCode().value();
            String code = upstream == 429 ? "NUVEMSHOP_RATE_LIMIT"
                    : upstream == 401 || upstream == 403 ? "ORDER_VALUE_HISTORY_DENIED"
                    : upstream == 404 ? "ORDER_VALUE_HISTORY_NOT_FOUND"
                    : "ORDER_VALUE_HISTORY_UPSTREAM_ERROR";
            throw new NuvemshopException(upstream == 429 ? HttpStatus.SERVICE_UNAVAILABLE
                    : HttpStatus.BAD_GATEWAY, code,
                    "Falha ao consultar historico de valores da Nuvemshop.",upstream);
        } catch (RestClientException e) {
            throw new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE,
                    "NUVEMSHOP_UNAVAILABLE", "A Nuvemshop esta indisponivel.");
        }
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

    /**
     * Operator-only, opt-in, read-only diagnostic of order-list URL parameters.
     * Intentionally discards every response body and never returns paths, dates,
     * order IDs, store IDs or tokens. It does not persist snapshots or cursors.
     */
    public record OrderListProbe(String scenario, String outcome, Integer httpStatus) {}

    private record ProbeCase(String scenario, int perPage, String fields,
                             Map<String,String> filters) {}

    public List<OrderListProbe> probeOrderList(Instant since, Instant until) {
        if (since == null || until == null || !since.isBefore(until))
            throw new NuvemshopException(HttpStatus.BAD_REQUEST,"INVALID_SYNC_WINDOW",
                    "Janela de diagnostico invalida.");
        var installation = repository.installation().orElseThrow(() ->
                new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE,
                        "NUVEMSHOP_NOT_CONNECTED","Conecte a loja antes do diagnostico."));
        String token = crypto.decrypt(installation.tokenEncrypted());
        var exact = Map.of("updated_at_min",since.toString(),
                "updated_at_max",until.toString());
        var seconds = Map.of(
                "updated_at_min",since.truncatedTo(ChronoUnit.SECONDS).toString(),
                "updated_at_max",until.truncatedTo(ChronoUnit.SECONDS).toString());
        var cases = List.of(
                new ProbeCase("BASELINE_SAME_FIELDS",100,ORDER_SYNC_FIELDS,Map.of()),
                new ProbeCase("RANGE_EXACT",100,ORDER_SYNC_FIELDS,exact),
                new ProbeCase("LOWER_ONLY",100,ORDER_SYNC_FIELDS,
                        Map.of("updated_at_min",since.toString())),
                new ProbeCase("UPPER_ONLY",100,ORDER_SYNC_FIELDS,
                        Map.of("updated_at_max",until.toString())),
                new ProbeCase("RANGE_SECONDS",100,ORDER_SYNC_FIELDS,seconds),
                new ProbeCase("RANGE_MINIMAL_FIELDS",100,"id,updated_at",exact),
                new ProbeCase("RANGE_PER_PAGE_ONE",1,ORDER_SYNC_FIELDS,exact));
        var results = new ArrayList<OrderListProbe>();
        for (ProbeCase check : cases) {
            URI uri = readUri("orders",check.fields(),1,check.perPage(),
                    installation.storeId(),check.filters());
            try {
                int status=client.get().uri(uri)
                        .header(HttpHeaders.AUTHORIZATION,"Bearer "+token)
                        .header(HttpHeaders.USER_AGENT,settings.userAgent())
                        .retrieve().toBodilessEntity().getStatusCode().value();
                results.add(new OrderListProbe(check.scenario(),"HTTP_STATUS",status));
                if (status==429) break;
            } catch (RestClientResponseException e) {
                int status=e.getStatusCode().value();
                results.add(new OrderListProbe(check.scenario(),"HTTP_STATUS",status));
                if (status==429) break; // Honor provider rate limits.
            } catch (RestClientException e) {
                results.add(new OrderListProbe(check.scenario(),"TRANSPORT_FAILURE",null));
                break; // No exception message, URL or response body is exposed.
            }
        }
        return List.copyOf(results);
    }

    /**
     * Bounded follow-up probe: same two date representations are each repeated
     * against page 1. Page 2 is requested ONLY when the exact page-1 response
     * advertises it via Link or a validated total count. All upstream bodies
     * are discarded; returned diagnostics never contain request URLs or data.
     */
    public record OrderListFollowup(boolean nextPageAdvertised,
            boolean totalExceedsPage, List<OrderListProbe> checks) {}

    private record ProbeStatus(Integer status, boolean hasNext,
            boolean countOverPage, boolean transportFailure) {}

    public OrderListFollowup probeOrderListFollowup(Instant since, Instant until) {
        if (since == null || until == null || !since.isBefore(until))
            throw new NuvemshopException(HttpStatus.BAD_REQUEST,"INVALID_SYNC_WINDOW",
                    "Janela de diagnostico invalida.");
        var installation = repository.installation().orElseThrow(() ->
                new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE,
                        "NUVEMSHOP_NOT_CONNECTED","Conecte a loja antes do diagnostico."));
        String token = crypto.decrypt(installation.tokenEncrypted());
        var exact = Map.of("updated_at_min",since.toString(),
                "updated_at_max",until.toString());
        var seconds = Map.of(
                "updated_at_min",since.truncatedTo(ChronoUnit.SECONDS).toString(),
                "updated_at_max",until.truncatedTo(ChronoUnit.SECONDS).toString());
        var checks = new ArrayList<OrderListProbe>();

        ProbeStatus first = probeStatusOnly(installation.storeId(), token, 1, exact);
        checks.add(safeProbeResult("EXACT_PAGE_1",first));
        if (first.transportFailure() || Integer.valueOf(429).equals(first.status()))
            return new OrderListFollowup(first.hasNext(),first.countOverPage(),List.copyOf(checks));

        ProbeStatus shortened = probeStatusOnly(installation.storeId(), token, 1, seconds);
        checks.add(safeProbeResult("SECONDS_PAGE_1",shortened));
        if (shortened.transportFailure() || Integer.valueOf(429).equals(shortened.status()))
            return new OrderListFollowup(first.hasNext(),first.countOverPage(),List.copyOf(checks));

        ProbeStatus exactRepeat = probeStatusOnly(installation.storeId(), token, 1, exact);
        checks.add(safeProbeResult("EXACT_PAGE_1_REPEAT",exactRepeat));
        if (exactRepeat.transportFailure() || Integer.valueOf(429).equals(exactRepeat.status()))
            return new OrderListFollowup(first.hasNext(),first.countOverPage(),List.copyOf(checks));

        ProbeStatus secondsRepeat = probeStatusOnly(installation.storeId(), token, 1, seconds);
        checks.add(safeProbeResult("SECONDS_PAGE_1_REPEAT",secondsRepeat));
        if (secondsRepeat.transportFailure() || Integer.valueOf(429).equals(secondsRepeat.status()))
            return new OrderListFollowup(first.hasNext(),first.countOverPage(),List.copyOf(checks));

        // A page-2 request without upstream evidence of a next page is not
        // meaningful and can return 404 for a non-existent page.
        if (Integer.valueOf(200).equals(first.status())
                && (first.hasNext() || first.countOverPage())) {
            ProbeStatus next = probeStatusOnly(installation.storeId(),token,2,exact);
            checks.add(safeProbeResult("EXACT_PAGE_2",next));
        }
        return new OrderListFollowup(first.hasNext(),first.countOverPage(),List.copyOf(checks));
    }


    /**
     * Diagnostic only: compare the same lower instant expressed with different
     * ISO-8601 zone offsets, and older lower instants in the same syntax.
     * Uses the exact same URI builder, fields, page and headers as production.
     * Never downloads order bodies, stores data or changes the sync cursor.
     * Maximum seven HTTP GETs. Abort on rate limiting or transport failures.
     */
    public List<OrderListProbe> probeOrderLowerBoundFormats(Instant lower, Instant upper) {
        if (lower == null || upper == null || !lower.isBefore(upper))
            throw new NuvemshopException(HttpStatus.BAD_REQUEST,"INVALID_SYNC_WINDOW",
                    "Janela de diagnostico invalida.");

        var installation = repository.installation().orElseThrow(() ->
                new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE,
                        "NUVEMSHOP_NOT_CONNECTED","Conecte a loja antes do diagnostico."));
        String token = crypto.decrypt(installation.tokenEncrypted());
        Instant wholeSeconds = lower.truncatedTo(ChronoUnit.SECONDS);
        var withColon = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");
        var compact = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxx");
        var scenarios = List.of(
                new ProbeCase("BASELINE_NO_DATE",100,ORDER_SYNC_FIELDS,Map.of()),
                new ProbeCase("MIN_UTC_Z_SECONDS",100,ORDER_SYNC_FIELDS,
                        Map.of("updated_at_min",wholeSeconds.toString())),
                new ProbeCase("MIN_UTC_PLUS00",100,ORDER_SYNC_FIELDS,
                        Map.of("updated_at_min",withColon.format(
                                wholeSeconds.atOffset(ZoneOffset.UTC)))),
                new ProbeCase("MIN_BR_MINUS03",100,ORDER_SYNC_FIELDS,
                        Map.of("updated_at_min",withColon.format(
                                wholeSeconds.atOffset(ZoneOffset.ofHours(-3))))),
                new ProbeCase("MIN_UTC_PLUS0000",100,ORDER_SYNC_FIELDS,
                        Map.of("updated_at_min",compact.format(
                                wholeSeconds.atOffset(ZoneOffset.UTC)))),
                new ProbeCase("MIN_OLDER_24H_Z",100,ORDER_SYNC_FIELDS,
                        Map.of("updated_at_min",wholeSeconds.minus(1,ChronoUnit.DAYS).toString())),
                new ProbeCase("MIN_OLDER_7D_Z",100,ORDER_SYNC_FIELDS,
                        Map.of("updated_at_min",wholeSeconds.minus(7,ChronoUnit.DAYS).toString()))
        );
        var results = new ArrayList<OrderListProbe>();
        for (var check : scenarios) {
            ProbeStatus status=probeStatusOnly(installation.storeId(),token,1,check.filters());
            results.add(safeProbeResult(check.scenario(),status));
            if(status.transportFailure() || Integer.valueOf(429).equals(status.status()))
                break;
        }
        return List.copyOf(results);
    }

    private OrderListProbe safeProbeResult(String label, ProbeStatus result) {
        return new OrderListProbe(label,
                result.transportFailure() ? "TRANSPORT_FAILURE" : "HTTP_STATUS",
                result.status());
    }

    private ProbeStatus probeStatusOnly(String storeId,String token,int page,
                                        Map<String,String> filters) {
        URI uri=readUri("orders",ORDER_SYNC_FIELDS,page,100,storeId,filters);
        try {
            var response=client.get().uri(uri)
                    .header(HttpHeaders.AUTHORIZATION,"Bearer "+token)
                    .header(HttpHeaders.USER_AGENT,settings.userAgent())
                    .retrieve().toBodilessEntity();
            String link=response.getHeaders().getFirst(HttpHeaders.LINK);
            boolean hasNext=link!=null && (
                    link.contains("rel=\"next\"") || link.contains("rel=next"));
            String count=response.getHeaders().getFirst("x-total-count");
            boolean countOverPage=false;
            if (count!=null && count.matches("[0-9]{1,9}")) {
                try {
                    countOverPage=Long.parseLong(count)>100L;
                } catch (NumberFormatException ignored) {
                    // Invalid upstream totals must not be trusted.
                }
            }
            return new ProbeStatus(response.getStatusCode().value(),hasNext,
                    countOverPage,false);
        } catch (RestClientResponseException e) {
            return new ProbeStatus(e.getStatusCode().value(),false,false,false);
        } catch (RestClientException e) {
            return new ProbeStatus(null,false,false,true);
        }
    }

    /** URI builder shared with production order-list reads for exact comparisons. */
    private URI readUri(String resource,String fields,int page,int perPage,
                        String storeId,Map<String,String> filters) {
        String path = "/" + settings.apiVersion() + "/" + storeId + "/" + resource;
        StringBuilder query = new StringBuilder(settings.apiBase()).append(path)
                .append("?page=").append(page).append("&per_page=").append(perPage)
                .append("&fields=").append(URLEncoder.encode(fields,StandardCharsets.UTF_8));
        filters.forEach((key,value)-> {
            if (!key.equals("updated_at_min") && !key.equals("updated_at_max"))
                throw new IllegalArgumentException("Filtro nao permitido");
            query.append('&').append(key).append('=')
                    .append(URLEncoder.encode(value,StandardCharsets.UTF_8));
        });
        return URI.create(query.toString());
    }

    private NuvemshopPage read(String resource, String fields, int page, int perPage,
                               List<String> allowed, String nested, List<String> allowedNested) {
        return read(resource, fields, page, perPage, allowed, nested, allowedNested, Map.of());
    }

    private NuvemshopPage read(String resource, String fields, int page, int perPage,
                               List<String> allowed, String nested, List<String> allowedNested,
                               Map<String, String> filters) {
        if (page < 1 || page > 100 || perPage < 1 || perPage > 100
                || (long) page * perPage > 10_000) {
            throw new NuvemshopException(HttpStatus.BAD_REQUEST, "NUVEMSHOP_INVALID_PAGINATION",
                    "page deve estar entre 1 e 100, perPage entre 1 e 100, ate 10000 resultados.");
        }
        var installation = repository.installation().orElseThrow(() ->
                new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE, "NUVEMSHOP_NOT_CONNECTED",
                        "Conecte a loja Nuvemshop antes de consultar produtos e pedidos."));
        String token = crypto.decrypt(installation.tokenEncrypted());
        URI uri = readUri(resource,fields,page,perPage,installation.storeId(),filters);
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
            if (field.equals(nestedField) && value instanceof Map<?,?> entryMap) {
                var projected = new LinkedHashMap<String,Object>();
                for (String allow : nestedAllowed) {
                    if (entryMap.containsKey(allow)) projected.put(allow, entryMap.get(allow));
                }
                safe.put(field,projected);
            } else if (field.equals(nestedField) && value instanceof List<?> entries) {
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
