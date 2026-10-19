package br.com.adpilot.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SystemOverviewResponseTest {
    @Test
    void createsStatusWithoutSecrets() {
        var response = new SystemOverviewResponse(
                "AdPilot", "0.1.0", "CONNECTED", Map.of("META_ADS", "NOT_CONFIGURED"));
        assertEquals("AdPilot", response.app());
        assertEquals("NOT_CONFIGURED", response.integrations().get("META_ADS"));
    }
}
