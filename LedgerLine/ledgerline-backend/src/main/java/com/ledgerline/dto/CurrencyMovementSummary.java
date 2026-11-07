package com.ledgerline.dto;

import java.math.BigDecimal;

public record CurrencyMovementSummary(
        String currency,
        BigDecimal totalAmount,
        long transactionCount) {
}
