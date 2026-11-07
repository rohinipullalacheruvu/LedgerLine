package com.ledgerline.exception;

public class AccountNumberConflictException extends RuntimeException {

    public AccountNumberConflictException() {
        super("A unique account number could not be allocated; please retry");
    }
}
