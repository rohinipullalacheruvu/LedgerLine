package com.ledgerline.dto;

import com.ledgerline.enums.TransactionStatus;
import com.ledgerline.enums.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;

public record FinancialTransactionResponse(
        Long transactionId,
        Long accountId,
        String accountNumber,
        TransactionType transactionType,
        TransactionStatus status,
        BigDecimal amount,
        String currency,
        Instant createdAt,
        Instant postedAt) {
}
