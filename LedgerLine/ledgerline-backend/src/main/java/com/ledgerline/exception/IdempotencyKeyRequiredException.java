package com.ledgerline.exception;

public class IdempotencyKeyRequiredException extends RuntimeException {

    public IdempotencyKeyRequiredException() {
        super("A valid Idempotency-Key header is required for money-moving requests");
    }
}
