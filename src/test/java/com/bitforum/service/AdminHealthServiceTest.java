package com.bitforum.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.Statement;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.bitforum.dto.AdminHealthResponse;

@ExtendWith(MockitoExtension.class)
class AdminHealthServiceTest {
    @Mock
    private DataSource dataSource;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ConnectionFactory rabbitConnectionFactory;
    @Mock
    private Connection sqlConnection;
    @Mock
    private Statement statement;
    @Mock
    private org.springframework.amqp.rabbit.connection.Connection rabbitConnection;

    @InjectMocks
    private AdminHealthService adminHealthService;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(dataSource.getConnection()).thenReturn(sqlConnection);
        lenient().when(sqlConnection.createStatement()).thenReturn(statement);
        lenient().when(statement.execute("SELECT 1")).thenReturn(true);
        lenient().when(stringRedisTemplate.execute(any(RedisCallback.class))).thenReturn("PONG");
        lenient().when(rabbitConnectionFactory.createConnection()).thenReturn(rabbitConnection);
    }

    @Test
    void checkShouldReturnUpWhenAllComponentsAreAvailable() {
        AdminHealthResponse response = adminHealthService.check();

        assertEquals("UP", response.getOverallStatus());
        assertEquals("UP", response.getApplication().getStatus());
        assertEquals("UP", response.getMysql().getStatus());
        assertEquals("UP", response.getRedis().getStatus());
        assertEquals("UP", response.getRabbitmq().getStatus());
        assertNotNull(response.getCheckedAt());
    }

    @Test
    void checkShouldReturnDownWhenMysqlThrowsException() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("mysql unavailable"));

        AdminHealthResponse response = adminHealthService.check();

        assertEquals("DOWN", response.getOverallStatus());
        assertEquals("DOWN", response.getMysql().getStatus());
        assertEquals("UP", response.getRedis().getStatus());
        assertEquals("UP", response.getRabbitmq().getStatus());
    }

    @Test
    void checkShouldReturnDownWhenRedisThrowsException() {
        when(stringRedisTemplate.execute(any(RedisCallback.class))).thenThrow(new RuntimeException("redis unavailable"));

        AdminHealthResponse response = adminHealthService.check();

        assertEquals("DOWN", response.getOverallStatus());
        assertEquals("UP", response.getMysql().getStatus());
        assertEquals("DOWN", response.getRedis().getStatus());
        assertEquals("UP", response.getRabbitmq().getStatus());
    }

    @Test
    void checkShouldReturnDownWhenRabbitmqThrowsException() {
        when(rabbitConnectionFactory.createConnection()).thenThrow(new RuntimeException("rabbit unavailable"));

        AdminHealthResponse response = adminHealthService.check();

        assertEquals("DOWN", response.getOverallStatus());
        assertEquals("UP", response.getMysql().getStatus());
        assertEquals("UP", response.getRedis().getStatus());
        assertEquals("DOWN", response.getRabbitmq().getStatus());
    }
}
