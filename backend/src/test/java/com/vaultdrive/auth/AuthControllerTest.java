package com.vaultdrive.auth;

import com.vaultdrive.common.exception.GlobalExceptionHandler;
import com.vaultdrive.auth.dto.RegisterRequest;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.dao.DataIntegrityViolationException;
import org.hibernate.exception.ConstraintViolationException;

class AuthControllerTest {

    private MockMvc mockMvc;
    private RegistrationService registrationService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {

        registrationService = mock(RegistrationService.class);

        AuthController controller =
                new AuthController(registrationService);

        mockMvc = MockMvcBuilders
                .standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        objectMapper = new ObjectMapper();
    }

    @Test
    void shouldRegisterUserSuccessfully() throws Exception {

        UUID userId = UUID.randomUUID();

        when(registrationService.register(any(RegisterRequest.class)))
                .thenReturn(userId);

        RegisterRequest request = new RegisterRequest(
                "pratham@example.com",
                "MySecurePassword123!",
                "Pratham"
        );

        mockMvc.perform(
                post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.userId").value(userId.toString()))
        .andExpect(jsonPath("$.message")
                .value("User registered successfully"));

        verify(registrationService)
                .register(any(RegisterRequest.class));
    }

    @Test
    void shouldReturnConflictForDuplicateEmail() throws Exception {

        when(registrationService.register(any(RegisterRequest.class)))
                .thenThrow(new EmailAlreadyExistsException());

        RegisterRequest request = new RegisterRequest(
                "pratham@example.com",
                "MySecurePassword123!",
                "Pratham"
        );

        mockMvc.perform(
                post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
        )
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.error").value("CONFLICT"))
        .andExpect(jsonPath("$.message")
                .value("Email is already registered"));
    }

    @Test
    void shouldRejectInvalidRegistrationData() throws Exception {

        String request = """
                {
                    "email": "invalid-email",
                    "password": "123",
                    "displayName": ""
                }
                """;

        mockMvc.perform(
                post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
        )
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
        .andExpect(jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("email")))
        .andExpect(jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("password")))
        .andExpect(jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("displayName")));

        verifyNoInteractions(registrationService);
    }

    @Test
    void shouldReturnBadRequestForInvalidPassword() throws Exception {

        when(registrationService.register(any(RegisterRequest.class)))
                .thenThrow(new InvalidPasswordException(
                        "Password exceeds 72 UTF-8 bytes"
                ));

        String request = """
                {
                    "email": "pratham@example.com",
                    "password": "MySecurePassword123!",
                    "displayName": "Pratham"
                }
                """;

        mockMvc.perform(
                post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
        )
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message")
                .value("Password exceeds 72 UTF-8 bytes"));
    }

    @Test
    void shouldReturnConflictForDatabaseEmailViolation() throws Exception {

        ConstraintViolationException databaseException =
                new ConstraintViolationException(
                        "Duplicate email",
                        new java.sql.SQLException(
                                "Duplicate key",
                                "23505"
                        ),
                        "users_email_unique_lower"
                );

        when(registrationService.register(any(RegisterRequest.class)))
                .thenThrow(new DataIntegrityViolationException(
                        "Database constraint violation",
                        databaseException
                ));

        String request = """
                {
                    "email": "pratham@example.com",
                    "password": "MySecurePassword123!",
                    "displayName": "Pratham"
                }
                """;

        mockMvc.perform(
                post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
        )
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.error").value("CONFLICT"))
        .andExpect(jsonPath("$.message")
                .value("Email is already registered"));
    }
}