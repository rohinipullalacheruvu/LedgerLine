package com.ledgerline.dto;

import com.ledgerline.enums.TransactionStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record TransferResponse(
        String transactionReference,
        String senderAccountNumber,
        String receiverAccountNumber,
        BigDecimal amount,
        String currency,
        TransactionStatus status,
        Instant createdAt,
        Instant postedAt) {
}
