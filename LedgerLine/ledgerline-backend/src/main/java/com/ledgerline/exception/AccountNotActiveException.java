package com.ledgerline.exception;

public class AccountNotActiveException extends RuntimeException {

    public AccountNotActiveException() {
        super("Account is not active and cannot process this operation");
    }
}
