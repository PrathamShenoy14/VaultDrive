package com.vaultdrive.folder.exception;

public class FolderNotFoundException extends RuntimeException {

    public FolderNotFoundException(String message) {
        super(message);
    }
}