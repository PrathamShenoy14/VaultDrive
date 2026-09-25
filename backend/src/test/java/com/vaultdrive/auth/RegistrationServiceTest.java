package com.vaultdrive.auth;

import com.vaultdrive.auth.dto.RegisterRequest;
import com.vaultdrive.auth.exception.EmailAlreadyExistsException;
import com.vaultdrive.auth.exception.InvalidPasswordException;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.util.UUID;

class RegistrationServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private RegistrationService registrationService;

    @BeforeEach
    void setUp() {

        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);

        registrationService = new RegistrationService(
                userRepository,
                passwordEncoder
        );
    }

    // TEST 1: Successful registration

    @Test
    void shouldRegisterUserSuccessfully() {

        RegisterRequest request = new RegisterRequest(
                "  PRATHAM@example.com  ",
                "MySecurePassword123!",
                "  Pratham  "
        );

        when(userRepository.existsByEmailIgnoreCase(
                "pratham@example.com"
        )).thenReturn(false);

        when(passwordEncoder.encode(
                "MySecurePassword123!"
        )).thenReturn("hashed-password");

        when(userRepository.saveAndFlush(any(User.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0)
                );

        UUID userId = registrationService.register(request);

        ArgumentCaptor<User> captor =
                ArgumentCaptor.forClass(User.class);

        verify(userRepository).saveAndFlush(captor.capture());

        User savedUser = captor.getValue();

        assertThat(userId)
                .isNotNull()
                .isEqualTo(savedUser.getId());

        assertThat(savedUser.getEmail())
                .isEqualTo("pratham@example.com");

        assertThat(savedUser.getDisplayName())
                .isEqualTo("Pratham");

        assertThat(savedUser.getPasswordHash())
                .isEqualTo("hashed-password");

        verify(passwordEncoder)
                .encode("MySecurePassword123!");
    }

    // TEST 2: Duplicate email rejection

    @Test
    void shouldRejectDuplicateEmail() {

        RegisterRequest request = new RegisterRequest(
                "PRATHAM@example.com",
                "MySecurePassword123!",
                "Pratham"
        );

        when(userRepository.existsByEmailIgnoreCase(
                "pratham@example.com"
        )).thenReturn(true);

        assertThatThrownBy(() ->
                registrationService.register(request)
        )
                .isInstanceOf(EmailAlreadyExistsException.class)
                .hasMessage("Email is already registered");

        verify(userRepository, never())
                .saveAndFlush(any(User.class));

        verifyNoInteractions(passwordEncoder);
    }

    // TEST 3: Password exceeds 72 UTF-8 bytes

    @Test
    void shouldRejectPasswordExceeding72Bytes() {

        String longPassword = "a".repeat(73);

        RegisterRequest request = new RegisterRequest(
                "pratham@example.com",
                longPassword,
                "Pratham"
        );

        assertThatThrownBy(() ->
                registrationService.register(request)
        )
                .isInstanceOf(InvalidPasswordException.class)
                .hasMessage("Password exceeds 72 UTF-8 bytes");

        verifyNoInteractions(
                userRepository,
                passwordEncoder
        );
    }

    // TEST 4: Unicode password exceeds 72 UTF-8 bytes

    @Test
    void shouldRejectUnicodePasswordExceeding72Bytes() {

        String password = "😀".repeat(19);

        RegisterRequest request = new RegisterRequest(
                "pratham@example.com",
                password,
                "Pratham"
        );

        assertThatThrownBy(() ->
                registrationService.register(request)
        )
                .isInstanceOf(InvalidPasswordException.class)
                .hasMessage("Password exceeds 72 UTF-8 bytes");

        verifyNoInteractions(
                userRepository,
                passwordEncoder
        );
    }
}