package br.com.adpilot.integrations.meta;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
        this.client = builder.baseUrl(settings.baseUrl()).requestFactory(requestFactory).build();
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
        try {
            var result = client.get()
                    .uri(uri -> {
                        uri.path(path);
                        query.forEach(uri::queryParam);
                        return uri.build();
                    })
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
        } catch (RestClientException e) {
            throw new MetaApiException(HttpStatus.SERVICE_UNAVAILABLE, "META_UNAVAILABLE",
                    "Nao foi possivel acessar a API da Meta.");
        }
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
        if (response.get("paging") instanceof Map<?, ?> paging
                && paging.get("cursors") instanceof Map<?, ?> cursors
                && cursors.get("after") instanceof String cursor) {
            after = cursor;
        }
        // Nunca retornar "paging.next": essa URL pode conter access_token.
        return new MetaPage(rows, after);
    }
}
