package com.vaultdrive.file;

public class MultipartConflictException extends IllegalStateException {
    public MultipartConflictException() {
        super("Multipart request conflicts with retained identity, authorization or decision");
    }
}
