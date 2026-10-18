package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.*;
import java.sql.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="CM24_ISOLATED_TESTS",matches="true")
class Cm24MigrationTest {
    @Test void freshAndUpgradePreserveRowsAndEnforceUniqueCascade() throws Exception {
        String base=System.getenv("SPRING_DATASOURCE_URL");
        assertNotNull(base); assertTrue(base.startsWith("jdbc:mysql://127.0.0.1:23306/bitforum_cm24_test?"));
        com.bitforum.testsupport.IsolatedTestTarget.requireRebuild(base, "bitforum_cm24_upgrade");
        com.bitforum.testsupport.IsolatedTestTarget.requireRebuild(base, "bitforum_cm24_fresh");
        String user=System.getenv("SPRING_DATASOURCE_USERNAME"), password=System.getenv("SPRING_DATASOURCE_PASSWORD");
        for(String name:List.of("bitforum_cm24_upgrade","bitforum_cm24_fresh")) {
            try(Connection c=DriverManager.getConnection(base,user,password); Statement s=c.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS "+name); s.execute("CREATE DATABASE "+name+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
            }
        }
        String upgrade=base.replace("/bitforum_cm24_test?","/bitforum_cm24_upgrade?"),fresh=base.replace("/bitforum_cm24_test?","/bitforum_cm24_fresh?");
        assertEquals(23,Flyway.configure().dataSource(upgrade,user,password).target("23").load().migrate().migrationsExecuted);
        try(Connection c=DriverManager.getConnection(upgrade,user,password);Statement s=c.createStatement()) {
            s.executeUpdate("INSERT INTO article(id,title,content,user_id,category_id,status,content_type) VALUES(24001,'合成旧内容','保持',24,1,'PUBLISHED','QUESTION')");
            s.executeUpdate("INSERT INTO comment(id,article_id,user_id,content,parent_comment_id,status) VALUES(24001,24001,25,'旧回答',0,'PUBLISHED')");
            s.executeUpdate("INSERT INTO answer_like(comment_id,user_id) VALUES(24001,24)");
            s.executeUpdate("UPDATE article SET accepted_answer_id=24001 WHERE id=24001");
            s.executeUpdate("INSERT INTO user_follow(follower_id,following_id) VALUES(24,25)");
            Map<String,Object> old=row(s,"SELECT * FROM article WHERE id=24001"),relation=row(s,"SELECT * FROM user_follow WHERE follower_id=24");
            Map<String,Object> historicalChecksums=checksums(s);
            var flyway=Flyway.configure().dataSource(upgrade,user,password).target("24").load();assertEquals(1,flyway.migrate().migrationsExecuted);flyway.validate();
            assertEquals(historicalChecksums,checksums(s));
            assertEquals(old,row(s,"SELECT * FROM article WHERE id=24001"));assertEquals(relation,row(s,"SELECT * FROM user_follow WHERE follower_id=24"));
            s.executeUpdate("INSERT INTO category(id,name) VALUES(24001,'合成空话题')");
            s.executeUpdate("INSERT INTO category_follow(user_id,category_id) VALUES(24,24001)");
            assertEquals(1062,assertThrows(SQLException.class,()->s.executeUpdate("INSERT INTO category_follow(user_id,category_id) VALUES(24,24001)")).getErrorCode());
            assertEquals(1452,assertThrows(SQLException.class,()->s.executeUpdate("INSERT INTO category_follow(user_id,category_id) VALUES(24,99999)")).getErrorCode());
            s.executeUpdate("DELETE FROM category WHERE id=24001");assertEquals(0L,((Number)row(s,"SELECT COUNT(*) n FROM category_follow WHERE category_id=24001").get("n")).longValue());
            assertEquals("24",row(s,"SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1").get("version"));
        }
        var f=Flyway.configure().dataSource(fresh,user,password).target("24").load();assertEquals(24,f.migrate().migrationsExecuted);f.validate();
    }
    private Map<String,Object> checksums(Statement s) throws SQLException {
        List<String> names=new ArrayList<>();
        try(var r=s.executeQuery("SHOW TABLES")){while(r.next()){String name=r.getString(1);if(!name.equals("flyway_schema_history")&&!name.equals("category_follow"))names.add(name);}}
        Map<String,Object> values=new TreeMap<>();
        for(String name:names){Object checksum=row(s,"CHECKSUM TABLE `"+name+"`").get("Checksum");assertNotNull(checksum,name);values.put(name,checksum);}
        return values;
    }
    private Map<String,Object> row(Statement s,String sql)throws SQLException {
        try(ResultSet r=s.executeQuery(sql)){assertTrue(r.next());Map<String,Object> map=new LinkedHashMap<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)map.put(r.getMetaData().getColumnLabel(i),r.getObject(i));return map;}
    }
}
