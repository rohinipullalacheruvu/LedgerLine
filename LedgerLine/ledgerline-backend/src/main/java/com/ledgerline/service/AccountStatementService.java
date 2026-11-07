package com.ledgerline.service;

import com.ledgerline.dto.AccountStatementEntry;
import com.ledgerline.dto.AccountStatementResponse;
import com.ledgerline.entity.AccountEntity;
import com.ledgerline.entity.LedgerEntryEntity;
import com.ledgerline.entity.TransactionEntity;
import com.ledgerline.enums.LedgerEntrySide;
import com.ledgerline.enums.TransactionType;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.exception.InvalidStatementFilterException;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.LedgerEntryRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountStatementService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final AccountAuthorizationService accountAuthorizationService;

    public AccountStatementService(
            AccountRepository accountRepository,
            LedgerEntryRepository ledgerEntryRepository,
            AccountAuthorizationService accountAuthorizationService) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.accountAuthorizationService = accountAuthorizationService;
    }

    @Transactional(readOnly = true)
    public AccountStatementResponse getStatement(
            Long accountId,
            LocalDate fromDate,
            LocalDate toDate,
            TransactionType transactionType) {
        if (fromDate != null && toDate != null && fromDate.isAfter(toDate)) {
            throw new InvalidStatementFilterException("fromDate must be on or before toDate");
        }

        accountAuthorizationService.assertCanViewAccount(accountId);
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(AccountNotFoundException::new);
        Instant fromInclusive = fromDate == null ? null : fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toExclusive = toDate == null
                ? null
                : toDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        BigDecimal runningBalance = BigDecimal.ZERO.setScale(4);
        List<AccountStatementEntry> filteredEntries = new ArrayList<>();
        for (LedgerEntryEntity entry : ledgerEntryRepository.findStatementEntriesByAccountId(accountId)) {
            TransactionEntity transaction = entry.getTransaction();
            Instant transactionDate = transaction.getPostedAt() == null
                    ? transaction.getCreatedAt()
                    : transaction.getPostedAt();
            runningBalance = runningBalance.add(balanceEffect(entry, accountId));

            if ((fromInclusive == null || !transactionDate.isBefore(fromInclusive))
                    && (toExclusive == null || transactionDate.isBefore(toExclusive))
                    && (transactionType == null || transaction.getTransactionType() == transactionType)) {
                filteredEntries.add(new AccountStatementEntry(
                        transaction.getId(),
                        transactionDate,
                        transaction.getTransactionType(),
                        transaction.getStatus(),
                        entry.getEntrySide(),
                        entry.getAmount(),
                        transaction.getCurrency(),
                        transaction.getDescription(),
                        transaction.getInitiatedBy().getUsername(),
                        transaction.getInitiatedBy().getFullName(),
                        runningBalance));
            }
        }

        return new AccountStatementResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getCurrency(),
                account.getBalance(),
                fromDate,
                toDate,
                List.copyOf(filteredEntries));
    }

    private BigDecimal balanceEffect(LedgerEntryEntity entry, Long accountId) {
        TransactionEntity transaction = entry.getTransaction();
        if (transaction.getDestinationAccount() != null
                && transaction.getDestinationAccount().getId().equals(accountId)) {
            return entry.getAmount();
        }
        if (transaction.getSourceAccount() != null
                && transaction.getSourceAccount().getId().equals(accountId)) {
            return entry.getAmount().negate();
        }
        return entry.getEntrySide() == LedgerEntrySide.DEBIT
                ? entry.getAmount()
                : entry.getAmount().negate();
    }
}
