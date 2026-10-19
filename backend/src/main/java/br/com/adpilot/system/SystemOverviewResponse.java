package br.com.adpilot.system;

import java.util.Map;

public record SystemOverviewResponse(
    String app,
    String release,
    String database,
    Map<String, String> integrations
) {
}
