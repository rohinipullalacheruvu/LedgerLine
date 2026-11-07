package com.ledgerline.dto;

import java.math.BigDecimal;

public record AccountBalanceResponse(
        Long accountId,
        String accountNumber,
        String currency,
        BigDecimal balance) {
}
