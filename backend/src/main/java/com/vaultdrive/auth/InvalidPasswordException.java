package com.vaultdrive.auth;

public class InvalidPasswordException
        extends RuntimeException {

    public InvalidPasswordException(String message) {
        super(message);
    }
}