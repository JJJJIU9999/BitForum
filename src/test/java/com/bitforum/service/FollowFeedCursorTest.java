package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;

class FollowFeedCursorTest {
    private final FollowFeedCursor codec = new FollowFeedCursor();
    private String encode(String json) { return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8)); }
    private String valid() { return "{\"version\":1,\"userId\":7,\"contentType\":null,\"beforeTime\":\"2026-10-10T01:02:03\",\"beforeId\":8}"; }
    @Test void roundTripAndNullDateUseSameBoundary() {
        assertNull(codec.decode(null, 7L, null));
        assertEquals(new FollowFeedCursor.Boundary(LocalDateTime.of(2026,10,10,1,2,3),8),codec.decode(encode(valid()),7L,null));
        assertEquals(FollowFeedCursor.OLDEST,codec.decode(codec.encode(7L,"QUESTION",null,8L),7L,"QUESTION").time());
        assertEquals(Long.MAX_VALUE,codec.decode(codec.encode(7L,null,LocalDateTime.of(9999,12,31,23,59,59),Long.MAX_VALUE),7L,null).id());
    }
    @ParameterizedTest @ValueSource(strings={"", "!bad", "YQ==", "YWJ", "e30", "bnVsbA"})
    void invalidEncodingOrShape(String cursor) { bad(cursor); }
    @ParameterizedTest @ValueSource(strings={"2026-02-30T01:02:03","0999-12-31T23:59:59","2026-10-10T01:02:03.0","2026-10-10T01:02:03Z","2026-10-10T01:02:03+08:00","2026-1-10T01:02:03","2026-10-10T24:00:00"})
    void strictTime(String date) { bad(encode(valid().replace("2026-10-10T01:02:03",date))); }
    @ParameterizedTest @ValueSource(strings={"0","-1","8.0","\"8\"","9223372036854775808","null","true"})
    void strictIds(String id) { bad(encode(valid().replace("\"beforeId\":8", "\"beforeId\":"+id))); bad(encode(valid().replace("\"userId\":7", "\"userId\":"+id))); }
    @Test void rejectsDuplicateUnknownMissingVersionUserTypeAndTrailingJson() {
        for(String value : new String[]{valid().replace("\"version\":1","\"version\":2"),valid().replace("\"version\":1","\"version\":1.0"),
                valid().replace("\"userId\":7","\"userId\":9"),valid().replace("\"contentType\":null","\"contentType\":\"ARTICLE\""),
                valid().replace("\"contentType\":null,",""),valid().replace("\"version\":1","\"extra\":0,\"version\":1"),
                valid().replace("\"version\":1","\"version\":1,\"version\":1"),valid()+" {}"}) bad(encode(value));
        bad("A".repeat(1025));
        assertThrows(ResponseStatusException.class,()->codec.decode(encode(valid()),7L,"QUESTION"));
    }
    private void bad(String token) { assertEquals(400,assertThrows(ResponseStatusException.class,()->codec.decode(token,7L,null)).getStatusCode().value()); }
}
