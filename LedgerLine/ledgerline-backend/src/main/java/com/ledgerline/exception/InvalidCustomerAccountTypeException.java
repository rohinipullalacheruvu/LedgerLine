package com.ledgerline.exception;

public class InvalidCustomerAccountTypeException extends RuntimeException {

    public InvalidCustomerAccountTypeException() {
        super("External clearing accounts cannot be created through the customer account API");
    }
}
