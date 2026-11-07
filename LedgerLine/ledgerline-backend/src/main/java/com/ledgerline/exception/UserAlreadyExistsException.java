package com.ledgerline.exception;

public class UserAlreadyExistsException extends RuntimeException {

    public UserAlreadyExistsException() {
        super("Username or email is already registered");
    }
}
