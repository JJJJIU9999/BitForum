package com.bitforum.config;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

/** Business DATETIME values are UTC; JSON makes that convention explicit. */
@Configuration
public class UtcTimeConfig {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer utcDateTimes() {
        return builder -> {
            builder.serializerByType(LocalDateTime.class, new JsonSerializer<LocalDateTime>() {
                @Override
                public void serialize(LocalDateTime value, JsonGenerator out, SerializerProvider provider)
                        throws IOException {
                    out.writeString(value.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
                }
            });
            builder.deserializerByType(LocalDateTime.class, new JsonDeserializer<LocalDateTime>() {
                @Override
                public LocalDateTime deserialize(JsonParser in, DeserializationContext context) throws IOException {
                    String value = in.getText();
                    try {
                        return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
                    } catch (DateTimeParseException offsetError) {
                        // Compatibility with cached/older JSON: unqualified values already mean UTC.
                        try {
                            return LocalDateTime.parse(value);
                        } catch (DateTimeParseException localError) {
                            return (LocalDateTime) context.handleWeirdStringValue(LocalDateTime.class, value,
                                    "Expected an ISO timestamp (legacy unqualified timestamps mean UTC)");
                        }
                    }
                }
            });
        };
    }
}
