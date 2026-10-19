package br.com.adpilot.system;

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

    public SystemOverviewController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/overview")
    public SystemOverviewResponse overview() {
        // O endpoint publica apenas estados; nunca tokens ou IDs sensíveis.
        Integer result = jdbc.queryForObject("SELECT 1", Integer.class);
        Map<String, String> integrations = new LinkedHashMap<>();
        integrations.put("META_ADS", "NOT_CONFIGURED");
        integrations.put("NUVEMSHOP", "NOT_CONFIGURED");
        jdbc.query("SELECT provider, status FROM integration_connection " +
                   "WHERE workspace_id = ?", rs -> {
            integrations.put(rs.getString("provider"), rs.getString("status"));
        }, java.util.UUID.fromString("00000000-0000-4000-8000-000000000001"));
        return new SystemOverviewResponse("AdPilot", "0.1.0", result != null && result == 1 ? "CONNECTED" : "ERROR", integrations);
    }
}
