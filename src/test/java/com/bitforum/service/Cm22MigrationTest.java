package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 用单独合成 V22 库验证升级；禁止依赖默认应用 datasource。 */
@EnabledIfEnvironmentVariable(named = "CM22_ISOLATED_TESTS", matches = "true")
class Cm22MigrationTest {
    @Test
    void freshAndV22UpgradePreserveContentAndEnforceUniqueKey() throws Exception {
        String base = System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(base); assertTrue(base.startsWith("jdbc:mysql://127.0.0.1:23306/bitforum_cm22_test?"));
        com.bitforum.testsupport.IsolatedTestTarget.requireRebuild(base, "bitforum_cm22_upgrade");
        com.bitforum.testsupport.IsolatedTestTarget.requireRebuild(base, "bitforum_cm22_fresh");
        String user = System.getenv("SPRING_DATASOURCE_USERNAME"), password = System.getenv("SPRING_DATASOURCE_PASSWORD");
        String url = base.replace("/bitforum_cm22_test?", "/bitforum_cm22_upgrade?");
        String freshUrl = base.replace("/bitforum_cm22_test?", "/bitforum_cm22_fresh?");
        try (Connection connection = DriverManager.getConnection(base, user, password); Statement sql = connection.createStatement()) {
            sql.execute("DROP DATABASE IF EXISTS bitforum_cm22_upgrade");
            sql.execute("CREATE DATABASE bitforum_cm22_upgrade CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            sql.execute("DROP DATABASE IF EXISTS bitforum_cm22_fresh");
            sql.execute("CREATE DATABASE bitforum_cm22_fresh CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
        Flyway v22 = Flyway.configure().dataSource(url, user, password).target("22").load();
        assertEquals(22, v22.migrate().migrationsExecuted);
        try (Connection c = DriverManager.getConnection(url, user, password); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO article(id,title,content,user_id,category_id,status,content_type,accepted_answer_id) VALUES(22001,'合成旧问题','原正文',22,1,'PUBLISHED','QUESTION',22001)");
            s.executeUpdate("INSERT INTO comment(id,content,user_id,article_id,parent_comment_id,status) VALUES(22001,'合成旧回答',23,22001,0,'PUBLISHED')");
            Map<String,Object> oldArticle = row(s, "SELECT * FROM article WHERE id=22001");
            Map<String,Object> oldComment = row(s, "SELECT * FROM comment WHERE id=22001");
            Flyway current = Flyway.configure().dataSource(url, user, password).target("23").load();
            assertEquals(1, current.migrate().migrationsExecuted); current.validate();
            assertEquals(oldArticle, row(s, "SELECT * FROM article WHERE id=22001"));
            assertEquals(oldComment, row(s, "SELECT * FROM comment WHERE id=22001"));
            assertEquals("23", row(s, "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1").get("version"));
            s.executeUpdate("INSERT INTO answer_like(comment_id,user_id) VALUES(22001,23)");
            SQLException duplicate = assertThrows(SQLException.class, () -> s.executeUpdate("INSERT INTO answer_like(comment_id,user_id) VALUES(22001,23)"));
            assertEquals(1062, duplicate.getErrorCode());
            assertTrue(hasIndex(s, "answer_like", "uk_answer_like_comment_user"));
            assertTrue(hasIndex(s, "comment", "idx_comment_article_parent_status_time"));
            assertTrue(hasIndex(s, "notification", "uk_notification_source_message_id"));
        }
        Flyway fresh = Flyway.configure().dataSource(freshUrl, user, password).target("23").load();
        assertEquals(23, fresh.migrate().migrationsExecuted); fresh.validate();
        try (Connection c = DriverManager.getConnection(freshUrl,user,password); Statement s = c.createStatement()) {
            assertEquals(23L, ((Number)row(s, "SELECT COUNT(*) AS versions FROM flyway_schema_history WHERE success=1").get("versions")).longValue());
            assertEquals("23", row(s, "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1").get("version"));
        }
    }
    private boolean hasIndex(Statement s, String table, String key) throws SQLException {
        try(ResultSet r=s.executeQuery("SHOW INDEX FROM " + table + " WHERE Key_name='" + key + "'")) {return r.next();}
    }
    private Map<String,Object> row(Statement s,String sql) throws SQLException {
        try(ResultSet r=s.executeQuery(sql)) {
            assertTrue(r.next()); Map<String,Object> result=new LinkedHashMap<>();
            for(int i=1;i<=r.getMetaData().getColumnCount();i++) result.put(r.getMetaData().getColumnLabel(i),r.getObject(i));
            return result;
        }
    }
}
