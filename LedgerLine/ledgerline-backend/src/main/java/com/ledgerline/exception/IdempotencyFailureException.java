package com.ledgerline.exception;

public class IdempotencyFailureException extends RuntimeException {

    private final String failureCode;

    public IdempotencyFailureException(String failureCode, String message) {
        super(message);
        this.failureCode = failureCode;
    }

    public String getFailureCode() {
        return failureCode;
    }
}
