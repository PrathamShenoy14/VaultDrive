package com.vaultdrive.auth;

import com.vaultdrive.common.exception.GlobalExceptionHandler;
import com.vaultdrive.auth.dto.RegisterRequest;
import com.vaultdrive.auth.exception.EmailAlreadyExistsException;
import com.vaultdrive.auth.exception.InvalidPasswordException;

import com.vaultdrive.auth.dto.LoginRequest;
import com.vaultdrive.auth.exception.InvalidCredentialsException;

import com.vaultdrive.security.JwtService;

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
    private AuthenticationService authenticationService;
    private JwtService jwtService;    
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {

        registrationService = mock(RegistrationService.class);
        authenticationService = mock(AuthenticationService.class);
        jwtService = mock(JwtService.class);

        AuthController controller =
                new AuthController(
                    registrationService, 
                    authenticationService,
                    jwtService
                );

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

    @Test
    void shouldLoginSuccessfully() throws Exception {
    
        UUID userId = UUID.randomUUID();
    
        when(authenticationService.authenticate(any(LoginRequest.class)))
                .thenReturn(userId);
    
        when(jwtService.generateAccessToken(userId))
                .thenReturn("test-signed-jwt");
    
        String request = """
                {
                    "email": "pratham@example.com",
                    "password": "MySecurePassword123!"
                }
                """;
    
        mockMvc.perform(
                post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
        )
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").value("test-signed-jwt"))
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.expiresIn").value(jwtService.getAccessTokenExpiresInSeconds()));
    
        verify(authenticationService)
                .authenticate(any(LoginRequest.class));
    
        verify(jwtService)
                .generateAccessToken(userId);
    }

    @Test
    void shouldRejectInvalidLoginCredentials() throws Exception {
    
        when(authenticationService.authenticate(any(LoginRequest.class)))
                .thenThrow(new InvalidCredentialsException());
    
        String request = """
                {
                    "email": "pratham@example.com",
                    "password": "WrongPassword123!"
                }
                """;
    
        mockMvc.perform(
                post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
        )
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.status").value(401))
        .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
        .andExpect(jsonPath("$.message")
                .value("Invalid email or password"));
    }

    @Test
    void shouldRejectInvalidLoginRequest() throws Exception {
    
        String request = """
                {
                    "email": "invalid-email",
                    "password": ""
                }
                """;
    
        mockMvc.perform(
                post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request)
        )
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("email")))
        .andExpect(jsonPath("$.message")
                .value(org.hamcrest.Matchers.containsString("password")));
    
        verifyNoInteractions(authenticationService);
    }
}