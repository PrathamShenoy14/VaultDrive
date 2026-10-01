package com.vaultdrive.file;

import com.vaultdrive.file.exception.InvalidFileNameException;
import org.springframework.stereotype.Component;

@Component
public class FileNameValidator {

    public String validateAndNormalize(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidFileNameException("File name must not be blank");
        }

        for (char c : name.toCharArray()) {
            if (Character.isISOControl(c)) {
                throw new InvalidFileNameException(
                        "File name must not contain control characters"
                );
            }
        }

        String normalized = name.trim();

        if (normalized.contains("/") || normalized.contains("\\")) {
            throw new InvalidFileNameException(
                    "File name must not contain path separators"
            );
        }

        if (normalized.length() > 255) {
            throw new InvalidFileNameException(
                    "File name must not exceed 255 characters"
            );
        }

        return normalized;
    }
}
