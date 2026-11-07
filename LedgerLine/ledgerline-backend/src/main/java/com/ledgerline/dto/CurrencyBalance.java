package com.ledgerline.dto;

import java.math.BigDecimal;

public record CurrencyBalance(
        String currency,
        BigDecimal totalBalance,
        long accountCount) {
}
