package com.ledgerline.dto;

import com.ledgerline.enums.AccountStatus;
import com.ledgerline.enums.AccountType;
import java.math.BigDecimal;

public record AccountSummary(
        Long id,
        String accountNumber,
        String name,
        AccountType accountType,
        String currency,
        BigDecimal balance,
        AccountStatus status) {
}
