package com.bitforum;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
// M13 起 AI 域拥有独立的 mapper 包（com.bitforum.ai.mapper）。
// @MapperScan 不会扫描子包，所以必须在这里显式列出，否则 AI 域 Mapper 不会注册为 bean。
@MapperScan({"com.bitforum.mapper", "com.bitforum.ai.mapper"})
public class BitForumSpringApplication {

	public static void main(String[] args) {
		SpringApplication.run(BitForumSpringApplication.class, args);
	}

}
