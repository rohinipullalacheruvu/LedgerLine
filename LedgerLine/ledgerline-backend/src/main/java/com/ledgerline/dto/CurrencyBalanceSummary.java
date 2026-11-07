package com.ledgerline.dto;

import java.math.BigDecimal;

public record CurrencyBalanceSummary(
        String currency,
        BigDecimal totalBalance,
        long accountCount,
        BigDecimal monthlyDeposits,
        long monthlyDepositCount,
        BigDecimal monthlyWithdrawals,
        long monthlyWithdrawalCount) {
}
