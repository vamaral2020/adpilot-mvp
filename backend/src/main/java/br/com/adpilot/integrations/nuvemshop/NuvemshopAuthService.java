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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final JsonMapper jsonMapper;

    public record Authorization(String authorizationUrl, int expiresInSeconds) { }
    public record Status(String status, boolean configured, String storeId,
                         String scopes, java.time.Instant connectedAt) { }

    public NuvemshopAuthService(NuvemshopSettings settings, NuvemshopRepository repository,
                                NuvemshopCrypto crypto, RestClient.Builder builder, JsonMapper jsonMapper) {
        this.settings = settings;
        this.repository = repository;
        this.crypto = crypto;
        this.jsonMapper = jsonMapper;
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
        // Read as text: Nuvemshop sometimes labels valid OAuth JSON as text/html.
        // The Spring-managed Jackson 3 mapper validates the body independently.
        ResponseEntity<String> upstream;
        try {
            upstream = client.post()
                    .uri(URI.create(settings.tokenUrl()))
                    .accept(MediaType.APPLICATION_JSON)
                    .header(org.springframework.http.HttpHeaders.USER_AGENT, settings.userAgent())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toEntity(String.class);
        } catch (RestClientResponseException e) {
            LOG.warn("NUVEMSHOP_OAUTH_TOKEN_EXCHANGE_FAILED state_ref={} category=http_rejected upstream_status={}",
                    stateRef(digest), e.getStatusCode().value());
            throw new NuvemshopException(HttpStatus.BAD_GATEWAY, "NUVEMSHOP_OAUTH_REJECTED",
                    "A Nuvemshop rejeitou a troca do codigo de autorizacao.",
                    e.getStatusCode().value());
        } catch (RestClientException e) {
            // Exception messages, URLs and upstream bodies must never reach logs.
            Throwable root = e;
            for (int depth = 0; depth < 10 && root.getCause() != null
                    && root.getCause() != root; depth++) root = root.getCause();
            LOG.warn("NUVEMSHOP_OAUTH_TOKEN_EXCHANGE_FAILED state_ref={} category=client_exception exception={} root={}",
                    stateRef(digest), e.getClass().getSimpleName(), root.getClass().getSimpleName());
            throw new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE, "NUVEMSHOP_OAUTH_UNAVAILABLE",
                    "Nao foi possivel concluir a autorizacao com a Nuvemshop.",
                    e.getClass().getSimpleName(), root.getClass().getSimpleName());
        }

        String body = upstream.getBody();
        if (body == null || body.isBlank() || body.length() > 65_536) {
            LOG.warn("NUVEMSHOP_OAUTH_TOKEN_EXCHANGE_FAILED state_ref={} category=empty_or_oversized_response",
                    stateRef(digest));
            throw invalidTokenResponse();
        }
        JsonNode response;
        try {
            response = jsonMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(body);
        } catch (JacksonException e) {
            boolean challenge = "challenge".equalsIgnoreCase(
                    upstream.getHeaders().getFirst("cf-mitigated"));
            LOG.warn("NUVEMSHOP_OAUTH_TOKEN_EXCHANGE_FAILED state_ref={} category={} upstream_status={}",
                    stateRef(digest), challenge ? "cloudflare_challenge" : "invalid_json",
                    upstream.getStatusCode().value());
            throw new NuvemshopException(HttpStatus.BAD_GATEWAY,
                    challenge ? "NUVEMSHOP_OAUTH_CHALLENGE" : "NUVEMSHOP_INVALID_OAUTH_RESPONSE",
                    "A Nuvemshop nao retornou JSON OAuth valido.",
                    upstream.getStatusCode().value());
        }
        if (response == null || !response.isObject()) {
            LOG.warn("NUVEMSHOP_OAUTH_TOKEN_EXCHANGE_FAILED state_ref={} category=non_object_json",
                    stateRef(digest));
            throw invalidTokenResponse();
        }
        if (response.has("error")) {
            // OAuth error envelopes may arrive with HTTP 200.
            LOG.warn("NUVEMSHOP_OAUTH_TOKEN_EXCHANGE_FAILED state_ref={} category=oauth_error upstream_status={}",
                    stateRef(digest), upstream.getStatusCode().value());
            throw new NuvemshopException(HttpStatus.BAD_GATEWAY, "NUVEMSHOP_OAUTH_REJECTED",
                    "A Nuvemshop rejeitou a troca do codigo de autorizacao.",
                    upstream.getStatusCode().value());
        }
        LOG.info("NUVEMSHOP_OAUTH_TOKEN_RESPONSE_RECEIVED state_ref={}", stateRef(digest));
        JsonNode id = response.get("user_id");
        if (id == null || id.isNull()) id = response.get("store_id");
        String storeId = id == null || id.isNull() ? "" : id.asText();
        JsonNode tokenNode = response.get("access_token");
        String token = tokenNode != null && tokenNode.isTextual() ? tokenNode.asText() : "";
        JsonNode scopeNode = response.get("scope");
        String scope = scopeNode != null && scopeNode.isTextual() ? scopeNode.asText() : "";
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
