package com.bitforum.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;

@Configuration
public class MybatisPlusConfig {
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        // MyBatis-Plus 的总拦截器，分页、乐观锁等内部插件都挂在这里
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页插件会把 selectPage 转成 MySQL 的 limit 分页 SQL
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
