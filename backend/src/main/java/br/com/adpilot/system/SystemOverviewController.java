package br.com.adpilot.system;

import br.com.adpilot.integrations.meta.MetaSettings;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemOverviewController {
    private final JdbcTemplate jdbc;
    private final MetaSettings meta;

    public SystemOverviewController(JdbcTemplate jdbc, MetaSettings meta) {
        this.jdbc = jdbc;
        this.meta = meta;
    }

    @GetMapping("/overview")
    public SystemOverviewResponse overview() {
        Integer result = jdbc.queryForObject("SELECT 1", Integer.class);
        Map<String, String> integrations = new LinkedHashMap<>();
        integrations.put("META_ADS", meta.configured() ? "CONFIGURED_UNVERIFIED" : "NOT_CONFIGURED");
        integrations.put("NUVEMSHOP", "NOT_CONFIGURED");
        jdbc.query("SELECT provider, status FROM integration_connection WHERE workspace_id = ?", rs -> {
            // Preserve explicit connection information, except do not claim verified Meta connectivity.
            if (!"META_ADS".equals(rs.getString("provider"))) {
                integrations.put(rs.getString("provider"), rs.getString("status"));
            }
        }, java.util.UUID.fromString("00000000-0000-4000-8000-000000000001"));
        return new SystemOverviewResponse("AdPilot", "0.2.0",
                result != null && result == 1 ? "CONNECTED" : "ERROR", integrations);
    }
}
