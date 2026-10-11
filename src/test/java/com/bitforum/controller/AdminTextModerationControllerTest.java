package com.bitforum.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.bitforum.entity.User;
import com.bitforum.mapper.UserMapper;
import com.bitforum.util.JwtUtil;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminTextModerationControllerTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private UserMapper userMapper;
    @MockitoBean private JwtUtil jwtUtil;

    @Test
    void rulesRequireAdminRole() throws Exception {
        mockMvc.perform(get("/api/admin/text-moderation/rules"))
                .andExpect(status().isUnauthorized());

        User member = new User();
        member.setUsername("m17-member-" + UUID.randomUUID());
        member.setPassword("test-hash");
        member.setRole("USER");
        member.setStatus(1);
        userMapper.insert(member);
        when(jwtUtil.getUserId("member-token")).thenReturn(member.getId());
        mockMvc.perform(get("/api/admin/text-moderation/rules")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());

        member.setRole("ADMIN");
        userMapper.updateById(member);
        mockMvc.perform(get("/api/admin/text-moderation/rules")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
