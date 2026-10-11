package com.bitforum.service;

import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.bitforum.dto.AdminHealthResponse;
import com.bitforum.dto.HealthComponentStatus;

@Service
public class AdminHealthService {
    private static final String STATUS_UP = "UP";
    private static final String STATUS_DOWN = "DOWN";

    @Autowired
    private DataSource dataSource;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private ConnectionFactory rabbitConnectionFactory;

    public AdminHealthResponse check() {
        HealthComponentStatus application = HealthComponentStatus.of("应用服务", STATUS_UP, "应用正在运行");
        HealthComponentStatus mysql = checkMysql();
        HealthComponentStatus redis = checkRedis();
        HealthComponentStatus rabbitmq = checkRabbitmq();

        AdminHealthResponse response = new AdminHealthResponse();
        response.setApplication(application);
        response.setMysql(mysql);
        response.setRedis(redis);
        response.setRabbitmq(rabbitmq);
        response.setOverallStatus(resolveOverallStatus(List.of(application, mysql, redis, rabbitmq)));
        response.setCheckedAt(LocalDateTime.now());
        return response;
    }

    private HealthComponentStatus checkMysql() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            return HealthComponentStatus.of("MySQL", STATUS_UP, "数据库连接正常");
        } catch (Exception e) {
            return HealthComponentStatus.of("MySQL", STATUS_DOWN, "数据库连接检查失败");
        }
    }

    private HealthComponentStatus checkRedis() {
        try {
            String pong = stringRedisTemplate.execute((RedisCallback<String>) RedisConnection::ping);
            if ("PONG".equalsIgnoreCase(pong)) {
                return HealthComponentStatus.of("Redis", STATUS_UP, "Redis 连接正常");
            }
            return HealthComponentStatus.of("Redis", STATUS_DOWN, "Redis 未返回正常 PING 响应");
        } catch (Exception e) {
            return HealthComponentStatus.of("Redis", STATUS_DOWN, "Redis 连接检查失败");
        }
    }

    private HealthComponentStatus checkRabbitmq() {
        try {
            org.springframework.amqp.rabbit.connection.Connection connection = rabbitConnectionFactory.createConnection();
            try {
                return HealthComponentStatus.of("RabbitMQ", STATUS_UP, "RabbitMQ 连接正常");
            } finally {
                connection.close();
            }
        } catch (Exception e) {
            return HealthComponentStatus.of("RabbitMQ", STATUS_DOWN, "RabbitMQ 连接检查失败");
        }
    }

    private String resolveOverallStatus(List<HealthComponentStatus> components) {
        for (HealthComponentStatus component : components) {
            if (STATUS_DOWN.equals(component.getStatus())) {
                return STATUS_DOWN;
            }
        }
        return STATUS_UP;
    }
}
