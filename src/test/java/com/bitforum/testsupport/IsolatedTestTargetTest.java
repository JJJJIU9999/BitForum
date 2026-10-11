package com.bitforum.testsupport;

import static org.junit.jupiter.api.Assertions.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class IsolatedTestTargetTest {
    static final String SAFE = "jdbc:mysql://127.0.0.1:23306/bitforum_finalization_test?useSSL=false";
    static Stream<String> unsafe() {
        return Stream.of(null, "", "jdbc:mysql://localhost:3306/bit_forum?useSSL=false",
            "jdbc:mysql://127.0.0.1:23306/bit_forum?useSSL=false",
            "jdbc:mysql://127.0.0.1:3306/bitforum_finalization_test?useSSL=false",
            "jdbc:mysql://192.0.2.2:23306/bitforum_finalization_test?useSSL=false",
            "jdbc:mysql://127.0.0.1:23306/bitforum_finalization_test?socketFactory=unsafe",
            "jdbc:mysql://127.0.0.1:23306/bitforum_finalization_test?useSSL=false#unsafe",
            "jdbc:mysql://user@127.0.0.1:23306/bitforum_finalization_test?useSSL=false",
            "jdbc:mysql://127.0.0.1:23306/bitforum_finalization_test");
    }
    @ParameterizedTest @MethodSource("unsafe") void refusesBeforeAnyConnection(String url) {
        assertThrows(IllegalStateException.class, () -> IsolatedTestTarget.requireDatabase(url, "true"));
    }
    @Test void acceptsOnlyExplicitIsolatedTarget() {
        assertDoesNotThrow(() -> IsolatedTestTarget.requireDatabase(SAFE,"true"));
        assertThrows(IllegalStateException.class, () -> IsolatedTestTarget.requireDatabase(SAFE,null));
    }
    @Test void initializerRunsWithoutRefreshingContextOrConnecting() {
        var context = new org.springframework.context.support.GenericApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
            "unsafe", java.util.Map.of("spring.datasource.url", "jdbc:mysql://localhost:3306/bit_forum?useSSL=false")));
        assertThrows(IllegalStateException.class, () -> new IsolatedTestInitializer().initialize(context));
        assertFalse(context.isActive());
        context.close();
    }

    @Test void destructiveTargetsHaveSeparateAllowlist() {
        String cm22 = SAFE.replace("bitforum_finalization_test", "bitforum_cm22_test");
        assertDoesNotThrow(() -> IsolatedTestTarget.requireRebuild(cm22,"bitforum_cm22_fresh","true"));
        for (String target : java.util.List.of("bit_forum","preview","bitforum_cm24_fresh","bitforum_cm22_test","bitforum_cm22_fresh; DROP DATABASE other")) {
            assertThrows(IllegalStateException.class, () -> IsolatedTestTarget.requireRebuild(cm22,target,"true"));
        }
        assertThrows(IllegalStateException.class, () -> IsolatedTestTarget.requireRebuild(cm22,"bitforum_cm22_fresh",null));
    }
}
