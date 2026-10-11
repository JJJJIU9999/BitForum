package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.zaxxer.hikari.HikariDataSource;

@SpringBootTest
class QuestionMigrationTest {
    @Autowired private DataSource dataSource;

    @Test
    void upgradingV21ToV22PreservesExistingContentAndOtherTables() throws Exception {
        HikariDataSource source = dataSource.unwrap(HikariDataSource.class);
        String schema = "bit_forum_m19_migration_" + UUID.randomUUID().toString().replace("-", "");
        com.bitforum.testsupport.IsolatedTestTarget.requireRebuild(source.getJdbcUrl(), schema);
        String url = source.getJdbcUrl().replaceFirst("/[^/?]+(?=\\?|$)", "/" + schema);
        try (Connection admin = source.getConnection(); Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            try {
                Flyway.configure().dataSource(url, source.getUsername(), source.getPassword()).target("21").load().migrate();
                try (Connection fixture = DriverManager.getConnection(url, source.getUsername(), source.getPassword());
                        Statement sql = fixture.createStatement()) {
                    sql.executeUpdate("INSERT INTO article (id,title,content,user_id,category_id,status) VALUES (99001,'历史文章','保持原正文',40001,1,'PUBLISHED')");
                    sql.executeUpdate("INSERT INTO comment (id,content,user_id,article_id,parent_comment_id,status) VALUES (99001,'历史评论',40002,99001,0,'PUBLISHED')");
                    Map<String, Object> oldArticle = row(sql, "SELECT * FROM article WHERE id=99001");
                    Map<String, Object> oldComment = row(sql, "SELECT * FROM comment WHERE id=99001");
                    Map<String, Object> checksums = checksums(sql);
                    Flyway.configure().dataSource(url, source.getUsername(), source.getPassword()).target("22").load().migrate();
                    Map<String, Object> newArticle = row(sql, "SELECT * FROM article WHERE id=99001");
                    oldArticle.forEach((key, value) -> assertEquals(value, newArticle.get(key), key));
                    assertEquals("ARTICLE", newArticle.get("content_type"));
                    assertNull(newArticle.get("accepted_answer_id"));
                    assertEquals(oldComment, row(sql, "SELECT * FROM comment WHERE id=99001"));
                    assertEquals(checksums, checksums(sql));
                    assertEquals("22", row(sql, "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1").get("version"));
                }
            } finally {
                statement.execute("DROP DATABASE " + schema);
            }
        }
    }

    private Map<String, Object> row(Statement sql, String query) throws SQLException {
        try (ResultSet result = sql.executeQuery(query)) {
            assertTrue(result.next());
            Map<String, Object> values = new LinkedHashMap<>();
            for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                values.put(result.getMetaData().getColumnLabel(column), result.getObject(column));
            }
            return values;
        }
    }

    private Map<String, Object> checksums(Statement sql) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (ResultSet result = sql.executeQuery("SHOW TABLES")) {
            while (result.next()) {
                String table = result.getString(1);
                if (!table.equals("article") && !table.equals("flyway_schema_history")) tables.add(table);
            }
        }
        Map<String, Object> checksums = new LinkedHashMap<>();
        for (String table : tables) {
            Object checksum = row(sql, "CHECKSUM TABLE `" + table + "`").get("Checksum");
            assertNotNull(checksum, table);
            checksums.put(table, checksum);
        }
        return checksums;
    }
}
