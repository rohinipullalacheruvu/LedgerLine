package com.ledgerline.exception;

public class AccountAccessDeniedException extends RuntimeException {

    public AccountAccessDeniedException() {
        super("You do not have permission to access this account");
    }
}
