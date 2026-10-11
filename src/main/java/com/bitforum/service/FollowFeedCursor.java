package com.bitforum.service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;

/** 游标只是排序边界；每次查询仍由服务端登录上下文决定用户及可见性。 */
@Component
public class FollowFeedCursor {
    public static final LocalDateTime OLDEST = LocalDateTime.of(1000, 1, 1, 0, 0);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss")
            .withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> FIELDS = Set.of("version", "userId", "contentType", "beforeTime", "beforeId");
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public record Boundary(LocalDateTime time, long id) {}

    public Boundary decode(String cursor, Long userId, String type) {
        if (cursor == null) return null;
        try {
            if (cursor.isEmpty() || cursor.length() > 1024 || !cursor.matches("[A-Za-z0-9_-]+")) throw invalid();
            byte[] raw = Base64.getUrlDecoder().decode(cursor);
            if (!Base64.getUrlEncoder().withoutPadding().encodeToString(raw).equals(cursor)) throw invalid();
            JsonNode node = json.readTree(raw);
            Set<String> fields = new HashSet<>(); node.fieldNames().forEachRemaining(fields::add);
            if (!node.isObject() || !fields.equals(FIELDS) || integer(node.get("version")) != 1
                    || integer(node.get("userId")) != userId) throw invalid();
            JsonNode nodeType = node.get("contentType");
            if (type == null ? !nodeType.isNull() : !nodeType.isTextual() || !type.equals(nodeType.textValue())) throw invalid();
            long id = integer(node.get("beforeId"));
            JsonNode date = node.get("beforeTime");
            if (!date.isTextual() || !date.textValue().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}")) throw invalid();
            LocalDateTime time = LocalDateTime.parse(date.textValue(), TIME);
            if (time.getYear() < 1000 || time.getYear() > 9999) throw invalid();
            return new Boundary(time, id);
        } catch (Exception e) {
            throw invalid();
        }
    }

    private long integer(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() <= 0) throw invalid();
        return node.longValue();
    }

    public String encode(Long userId, String type, LocalDateTime time, Long id) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", 1); value.put("userId", userId); value.put("contentType", type);
        value.put("beforeTime", TIME.format(time == null ? OLDEST : time)); value.put("beforeId", id);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsString(value).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { throw new IllegalStateException("Unable to encode feed cursor", e); }
    }

    private ResponseStatusException invalid() { return new ResponseStatusException(HttpStatus.BAD_REQUEST, "关注动态游标无效，请刷新后重试"); }
}
