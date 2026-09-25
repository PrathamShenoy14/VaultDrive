package com.vaultdrive.auth;

import com.vaultdrive.auth.dto.LoginRequest;
import com.vaultdrive.auth.exception.InvalidCredentialsException;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthenticationServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AuthenticationService authenticationService;

    @BeforeEach
    void setUp() {

        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);

        authenticationService = new AuthenticationService(
                userRepository,
                passwordEncoder
        );
    }

    @Test
    void shouldAuthenticateWithCorrectCredentials() {

        LoginRequest request = new LoginRequest(
                "  PRATHAM@example.com  ",
                "CorrectPassword123!"
        );

        User user = new User(
                "pratham@example.com",
                "stored-password-hash",
                "Pratham"
        );

        when(userRepository.findByEmailIgnoreCase("pratham@example.com"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches(
                "CorrectPassword123!",
                "stored-password-hash"
        )).thenReturn(true);

        UUID authenticatedUserId =
                authenticationService.authenticate(request);

        assertThat(authenticatedUserId)
                .isEqualTo(user.getId());

        verify(userRepository)
                .findByEmailIgnoreCase("pratham@example.com");

        verify(passwordEncoder)
                .matches("CorrectPassword123!", "stored-password-hash");
    }

    @Test
    void shouldRejectIncorrectPassword() {

        LoginRequest request = new LoginRequest(
                "pratham@example.com",
                "WrongPassword123!"
        );

        User user = new User(
                "pratham@example.com",
                "stored-password-hash",
                "Pratham"
        );

        when(userRepository.findByEmailIgnoreCase("pratham@example.com"))
                .thenReturn(Optional.of(user));

        when(passwordEncoder.matches(
                "WrongPassword123!",
                "stored-password-hash"
        )).thenReturn(false);

        assertThatThrownBy(
                () -> authenticationService.authenticate(request)
        )
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");

        verify(passwordEncoder)
                .matches("WrongPassword123!", "stored-password-hash");
    }

    @Test
    void shouldRejectUnknownEmail() {

        LoginRequest request = new LoginRequest(
                "unknown@example.com",
                "SomePassword123!"
        );

        when(userRepository.findByEmailIgnoreCase("unknown@example.com"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> authenticationService.authenticate(request)
        )
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Invalid email or password");

        verifyNoInteractions(passwordEncoder);
    }
}
