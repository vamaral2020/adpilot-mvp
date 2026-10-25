package br.com.adpilot.integrations.meta;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;

@Component
public class MetaAdsClient {
    private static final int MAX_PAGE = 100;
    private static final List<String> CAMPAIGN_FIELDS =
            List.of("id", "name", "objective", "status", "effective_status", "daily_budget", "lifetime_budget");
    private static final List<String> INSIGHT_FIELDS =
            List.of("date_start", "date_stop", "campaign_id", "campaign_name",
                    "spend", "impressions", "clicks", "ctr", "cpc", "actions", "action_values");
    private final MetaSettings settings;
    private final RestClient client;

    public MetaAdsClient(RestClient.Builder builder, MetaSettings settings) {
        this.settings = settings;
        var httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(20));
        // A Graph API pode responder JSON com Content-Type text/javascript.
        // O suporte adicional fica restrito a este RestClient, nao ao MVC da API.
        this.client = builder.baseUrl(settings.baseUrl())
                .requestFactory(requestFactory)
                .configureMessageConverters(converters -> converters.withJsonConverter(metaJsonConverter()))
                .build();
    }

    static JacksonJsonHttpMessageConverter metaJsonConverter() {
        var converter = new JacksonJsonHttpMessageConverter();
        converter.setSupportedMediaTypes(List.of(
                MediaType.APPLICATION_JSON,
                new MediaType("application", "*+json"),
                new MediaType("text", "javascript")));
        return converter;
    }

    public Map<String, Object> account() {
        var body = get("", Map.of("fields", "id,name,currency,account_status"));
        return project(body, List.of("id", "name", "currency", "account_status"));
    }

    public MetaPage campaigns(int limit, String after) {
        validatePage(limit, after);
        var params = new LinkedHashMap<String, Object>();
        params.put("fields", String.join(",", CAMPAIGN_FIELDS));
        params.put("limit", limit);
        if (after != null && !after.isBlank()) params.put("after", after);
        return page(get("campaigns", params), CAMPAIGN_FIELDS);
    }

    public MetaPage insights(LocalDate since, LocalDate until, int limit, String after) {
        validatePage(limit, after);
        if (since == null || until == null || since.isAfter(until)
                || ChronoUnit.DAYS.between(since, until) > 89
                || until.isAfter(LocalDate.now().plusDays(1))) {
            throw new MetaApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE",
                    "Periodo invalido: informe de 1 a 90 dias, sem datas futuras.");
        }
        var params = new LinkedHashMap<String, Object>();
        params.put("fields", String.join(",", INSIGHT_FIELDS));
        params.put("level", "campaign");
        params.put("time_increment", 1);
        params.put("time_range", "{\"since\":\"" + since + "\",\"until\":\"" + until + "\"}");
        params.put("limit", limit);
        if (after != null && !after.isBlank()) params.put("after", after);
        return page(get("insights", params), INSIGHT_FIELDS);
    }

    /**
     * Prevent an authorized multi-account Meta token from being used to inspect
     * campaigns outside the configured AdPilot account.
     */
    public boolean belongsToConfiguredAccount(String campaignId) {
        validateCampaignId(campaignId);
        var campaign=getCampaign(campaignId,"",Map.of("fields","id,account_id"));
        Object owner=campaign.get("account_id");
        Object id=campaign.get("id");
        if(owner==null || id==null || !campaignId.equals(String.valueOf(id)))
            return false;
        String account=String.valueOf(owner);
        if(account.startsWith("act_")) account=account.substring(4);
        return account.equals(settings.accountId());
    }

    /**
     * A campaign-scoped, read-only view of ad sets, including the optimization
     * goal and destination. Never include audience targeting or user data.
     */
    public MetaPage adsets(String campaignId, int limit, String after) {
        validateCampaignId(campaignId);
        validatePage(limit,after);
        var params=new LinkedHashMap<String,Object>();
        params.put("fields","id,name,campaign_id,optimization_goal,destination_type,status,effective_status");
        params.put("limit",limit);
        if(after!=null && !after.isBlank()) params.put("after",after);
        return page(getCampaign(campaignId,"adsets",params),
                List.of("id","name","campaign_id","optimization_goal","destination_type",
                        "status","effective_status"));
    }

    /**
     * Creative specifications are internal to the adapter. The controller must
     * only return a format category and fixed allowlisted metadata, never raw
     * story specs, asset-feed specs, destination URLs, ad text or tokens.
     */
    public MetaPage ads(String campaignId, int limit, String after) {
        validateCampaignId(campaignId);
        validatePage(limit,after);
        var params=new LinkedHashMap<String,Object>();
        params.put("fields","id,name,campaign_id,adset_id,status,effective_status,"
                + "creative{id,object_type,object_story_spec,asset_feed_spec,product_set_id}");
        params.put("limit",limit);
        if(after!=null && !after.isBlank()) params.put("after",after);
        return page(getCampaign(campaignId,"ads",params),
                List.of("id","name","campaign_id","adset_id","status","effective_status",
                        "creative"));
    }

    // PR053: bounded, on-demand diagnostic, distinct from persisted campaign insights.
    // Never request targeting, customer data, ad copy or asset metadata here.
    private static final List<String> DELIVERY_INSIGHT_FIELDS = List.of(
            "campaign_id","adset_id","ad_id","spend","impressions","clicks",
            "inline_link_clicks","actions","action_values","attribution_setting");
    private static final List<String> DELIVERY_ADSET_FIELDS = List.of(
            "id","campaign_id","name","optimization_goal","billing_event",
            "bid_strategy","destination_type","effective_status","promoted_object");

    /** Verify ownership before any campaign-scoped request. */
    public Map<String,Object> deliveryCampaign(String id) {
        validateCampaignId(id);
        var row=getCampaign(id,"",Map.of("fields","id,account_id,objective,effective_status"));
        if(!id.equals(String.valueOf(row.get("id")))
                || !settings.accountId().equals(
                        String.valueOf(row.get("account_id")).replaceFirst("^act_","")))
            throw new MetaApiException(HttpStatus.NOT_FOUND,"CAMPAIGN_NOT_IN_ACCOUNT",
                    "Campanha nao identificada na conta Meta configurada.");
        return project(row,List.of("id","objective","effective_status"));
    }

    /** Minimum ad-set delivery configuration, with promoted event only (no pixel ID). */
    public MetaPage deliveryAdsets(String campaignId,int limit,String after) {
        validateCampaignId(campaignId);
        validatePage(limit,after);
        var query=new LinkedHashMap<String,Object>();
        query.put("fields",String.join(",",DELIVERY_ADSET_FIELDS));
        query.put("limit",limit);
        if(after!=null && !after.isBlank()) query.put("after",after);
        MetaPage original=page(getCampaign(campaignId,"adsets",query),DELIVERY_ADSET_FIELDS);
        var sanitized=new java.util.ArrayList<Map<String,Object>>();
        for(var raw:original.data()) {
            var row=new LinkedHashMap<>(raw);
            Object promoted=row.remove("promoted_object");
            if(promoted instanceof Map<?,?> detail) {
                // Only recognized event type, never pixel_id, custom_event_id,
                // application ID or anything from targeting.
                String event=String.valueOf(detail.get("custom_event_type"));
                if(List.of("PURCHASE","ADD_TO_CART","INITIATED_CHECKOUT",
                        "LEAD","COMPLETE_REGISTRATION").contains(event))
                    row.put("promoted_event",event);
            }
            row.values().removeIf(java.util.Objects::isNull);
            sanitized.add(Map.copyOf(row));
        }
        return new MetaPage(List.copyOf(sanitized),original.after());
    }

    /** Ad-set or ad insights, aggregated for the requested calendar range. */
    public MetaPage deliveryInsights(String campaignId,String level,
            LocalDate since,LocalDate until,int limit,String after) {
        validateCampaignId(campaignId);
        validatePage(limit,after);
        if(!"adset".equals(level)&&!"ad".equals(level))
            throw new MetaApiException(HttpStatus.BAD_REQUEST,"INVALID_INSIGHTS_LEVEL",
                    "Nivel de detalhamento invalido.");
        if(since==null || until==null || since.isAfter(until)
                || ChronoUnit.DAYS.between(since,until)>30
                || until.isAfter(LocalDate.now(java.time.ZoneOffset.UTC)))
            throw new MetaApiException(HttpStatus.BAD_REQUEST,"INVALID_DATE_RANGE",
                    "Informe periodo completo de 1 a 31 dias, sem datas futuras.");
        var query=new LinkedHashMap<String,Object>();
        query.put("fields",String.join(",",DELIVERY_INSIGHT_FIELDS));
        query.put("level",level);
        query.put("time_range","{\"since\":\""+since+"\",\"until\":\""+until+"\"}");
        query.put("limit",limit);
        if(after!=null&&!after.isBlank()) query.put("after",after);
        return page(getCampaign(campaignId,"insights",query),DELIVERY_INSIGHT_FIELDS);
    }

    private static void validateCampaignId(String id) {
        if(id==null || !id.matches("[0-9]{1,40}"))
            throw new MetaApiException(HttpStatus.BAD_REQUEST,"INVALID_CAMPAIGN_ID",
                    "Informe um identificador numerico de campanha.");
    }

    private static void validatePage(int limit, String after) {
        if (limit < 1 || limit > MAX_PAGE || (after != null && after.length() > 2048)) {
            throw new MetaApiException(HttpStatus.BAD_REQUEST, "INVALID_PAGINATION",
                    "Limit deve estar entre 1 e 100 e o cursor deve ser valido.");
        }
    }

    private Map<String, Object> get(String endpoint, Map<String, ?> query) {
        if (!settings.configured()) {
            throw new MetaApiException(HttpStatus.SERVICE_UNAVAILABLE, "META_NOT_CONFIGURED",
                    "Configure META_AD_ACCOUNT_ID (somente digitos) e META_ACCESS_TOKEN no ambiente.");
        }
        String path = "/" + settings.version() + "/act_" + settings.accountId()
                + (endpoint.isEmpty() ? "" : "/" + endpoint);
        return getPath(path,query);
    }

    private Map<String,Object> getCampaign(String campaignId,String edge,Map<String,?> query) {
        if (!settings.configured()) {
            throw new MetaApiException(HttpStatus.SERVICE_UNAVAILABLE,"META_NOT_CONFIGURED",
                    "Configure a conta Meta e o token de acesso.");
        }
        return getPath("/"+settings.version()+"/"+campaignId+
                (edge.isBlank()?"":"/"+edge),query);
    }

    private Map<String,Object> getPath(String path,Map<String,?> query) {
        try {
            var result = client.get()
                    .uri(buildUri(settings.baseUrl(), path, query))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + settings.accessToken())
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            if (result == null) {
                throw new MetaApiException(HttpStatus.BAD_GATEWAY, "META_EMPTY_RESPONSE",
                        "A Meta retornou uma resposta vazia.");
            }
            return result;
        } catch (RestClientResponseException e) {
            int upstreamStatus = e.getStatusCode().value();
            // Retorna apenas os identificadores numericos; nunca propaga o JSON original
            // nem a URL da requisicao, que podem conter dados sensiveis.
            var details = MetaGraphErrorDetails.fromJson(e.getResponseBodyAsString());
            if (upstreamStatus == 429) {
                throw new MetaApiException(HttpStatus.SERVICE_UNAVAILABLE, "META_RATE_LIMIT",
                        "Limite de requisicoes da Meta atingido; tente novamente mais tarde.",
                        upstreamStatus, details.code(), details.subcode());
            }
            if (upstreamStatus == 401 || upstreamStatus == 403 || upstreamStatus == 400) {
                throw new MetaApiException(HttpStatus.BAD_GATEWAY, "META_PERMISSION_OR_REQUEST",
                        "A Meta rejeitou a requisicao. Confira token, ads_read, conta e parametros.",
                        upstreamStatus, details.code(), details.subcode());
            }
            throw new MetaApiException(HttpStatus.BAD_GATEWAY, "META_UPSTREAM_ERROR",
                    "Falha ao consultar a API da Meta.",
                    upstreamStatus, details.code(), details.subcode());
        } catch (UnknownContentTypeException e) {
            // Nao ler ou expor a resposta bruta: pode ser HTML, erro de proxy
            // ou dados sensiveis. Incluir apenas MIME e status para diagnostico.
            var mime = e.getContentType();
            String type = mime.getType() + "/" + mime.getSubtype();
            throw new MetaApiException(HttpStatus.BAD_GATEWAY, "META_UNSUPPORTED_CONTENT_TYPE",
                    "A Meta retornou formato HTTP inesperado: " + type + ".",
                    e.getStatusCode().value(), null, null);
        } catch (RestClientException e) {
            var failure = MetaClientFailureDetails.from(e);
            throw new MetaApiException(HttpStatus.SERVICE_UNAVAILABLE, "META_UNAVAILABLE",
                    "Nao foi possivel acessar a API da Meta.",
                    failure.clientException(), failure.rootException());
        }
    }

    /**
     * Builds an already encoded URI, bypassing Spring URI template expansion.
     * The Meta time_range value is JSON containing { and }, which Spring
     * otherwise treats as placeholders and throws IllegalArgumentException.
     *
     * Authentication stays in the Authorization header, never the URI.
     */
    static URI buildUri(String baseUrl, String path, Map<String, ?> query) {
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        var url = new StringBuilder(root).append(path);
        if (!query.isEmpty()) {
            var params = new StringJoiner("&");
            query.forEach((name, value) -> params.add(
                    URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                            + URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8)));
            url.append('?').append(params);
        }
        return URI.create(url.toString());
    }

    private static Map<String, Object> project(Map<?, ?> source, List<String> fields) {
        var sanitized = new LinkedHashMap<String, Object>();
        for (var field : fields) {
            if (source.containsKey(field)) sanitized.put(field, source.get(field));
        }
        return sanitized;
    }

    private static MetaPage page(Map<String, Object> response, List<String> fields) {
        var rows = new java.util.ArrayList<Map<String, Object>>();
        if (response.get("data") instanceof List<?> data) {
            for (var item : data) {
                if (item instanceof Map<?, ?> map) rows.add(project(map, fields));
            }
        }
        String after = null;
        // Cursor so representa uma proxima pagina quando a Meta realmente
        // fornece paging.next. Cursors.after sozinho pode existir na ultima pagina.
        if (response.get("paging") instanceof Map<?, ?> paging
                && paging.get("next") instanceof String next && !next.isBlank()
                && paging.get("cursors") instanceof Map<?, ?> cursors
                && cursors.get("after") instanceof String cursor && !cursor.isBlank()) {
            after = cursor;
        }
        // Nunca retornar "paging.next": essa URL pode conter access_token.
        return new MetaPage(rows, after);
    }
}
