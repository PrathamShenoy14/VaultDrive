package com.vaultdrive.file;

import com.vaultdrive.file.exception.FileExtensionChangeException;
import org.springframework.stereotype.Component;

@Component
public class FileNameExtensionResolver {

    public String preserveExtension(
            String currentName,
            String requestedName
    ) {
        String currentExtension =
                extractExtension(currentName);

        String requestedExtension =
                extractExtension(requestedName);

        if (currentExtension == null) {
            return requestedName;
        }

        if (requestedExtension == null) {
            return requestedName + currentExtension;
        }

        if (!currentExtension.equalsIgnoreCase(requestedExtension)) {
            throw new FileExtensionChangeException(
                    "File extension cannot be changed"
            );
        }

        return requestedName;
    }

    private String extractExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }

        int lastDot = fileName.lastIndexOf('.');

        if (lastDot <= 0 || lastDot == fileName.length() - 1) {
            return null;
        }

        return fileName.substring(lastDot);
    }
}