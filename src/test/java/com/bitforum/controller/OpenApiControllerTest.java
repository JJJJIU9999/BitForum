package com.bitforum.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class OpenApiControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void apiDocsShouldBeAccessible() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("BitForum 社区交流平台接口文档"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.paths['/api/admin/health']").exists())
                .andExpect(jsonPath("$.paths['/api/users/{userId}/follow']").exists())
                .andExpect(jsonPath("$.paths['/api/upload/avatar']").exists())
                .andExpect(jsonPath("$.paths['/api/upload/article-cover']").exists());
    }

    @Test
    void swaggerUiShouldBeAccessible() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
