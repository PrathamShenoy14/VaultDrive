package com.vaultdrive.storage;

/** Contains no upstream exception details or bearer capabilities. */
public class MultipartStorageException extends RuntimeException {
    public MultipartStorageException() { super("Multipart storage outcome unavailable"); }
}
