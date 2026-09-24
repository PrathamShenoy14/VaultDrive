package com.vaultdrive.user;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE
)
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void shouldSaveAndFindUserByEmail() {

        // Arrange
        User user = new User(
                "test@example.com",
                "temporary-test-hash",
                "Test User"
        );

        // Act
        userRepository.saveAndFlush(user);

        Optional<User> result =
                userRepository.findByEmailIgnoreCase("TEST@example.com");

        // Assert
        assertThat(result).isPresent();

        assertThat(result.get().getEmail())
                .isEqualTo("test@example.com");
    }

    @Test
    void shouldRejectDuplicateEmailIgnoringCase() {

        // Arrange
        User firstUser = new User(
                "test@example.com",
                "temporary-test-hash",
                "First User"
        );

        User secondUser = new User(
                "TEST@example.com",
                "another-test-hash",
                "Second User"
        );

        // Act
        userRepository.saveAndFlush(firstUser);

        // Assert
        assertThatThrownBy(() ->
                userRepository.saveAndFlush(secondUser)
        ).isInstanceOf(DataIntegrityViolationException.class);
        }       
}