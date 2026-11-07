package com.ledgerline.dto;

import com.ledgerline.enums.AccountStatus;
import com.ledgerline.enums.AccountType;
import java.math.BigDecimal;
import java.time.Instant;

public record AdminAccountSummary(
        Long id,
        String accountNumber,
        String name,
        AccountType accountType,
        String currency,
        BigDecimal balance,
        AccountStatus status,
        Long ownerId,
        String ownerUsername,
        Instant createdAt) {
}
