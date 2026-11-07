package com.ledgerline.dto;

import com.ledgerline.enums.LedgerEntrySide;
import com.ledgerline.enums.TransactionStatus;
import com.ledgerline.enums.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;

public record AccountStatementEntry(
        Long transactionId,
        Instant transactionDate,
        TransactionType transactionType,
        TransactionStatus status,
        LedgerEntrySide entrySide,
        BigDecimal amount,
        String currency,
        String description,
        String initiatedByUsername,
        String initiatedByFullName,
        BigDecimal runningBalance) {
}
