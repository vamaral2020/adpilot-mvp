package br.com.adpilot.integrations.meta;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MetaSettings {
    private final String version;
    private final String accountId;
    private final String accessToken;
    private final String baseUrl;

    public MetaSettings(
            @Value("${adpilot.meta.version:v26.0}") String version,
            @Value("${adpilot.meta.account-id:}") String accountId,
            @Value("${adpilot.meta.access-token:}") String accessToken,
            @Value("${adpilot.meta.base-url:https://graph.facebook.com}") String baseUrl) {
        this.version = version;
        this.accountId = accountId;
        this.accessToken = accessToken;
        this.baseUrl = baseUrl;
    }

    public boolean configured() {
        return version.matches("v[0-9]+\\.[0-9]+")
                && accountId.matches("[0-9]{4,30}")
                && accessToken != null && !accessToken.isBlank();
    }

    public String version() { return version; }
    public String accountId() { return accountId; }
    String accessToken() { return accessToken; }
    String baseUrl() { return baseUrl; }
}
