package com.bitforum.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ActuatorHealthControllerTest {
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private HttpCodeStatusMapper httpCodeStatusMapper;

    @Test
    void actuatorHealthShouldBeAccessibleWithoutDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").exists())
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void downAndOutOfServiceShouldUseServiceUnavailableStatus() {
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), httpCodeStatusMapper.getStatusCode(Status.DOWN));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), httpCodeStatusMapper.getStatusCode(Status.OUT_OF_SERVICE));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), httpCodeStatusMapper.getStatusCode(Status.UNKNOWN));
    }
}
