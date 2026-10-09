package com.vaultdrive.file.exception;

public class UploadFinalizationRejectedException
        extends RuntimeException {

    public UploadFinalizationRejectedException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
