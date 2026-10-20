package br.com.adpilot.integrations.nuvemshop;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class NuvemshopAuthService {
    private static final SecureRandom RANDOM = new SecureRandom();
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
        this.client = builder.build();
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
        repository.createState(digest(state));
        String url = settings.oauthBase() + "/apps/" + settings.clientId() + "/authorize?state="
                + URLEncoder.encode(state, StandardCharsets.UTF_8);
        return new Authorization(url, 300);
    }

    public void complete(String code, String state) {
        requireConfigured();
        if (code == null || code.isBlank() || code.length() > 4096
                || state == null || !state.matches("[A-Za-z0-9_-]{43}")) {
            throw new NuvemshopException(HttpStatus.BAD_REQUEST, "NUVEMSHOP_INVALID_CALLBACK",
                    "Parametros de autorizacao invalidos.");
        }
        repository.consumeState(digest(state));
        Map<String, Object> payload = Map.of(
                "client_id", settings.clientId(),
                "client_secret", settings.clientSecret(),
                "grant_type", "authorization_code",
                "code", code);
        Map<String, Object> response;
        try {
            response = client.post()
                    .uri(URI.create(settings.oauthBase() + "/apps/authorize/token"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (RestClientResponseException e) {
            throw new NuvemshopException(HttpStatus.BAD_GATEWAY, "NUVEMSHOP_OAUTH_REJECTED",
                    "A Nuvemshop rejeitou a troca do codigo de autorizacao.",
                    e.getStatusCode().value());
        } catch (RestClientException e) {
            throw new NuvemshopException(HttpStatus.SERVICE_UNAVAILABLE, "NUVEMSHOP_OAUTH_UNAVAILABLE",
                    "Nao foi possivel concluir a autorizacao com a Nuvemshop.");
        }
        if (response == null) throw invalidTokenResponse();
        Object userId = response.get("user_id") == null ? response.get("store_id") : response.get("user_id");
        String storeId = userId == null ? "" : userId.toString();
        String token = response.get("access_token") instanceof String t ? t : "";
        String scope = response.get("scope") instanceof String s ? s : "";
        if (!storeId.matches("[0-9]{1,40}") || token.isBlank()
                || !scopeContains(scope, "read_products") || !scopeContains(scope, "read_orders")) {
            throw invalidTokenResponse();
        }
        // Store only encrypted credentials; OAuth scopes validated for read-only use.
        repository.saveInstallation(storeId, scope, crypto.encrypt(token));
    }

    private static boolean scopeContains(String scope, String value) {
        return Arrays.stream(scope.split("[,\\s]+")).anyMatch(value::equals);
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

    static byte[] digest(String state) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(state.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel");
        }
    }
}
