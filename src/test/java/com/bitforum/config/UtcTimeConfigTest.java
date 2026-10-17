package com.bitforum.config;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDateTime;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;

class UtcTimeConfigTest {
    private ObjectMapper mapper() {
        var builder = new Jackson2ObjectMapperBuilder();
        new UtcTimeConfig().utcDateTimes().customize(builder);
        return builder.build();
    }
    @Test void writesExplicitUtcRegardlessOfJvmTimezone() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
            assertEquals("\"2026-01-31T16:00:00Z\"", mapper().writeValueAsString(LocalDateTime.of(2026, 1, 31, 16, 0)));
        } finally { TimeZone.setDefault(original); }
    }
    @Test void readsOffsetsIntoUtcAcrossMonthBoundary() throws Exception {
        assertEquals(LocalDateTime.of(2026, 1, 31, 16, 0), mapper().readValue("\"2026-02-01T00:00:00+08:00\"", LocalDateTime.class));
    }
    @Test void readsHistoricalUtcCacheWithoutChangingTheStoredValue() throws Exception {
        assertEquals(LocalDateTime.of(2026, 10, 13, 12, 51, 17), mapper().readValue("\"2026-10-13T12:51:17\"", LocalDateTime.class));
    }
    @Test void rejectsImpossibleDate() {
        assertThrows(Exception.class, () -> mapper().readValue("\"2026-02-30T12:00:00Z\"", LocalDateTime.class));
    }
}
