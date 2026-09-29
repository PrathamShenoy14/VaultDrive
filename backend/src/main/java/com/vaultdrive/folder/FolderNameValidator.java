package com.vaultdrive.folder;

import com.vaultdrive.folder.exception.InvalidFolderNameException;
import org.springframework.stereotype.Component;

@Component
public class FolderNameValidator {

    private static final int MAX_NAME_LENGTH = 255;

    public String validateAndNormalize(String name) {

        if (name == null || name.isBlank()) {
            throw new InvalidFolderNameException(
                    "Folder name cannot be empty"
            );
        }

        // Reject invalid characters before normalization.
        for (int i = 0; i < name.length(); i++) {

            char character = name.charAt(i);

            if (character == '/' || character == '\\') {
                throw new InvalidFolderNameException(
                        "Folder name cannot contain / or \\"
                );
            }

            if (Character.isISOControl(character)) {
                throw new InvalidFolderNameException(
                        "Folder name cannot contain control characters"
                );
            }
        }

        String normalizedName = name.trim();

        if (normalizedName.length() > MAX_NAME_LENGTH) {
            throw new InvalidFolderNameException(
                    "Folder name cannot exceed 255 characters"
            );
        }

        return normalizedName;
    }
}
