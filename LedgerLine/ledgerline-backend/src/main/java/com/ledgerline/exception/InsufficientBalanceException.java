package com.ledgerline.exception;

import java.math.BigDecimal;

public class InsufficientBalanceException extends RuntimeException {

    private final BigDecimal requestedAmount;
    private final BigDecimal availableBalance;

    public InsufficientBalanceException(BigDecimal requestedAmount, BigDecimal availableBalance) {
        super("Insufficient balance: requested " + requestedAmount.toPlainString()
                + ", available " + availableBalance.toPlainString());
        this.requestedAmount = requestedAmount;
        this.availableBalance = availableBalance;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }
}
