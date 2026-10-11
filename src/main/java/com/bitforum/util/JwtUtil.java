package com.bitforum.util;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Component;

import com.bitforum.config.JwtProperties;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

// 交给 Spring 管理后，才能通过构造器拿到 JwtProperties 配置
@Component
public class JwtUtil {

    // 三个核心类的关系：
    // Jwts.builder()—造 Token（往里面塞数据→签名→打包）
    // Jwts.parser()—拆 Token（验证签名→解包→读数据）
    // Claims—Token 解析后的结果对象，.get("userId")取值、.getExpiration()查过期

    private final SecretKey key;
    private final long expiration;

    // Spring 会自动传入 JwtProperties；这里把配置里的 secret 转成 jjwt 需要的 SecretKey
    public JwtUtil(JwtProperties jwtProperties) {
        this.key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.expiration = jwtProperties.getExpiration();
    }
    /**
     * 生成 Token
     * 
     * @param userId   用户ID，塞进 Payload
     * @param username 用户名，塞进 subject
     * @return JWT 字符串（三段 Base64 用 . 连起来）
    */

    public String generateToken(Long userId, String username) {
        Date now = new Date();
        Date expireDate = new Date(System.currentTimeMillis()+ expiration);

        return Jwts.builder()
                .subject(username)          //主题 = 用户名
                .claim("userId", userId)    //自定义字段(key-value)
                .issuedAt(now)              //签发时间
                .expiration(expireDate)     //过期时间
                .signWith(key)              //用配置文件里的密钥签名
                .compact();                 //打包成字符串
    }
    
    /**
     * 解析 Token → 拿出里面的信息
     * 如果 Token 被篡改过或过期了，会自动抛异常
    */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)          //用同一把配置密钥验证签名
                .build()
                .parseSignedClaims(token) //解析
                .getPayload();            //拿到 Payload 部分
    }
    
    /**
     * 检查 Token 是否过期
    */
    public boolean isTokenExpired(String token) {
        return parseToken(token)
                .getExpiration()        //拿到过期时间
                .before(new Date());
    }
   
    //从Token里取具体数据
    public Long getUserId(String token) {
        //"userId"对应generateToken里Claim的key
        return parseToken(token).get("userId", Long.class);
    }

    public String getUsername(String token) {
        return parseToken(token).getSubject();  //subject就是用户名
    }
}
