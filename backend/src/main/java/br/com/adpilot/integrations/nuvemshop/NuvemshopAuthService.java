package br.com.adpilot.integrations.nuvemshop;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class NuvemshopAuthService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger LOG = LoggerFactory.getLogger(NuvemshopAuthService.class);
    private final NuvemshopSettings settings;
    private final NuvemshopRepository repository;
    private final NuvemshopCrypto crypto;
    private final RestClient client;

    public record Authorization(String authorizationUrl, int expiresInSeconds) { }
    public record Status(String status, boolean configured, String storeId,
                         String scopes, java.time.Instant connectedAt) { }

    public NuvemshopAuthService(NuvemshopSettings settings, NuvemshopRepository repository,
                                NuvemshopCrypto crypto, RestClient.Builder builder) {
        this.settings = settings;
        this.repository = repository;
        this.crypto = crypto;
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.client = builder.requestFactory(factory).build();
    }

    public Status status() {
        if (!settings.configured()) return new Status("NOT_CONFIGURED", false, null, null, null);
        return repository.installation()
                .map(it -> new Status("CONNECTED", true, it.storeId(), it.scope(), it.connectedAt()))
                .orElseGet(() -> new Status("NOT_CONNECTED", true, null, null, null));
    }

    public Authorization begin() {
        requireConfigured();
        byte[] randomBytes = new byte[32];
        RANDOM.nextBytes(randomBytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        byte[] digest = digest(state);
        repository.createState(digest);
        // Correlation uses 48 bits of the SHA-256 digest of a 256-bit random state.
        // Never log raw state, authorization code, URLs or token.
        LOG.info("NUVEMSHOP_OAUTH_START state_ref={}", stateRef(digest));
        String url = settings.oauthBase() + "/apps/" + settings.clientId() + "/authorize?state="
                + URLEncoder.encode(state, StandardCharsets.UTF_8);
        // This TTL starts *before* shop login and consent. Nuvemshop's 5-minute
        // authorization-code expiry begins only after code issuance, not now.
        // Allow 15 minutes for interactive installation without weakening the
        // one-time, unpredictable state requirement.
        return new Authorization(url, 900);
    }

    public void complete(String code, String state) {
        requireConfigured();
        if (code == null || code.isBlank() || code.length() > 4096
                || state == null || !state.matches("[A-Za-z0-9_-]{43}")) {
            throw new NuvemshopException(HttpStatus.BAD_REQUEST, "NUVEMSHOP_INVALID_CALLBACK",
                    "Parametros de autorizacao invalidos.");
        }
        byte[] digest = digest(state);
        LOG.info("NUVEMSHOP_OAUTH_CALLBACK state_ref={}", stateRef(digest));
        repository.consumeState(digest);
        LOG.info("NUVEMSHOP_OAUTH_STATE_CONSUMED state_ref={}", stateRef(digest));
        LOG.info("NUVEMSHOP_OAUTH_TOKEN_EXCHANGE_START state_ref={}", stateRef(digest));
        Map<String, Object> payload = Map.of(
                "client_id", settings.clientId(),
                "client_secret", settings.clientSecret(),
                "grant_type", "authorization_code",
                "code", code);
        Map<String, Object> response;
        try {
            response = client.post()
                    .uri(URI.create(settings.tokenUrl()))
                    .accept(MediaType.APPLICATION_JSON)
                    .header(org.springframework.http.HttpHeaders.USER_AGENT, settings.userAgent())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (RestClientResponseException e) {
            LOG.warn("NUVEMSHOP_OAUTH_TOKEN_EXCHANGE_FAILED state_ref={} category=http_rejected upstream_status={}",
                    stateRef(digest), e.getStatusCode().value());
            throw new NuvemshopException(HttpStatus.BAD_GATEWAY, "NUVEMSHOP_OAUTH_REJECTED",
                    "A Nuvemshop rejeitou a troca do codigo de autorizacao.",
                    e.getStatusCode().value());
        } catch (org.springframework.web.client.UnknownContentTypeException e) {
            // Upstream may return HTML (e.g. proxy/login page) with a 2xx HTTP status.
            // Never include the response body: it may contain credentials or the OAuth code.
            var mime = e.getContentType();
            String type = mime.getType() + "/" + mime.getSubtype();
            boolean challenge = e.getResponseHeaders() != null
                    && "challenge".equalsIgnoreCase(e.getResponseHeaders().getFirst("cf-mitigated"));
            LOG.warn("NUVEMSHOP_OAUTH_TOKEN_EXCHANGE_FAILED state_ref={} category={} upstream_status={} content_type={}",
                    stateRef(digest), challenge ? "cloudflare_challenge" : "unexpected_content_type", e.getStatusCode().value(), type);
            throw new NuvemshopException(HttpStatus.BAD_GATEWAY,
                    challenge ? "NUVEMSHOP_OAUTH_CHALLENGE" : "NUVEMSHOP_OAUTH_CONTENT_TYPE",
                    "Resposta OAuth em formato inesperado: " + type + ".",
                    e.getStatusCode().value());
        } catch (RestClientException e) {
            // Class names only. Exception messages or URLs could contain sensitive data.
            Throwable root = e;
            for (int depth = 0; depth < 10 && root.getCause() != null
                    && root.getCause() != root; depth++) {
                root = root.getCause();
            }
            LOG.warn("NUVEMSHOP_OAUTH_TOKEN_EXCHANGE_FAILED state_ref={} category=client_exception exception={} root={}",
                    stateRef(digest), e.getClass().getSimpleName(), root.getClass().getSimpleName());
            throw new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE, "NUVEMSHOP_OAUTH_UNAVAILABLE",
                    "Nao foi possivel concluir a autorizacao com a Nuvemshop.",
                    e.getClass().getSimpleName(), root.getClass().getSimpleName());
        }
        if (response == null) {
            LOG.warn("NUVEMSHOP_OAUTH_TOKEN_EXCHANGE_FAILED state_ref={} category=empty_response",
                    stateRef(digest));
            throw invalidTokenResponse();
        }
        LOG.info("NUVEMSHOP_OAUTH_TOKEN_RESPONSE_RECEIVED state_ref={}", stateRef(digest));
        Object userId = response.get("user_id") == null ? response.get("store_id") : response.get("user_id");
        String storeId = userId == null ? "" : userId.toString();
        String token = response.get("access_token") instanceof String t ? t : "";
        String scope = response.get("scope") instanceof String s ? s : "";
        boolean validStore = storeId.matches("[0-9]{1,40}");
        boolean hasProducts = scopeContains(scope, "read_products");
        boolean hasOrders = scopeContains(scope, "read_orders");
        boolean readOnly = onlyReadScopes(scope);
        if (!validStore || token.isBlank() || !hasProducts || !hasOrders || !readOnly) {
            // No scope values, user_id, access_token or response bodies in logs.
            LOG.warn("NUVEMSHOP_OAUTH_TOKEN_REJECTED state_ref={} valid_store_id={} has_token={} read_products={} read_orders={} read_only={}",
                    stateRef(digest), validStore, !token.isBlank(), hasProducts, hasOrders, readOnly);
            throw invalidTokenResponse();
        }
        // Store only encrypted credentials; OAuth scopes validated for read-only use.
        try {
            repository.saveInstallation(storeId, scope, crypto.encrypt(token));
            LOG.info("NUVEMSHOP_OAUTH_CONNECTED state_ref={}", stateRef(digest));
        } catch (DataAccessException e) {
            LOG.warn("NUVEMSHOP_OAUTH_PERSISTENCE_FAILED state_ref={} exception={}",
                    stateRef(digest), e.getClass().getSimpleName());
            throw new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE,
                    "NUVEMSHOP_OAUTH_PERSISTENCE_FAILED",
                    "Nao foi possivel registrar a conexao da loja no banco.");
        }
    }

    private static boolean scopeContains(String scope, String value) {
        return Arrays.stream(scope.split("[,\\s]+")).anyMatch(value::equals);
    }

    private static boolean onlyReadScopes(String scope) {
        // Fail closed if a Partner Portal app was mistakenly configured with
        // additional permissions, in particular any write_* scope.
        return Arrays.stream(scope.split("[,\\s]+"))
                .filter(s -> !s.isBlank())
                .allMatch(s -> s.equals("read_products") || s.equals("read_orders"));
    }

    private static NuvemshopException invalidTokenResponse() {
        return new NuvemshopException(HttpStatus.BAD_GATEWAY, "NUVEMSHOP_INVALID_OAUTH_RESPONSE",
                "A Nuvemshop nao retornou identificador, token e permissoes de leitura validos.");
    }

    private void requireConfigured() {
        if (!settings.configured()) {
            throw new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE, "NUVEMSHOP_NOT_CONFIGURED",
                    "Configure o aplicativo Nuvemshop, callback, User-Agent e chave de criptografia no ambiente.");
        }
    }

    static String stateRef(byte[] digest) {
        // Diagnostic correlation only; truncated digest cannot recreate the nonce.
        return HexFormat.of().formatHex(digest, 0, 6);
    }

    static byte[] digest(String state) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel");
        }
    }
}
