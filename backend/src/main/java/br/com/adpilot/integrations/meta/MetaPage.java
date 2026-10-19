package br.com.adpilot.integrations.meta;

import java.util.List;
import java.util.Map;

public record MetaPage(List<Map<String, Object>> data, String after) { }
