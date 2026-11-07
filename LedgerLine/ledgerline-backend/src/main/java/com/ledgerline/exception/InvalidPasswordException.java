package com.ledgerline.exception;

public class InvalidPasswordException extends RuntimeException {

    public InvalidPasswordException() {
        super("Password must be no more than 72 UTF-8 bytes");
    }
}
