package com.bitforum.testsupport;

import java.net.URI;
import java.util.Set;

/** Checks only strings; never opens a connection. Used before context refresh and destructive JDBC. */
public final class IsolatedTestTarget {
    private static final Set<String> DATABASES = Set.of("bitforum_finalization_test", "bitforum_cm22_test",
            "bitforum_cm23_test", "bitforum_cm24_test");
    private static final Set<String> OPTIONS = Set.of("useSSL=false", "allowPublicKeyRetrieval=true",
            "serverTimezone=Asia/Shanghai", "characterEncoding=UTF-8");

    private IsolatedTestTarget() {}

    public static void requireDatabase(String url) {
        requireDatabase(url, System.getenv("BITFORUM_ISOLATED_TESTS"));
    }

    public static void requireDatabase(String url, String optedIn) {
        if (!"true".equals(optedIn)) throw new IllegalStateException("Explicit isolated test opt-in required");
        try {
            if (url == null || !url.startsWith("jdbc:mysql://")) throw new IllegalArgumentException();
            URI uri = URI.create(url.substring(5));
            if (!"127.0.0.1".equals(uri.getHost()) || uri.getPort() != 23306 || uri.getUserInfo() != null
                    || uri.getFragment() != null || !DATABASES.contains(uri.getPath().substring(1))) {
                throw new IllegalArgumentException();
            }
            if (uri.getRawQuery() == null) throw new IllegalArgumentException();
            for (String option : uri.getRawQuery().split("&", -1)) {
                if (!OPTIONS.contains(option)) throw new IllegalArgumentException();
            }
        } catch (RuntimeException rejected) {
            throw new IllegalStateException("Dedicated loopback MySQL:23306 test database required");
        }
    }

    public static void requireRebuild(String baseUrl, String target) {
        requireRebuild(baseUrl, target, System.getenv("BITFORUM_ISOLATED_TESTS"));
    }

    public static void requireRebuild(String baseUrl, String target, String optedIn) {
        requireDatabase(baseUrl, optedIn);
        boolean cm22 = baseUrl.startsWith("jdbc:mysql://127.0.0.1:23306/bitforum_cm22_test?")
                && ("bitforum_cm22_upgrade".equals(target) || "bitforum_cm22_fresh".equals(target));
        boolean cm24 = baseUrl.startsWith("jdbc:mysql://127.0.0.1:23306/bitforum_cm24_test?")
                && ("bitforum_cm24_upgrade".equals(target) || "bitforum_cm24_fresh".equals(target));
        boolean question = baseUrl.startsWith("jdbc:mysql://127.0.0.1:23306/bitforum_finalization_test?")
                && target != null && target.matches("bit_forum_m19_migration_[a-f0-9]{32}");
        if (!(cm22 || cm24 || question)) {
            throw new IllegalStateException("Destructive test schema is not permitted");
        }
    }
}
