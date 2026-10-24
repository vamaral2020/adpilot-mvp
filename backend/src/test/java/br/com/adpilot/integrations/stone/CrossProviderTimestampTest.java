package br.com.adpilot.integrations.stone;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CrossProviderTimestampTest {
    @Test void compactAndExtendedOffsetsRepresentSameInstant() {
        Instant expected=Instant.parse("2026-10-20T15:47:32Z");
        var compact=CrossProviderTimestamp.parse("2026-10-20T12:47:32-0300");
        var extended=CrossProviderTimestamp.parse("2026-10-20T12:47:32-03:00");
        assertEquals(expected,compact.instant());
        assertEquals(expected,extended.instant());
        assertEquals("ISO_OFFSET_COMPACT_HHMM",compact.formatClass());
        assertEquals("ISO_OFFSET_EXTENDED",extended.formatClass());
        assertEquals("PARSED",compact.fieldStatus());
    }

    @Test void supportsActualNuvemshopDocumentationUtcFormatAndFractionalSeconds() {
        assertEquals(Instant.parse("2022-11-15T19:47:32Z"),
                CrossProviderTimestamp.parse("2022-11-15T19:47:32+0000").instant());
        assertEquals(Instant.parse("2022-11-15T19:47:32.120Z"),
                CrossProviderTimestamp.parse("2022-11-15T16:47:32.120-0300").instant());
        assertEquals("UTC_Z",CrossProviderTimestamp.parse(
                "2022-11-15T19:47:32Z").formatClass());
    }

    @Test void rejectsDatesWithoutAnExplicitOffsetAndNeverGuessesBrazilianTimezone() {
        var unknown=CrossProviderTimestamp.parse("2026-10-20T12:47:32");
        assertNull(unknown.instant());
        assertEquals("LOCAL_WITHOUT_OFFSET",unknown.formatClass());
        assertEquals("UNPARSEABLE",unknown.fieldStatus());
        assertNull(CrossProviderTimestamp.parse("2026-10-20").instant());
    }

    @Test void safeFixedClassificationsDoNotLeakMalformedValues() {
        for(Object sample:new Object[]{"SENSITIVE-2026-PRIVATE",Map.of("card","SECRET"),12L}) {
            var value=CrossProviderTimestamp.parse(sample);
            assertNull(value.instant());
            assertFalse(value.formatClass().contains("SENSITIVE"));
            assertFalse(value.formatClass().contains("SECRET"));
        }
        assertEquals("ABSENT_OR_NULL",CrossProviderTimestamp.parse(null).fieldStatus());
    }

    @Test void invalidOffsetDoesNotProduceAnInstant() {
        var value=CrossProviderTimestamp.parse("2026-10-20T12:00:00+2500");
        assertNull(value.instant());
        assertEquals("UNRECOGNIZED_STRING_FORMAT",value.formatClass());
    }
}
