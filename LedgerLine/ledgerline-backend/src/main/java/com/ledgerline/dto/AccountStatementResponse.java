package com.ledgerline.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record AccountStatementResponse(
        Long accountId,
        String accountNumber,
        String currency,
        BigDecimal currentBalance,
        LocalDate fromDate,
        LocalDate toDate,
        List<AccountStatementEntry> entries) {
}
