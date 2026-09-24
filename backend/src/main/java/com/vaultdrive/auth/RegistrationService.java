package com.vaultdrive.auth;

import com.vaultdrive.auth.dto.RegisterRequest;
import com.vaultdrive.user.User;
import com.vaultdrive.user.UserRepository;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

@Service
public class RegistrationService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UUID register(RegisterRequest request) {

        // 1. Normalize the email address
        String email = request.email()
                .trim()
                .toLowerCase(Locale.ROOT);

        // 2. Enforce BCrypt's 72-byte password limit
        int passwordBytes = request.password()
                .getBytes(StandardCharsets.UTF_8)
                .length;

        if (passwordBytes > 72) {
            throw new InvalidPasswordException(
                    "Password exceeds 72 UTF-8 bytes"
            );
        }

        // 3. Check whether the email already exists
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyExistsException();
        }

        // 4. Hash the password
        String passwordHash = passwordEncoder.encode(
                request.password()
        );

        // 5. Construct the entity
        User user = new User(
                email,
                passwordHash,
                request.displayName().trim()
        );

        // 6. Persist and flush the entity
        User savedUser = userRepository.saveAndFlush(user);

        return savedUser.getId();
    }
}