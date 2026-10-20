package br.com.adpilot.integrations.nuvemshop;

import java.util.List;
import java.util.Map;

public record NuvemshopPage(List<Map<String, Object>> data, int page,
                            int perPage, Long totalCount, boolean hasNext) { }
