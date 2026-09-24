package com.vaultdrive.auth;

import com.vaultdrive.auth.config.PasswordConfig;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordConfigTest {

    @Test
    void shouldHashAndVerifyPassword() {

        // Arrange
        PasswordEncoder encoder =
                new PasswordConfig().passwordEncoder();

        String password = "MySecurePassword123!";

        // Act
        String hash = encoder.encode(password);

        // Assert
        assertThat(hash)
                .isNotEqualTo(password);

        assertThat(encoder.matches(password, hash))
                .isTrue();

        assertThat(encoder.matches("WrongPassword", hash))
                .isFalse();
    }
}