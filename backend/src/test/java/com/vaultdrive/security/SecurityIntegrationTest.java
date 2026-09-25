package com.vaultdrive.security;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.http.HttpHeaders;

import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Test
    void shouldRejectRequestWithoutJwt() throws Exception {

        mockMvc.perform(
                get("/api/v1/users/me")
        )
        .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldRejectInvalidJwt() throws Exception {

        mockMvc.perform(
                get("/api/v1/users/me")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer invalid.jwt.token"
                        )
        )
        .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAllowRequestWithValidJwt() throws Exception {

        UUID userId = UUID.randomUUID();

        String token = jwtService.generateAccessToken(userId);

        mockMvc.perform(
                get("/api/v1/users/me")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + token
                        )
        )
        .andExpect(status().isOk())
        .andExpect(
                jsonPath("$.userId").value(userId.toString())
        );
    }

    @Test
    void shouldAllowPublicHealthEndpoint() throws Exception {

        mockMvc.perform(
                get("/api/v1/health")
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"));
    }
}