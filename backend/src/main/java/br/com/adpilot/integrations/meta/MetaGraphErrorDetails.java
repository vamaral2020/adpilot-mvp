package br.com.adpilot.integrations.meta;

import java.util.regex.Pattern;

/**
 * Extrai somente codigos numericos do corpo de erro da Graph API.
 * Nao devolve messages, URLs, tokens nem outros campos arbitrarios.
 */
record MetaGraphErrorDetails(Integer code, Integer subcode) {
    private static final Pattern CODE = Pattern.compile("\"code\"\\s*:\\s*([0-9]{1,9})(?![0-9])");
    private static final Pattern SUBCODE = Pattern.compile("\"error_subcode\"\\s*:\\s*([0-9]{1,9})(?![0-9])");

    static MetaGraphErrorDetails fromJson(String body) {
        if (body == null || body.isBlank()) return new MetaGraphErrorDetails(null, null);
        // Limitamos processamento de respostas upstream grandes ou malformadas.
        String source = body.substring(0, Math.min(body.length(), 8192));
        return new MetaGraphErrorDetails(find(source, CODE), find(source, SUBCODE));
    }

    private static Integer find(String source, Pattern pattern) {
        var match = pattern.matcher(source);
        if (!match.find()) return null;
        try {
            return Integer.valueOf(match.group(1));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
