package br.com.adpilot.integrations.meta;

import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meta")
public class MetaAdsController {
    private final MetaSettings settings;
    private final MetaAdsClient client;

    public MetaAdsController(MetaSettings settings, MetaAdsClient client) {
        this.settings = settings;
        this.client = client;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("configured", settings.configured(), "version", settings.version(),
                "status", settings.configured() ? "CONFIGURED_UNVERIFIED" : "NOT_CONFIGURED");
    }

    @GetMapping("/account")
    public Map<String, Object> account() {
        return client.account();
    }

    @GetMapping("/campaigns")
    public MetaPage campaigns(@RequestParam(defaultValue = "25") int limit,
                              @RequestParam(required = false) String after) {
        return client.campaigns(limit, after);
    }

    @GetMapping("/insights")
    public MetaPage insights(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate since,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate until,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(required = false) String after) {
        return client.insights(since, until, limit, after);
    }
}
