package com.ledgerline.exception;

public class InvalidAccountUpdateException extends RuntimeException {

    public InvalidAccountUpdateException() {
        super("At least one account field must be provided for update");
    }
}
