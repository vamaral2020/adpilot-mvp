package br.com.adpilot.integrations.nuvemshop;

import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NuvemshopSettings {
    private final String clientId, clientSecret, redirectUri, userAgent, encryptionKey;
    private final String apiBase, apiVersion, oauthBase, dashboardUrl;

    public NuvemshopSettings(
            @Value("${adpilot.nuvemshop.client-id:}") String clientId,
            @Value("${adpilot.nuvemshop.client-secret:}") String clientSecret,
            @Value("${adpilot.nuvemshop.redirect-uri:}") String redirectUri,
            @Value("${adpilot.nuvemshop.user-agent:}") String userAgent,
            @Value("${adpilot.nuvemshop.encryption-key:}") String encryptionKey,
            @Value("${adpilot.nuvemshop.api-base:https://api.nuvemshop.com.br}") String apiBase,
            @Value("${adpilot.nuvemshop.api-version:2025-03}") String apiVersion,
            @Value("${adpilot.nuvemshop.oauth-base:https://www.nuvemshop.com.br}") String oauthBase,
            @Value("${adpilot.nuvemshop.dashboard-url:http://localhost:13000/}") String dashboardUrl) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.redirectUri = redirectUri;
        this.userAgent = userAgent;
        this.encryptionKey = encryptionKey;
        this.apiBase = apiBase;
        this.apiVersion = apiVersion;
        this.oauthBase = oauthBase;
        this.dashboardUrl = dashboardUrl;
    }

    public boolean configured() {
        return clientId.matches("[0-9]{1,30}") && !clientSecret.isBlank()
                && !redirectUri.isBlank() && !userAgent.isBlank()
                && apiVersion.matches("[0-9]{4}-[0-9]{2}")
                && validKey();
    }

    boolean validKey() {
        try {
            return Base64.getDecoder().decode(encryptionKey).length == 32;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public String clientId() { return clientId; }
    String clientSecret() { return clientSecret; }
    String redirectUri() { return redirectUri; }
    String userAgent() { return userAgent; }
    String encryptionKey() { return encryptionKey; }
    String apiBase() { return apiBase; }
    String apiVersion() { return apiVersion; }
    String oauthBase() { return oauthBase; }
    String dashboardUrl() { return dashboardUrl; }
}
