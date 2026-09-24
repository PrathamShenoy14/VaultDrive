package com.vaultdrive.auth;

public class EmailAlreadyExistsException
        extends RuntimeException {

    public EmailAlreadyExistsException() {
        super("Email is already registered");
    }
}