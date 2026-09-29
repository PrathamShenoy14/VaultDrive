package com.vaultdrive.user;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
@ActiveProfiles("test")
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldSaveAndFindUserByEmail() {

        // Use a unique email to avoid collisions with other test runs.
        String email = UUID.randomUUID() + "@example.com";

        User user = new User(
                email,
                "temporary-test-hash",
                "Test User"
        );

        userRepository.saveAndFlush(user);

        Optional<User> result =
                userRepository.findByEmailIgnoreCase(
                        email.toUpperCase()
                );

        assertThat(result).isPresent();

        assertThat(result.get().getEmail())
                .isEqualTo(email);
    }

    @Test
    void shouldRejectDuplicateEmailIgnoringCase() {

        String email = UUID.randomUUID() + "@example.com";

        User firstUser = new User(
                email,
                "temporary-test-hash",
                "First User"
        );

        User secondUser = new User(
                email.toUpperCase(),
                "another-test-hash",
                "Second User"
        );

        userRepository.saveAndFlush(firstUser);

        assertThatThrownBy(() ->
                userRepository.saveAndFlush(secondUser)
        ).isInstanceOf(
                DataIntegrityViolationException.class
        );
    }
}