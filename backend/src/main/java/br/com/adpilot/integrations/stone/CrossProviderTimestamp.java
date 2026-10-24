package br.com.adpilot.integrations.stone;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * Pure parser for explicit-offset order creation instants.
 * No timezone inference. The classification is fixed-vocabulary, never raw input.
 */
final class CrossProviderTimestamp {
    private CrossProviderTimestamp() {}

    record Parsed(Instant instant,String formatClass) {
        String fieldStatus() {
            if("ABSENT_OR_NULL".equals(formatClass)) return "ABSENT_OR_NULL";
            return instant==null?"UNPARSEABLE":"PARSED";
        }
    }

    static Parsed parse(Object raw) {
        if(raw==null) return new Parsed(null,"ABSENT_OR_NULL");
        if(!(raw instanceof String value)) return new Parsed(null,"NON_STRING");
        if(value.isEmpty() || value.length()>80) return new Parsed(null,"INVALID_STRING");
        try {
            if(value.endsWith("Z")) {
                return new Parsed(Instant.parse(value),"UTC_Z");
            }
            // Nuvemshop documented example: 2022-11-15T19:47:32+0000.
            // Only normalize a strictly numeric explicit UTC offset, never infer one.
            if(value.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?[+-]\\d{4}")) {
                String normalized=value.substring(0,value.length()-2)+":"
                        +value.substring(value.length()-2);
                return new Parsed(OffsetDateTime.parse(normalized).toInstant(),
                        "ISO_OFFSET_COMPACT_HHMM");
            }
            return new Parsed(OffsetDateTime.parse(value).toInstant(),
                    "ISO_OFFSET_EXTENDED");
        } catch(DateTimeParseException ex) {
            try {
                LocalDateTime.parse(value);
                return new Parsed(null,"LOCAL_WITHOUT_OFFSET");
            } catch(DateTimeParseException ignored) {
                return new Parsed(null,"UNRECOGNIZED_STRING_FORMAT");
            }
        }
    }
}
