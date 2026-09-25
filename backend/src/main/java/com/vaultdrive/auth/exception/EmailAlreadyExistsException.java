package com.vaultdrive.auth.exception;

public class EmailAlreadyExistsException
        extends RuntimeException {

    public EmailAlreadyExistsException() {
        super("Email is already registered");
    }
}