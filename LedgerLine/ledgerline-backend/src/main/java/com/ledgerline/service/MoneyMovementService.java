package com.ledgerline.service;

import com.ledgerline.dto.FinancialTransactionResponse;
import com.ledgerline.dto.MoneyOperationRequest;
import com.ledgerline.entity.AccountEntity;
import com.ledgerline.entity.LedgerEntryEntity;
import com.ledgerline.entity.TransactionEntity;
import com.ledgerline.entity.UserEntity;
import com.ledgerline.enums.AccountStatus;
import com.ledgerline.enums.AccountType;
import com.ledgerline.enums.LedgerEntrySide;
import com.ledgerline.enums.TransactionType;
import com.ledgerline.exception.AccountNotActiveException;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.exception.ClearingAccountException;
import com.ledgerline.exception.InsufficientBalanceException;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.LedgerEntryRepository;
import com.ledgerline.repository.TransactionRepository;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.LedgerLinePrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MoneyMovementService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final UserRepository userRepository;
    private final AccountAuthorizationService accountAuthorizationService;
    private final RefusedOperationAuditService refusedOperationAuditService;
    private final String clearingAccountNumber;

    public MoneyMovementService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            UserRepository userRepository,
            AccountAuthorizationService accountAuthorizationService,
            RefusedOperationAuditService refusedOperationAuditService,
            @Value("${app.ledger.clearing-account-number}") String clearingAccountNumber) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.userRepository = userRepository;
        this.accountAuthorizationService = accountAuthorizationService;
        this.refusedOperationAuditService = refusedOperationAuditService;
        this.clearingAccountNumber = clearingAccountNumber;
    }

    @Transactional(noRollbackFor = {AccountNotActiveException.class, InsufficientBalanceException.class})
    public FinancialTransactionResponse deposit(Long accountId, MoneyOperationRequest request) {
        return post(accountId, request, TransactionType.DEPOSIT);
    }

    @Transactional(noRollbackFor = {AccountNotActiveException.class, InsufficientBalanceException.class})
    public FinancialTransactionResponse withdraw(Long accountId, MoneyOperationRequest request) {
        return post(accountId, request, TransactionType.WITHDRAWAL);
    }

    private FinancialTransactionResponse post(
            Long accountId,
            MoneyOperationRequest request,
            TransactionType operation) {
        LedgerLinePrincipal principal = accountAuthorizationService.currentPrincipalOrThrow();
        accountAuthorizationService.assertCanTransactOnAccount(accountId);

        List<AccountEntity> lockedAccounts =
                accountRepository.findAccountAndClearingAccountForUpdate(accountId, clearingAccountNumber);
        AccountEntity account = lockedAccounts.stream()
                .filter(candidate -> candidate.getId().equals(accountId))
                .findFirst()
                .orElse(null);
        AccountEntity clearing = lockedAccounts.stream()
                .filter(candidate -> candidate.getAccountNumber().equals(clearingAccountNumber))
                .findFirst()
                .orElse(null);
        if (account == null) {
            throw new AccountNotFoundException();
        }
        if (clearing == null || clearing.getAccountType() != AccountType.EXTERNAL_CLEARING
                || clearing.getOwner() != null
                || clearing.getStatus() != AccountStatus.ACTIVE) {
            throw new ClearingAccountException(
                    "The designated clearing account must be an unowned external clearing account");
        }
        if (account.getAccountType() == AccountType.EXTERNAL_CLEARING) {
            throw new AccountNotFoundException();
        }
        if (!account.getCurrency().equalsIgnoreCase(clearing.getCurrency())) {
            throw new ClearingAccountException(
                    "The account currency does not match the configured clearing account currency");
        }

        if (account.getStatus() != AccountStatus.ACTIVE) {
            refusedOperationAuditService.recordRefusedAttempt(
                    principal,
                    operation.name(),
                    accountId,
                    request.amount(),
                    account.getCurrency(),
                    "Account is not active");
            throw new AccountNotActiveException();
        }

        if (operation == TransactionType.WITHDRAWAL && account.getBalance().compareTo(request.amount()) < 0) {
            refusedOperationAuditService.recordRefusedAttempt(
                    principal,
                    operation.name(),
                    accountId,
                    request.amount(),
                    account.getCurrency(),
                    "Insufficient balance");
            throw new InsufficientBalanceException(request.amount(), account.getBalance());
        }

        AccountEntity source = operation == TransactionType.DEPOSIT ? clearing : account;
        AccountEntity destination = operation == TransactionType.DEPOSIT ? account : clearing;
        UserEntity initiator = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
        Instant postedAt = Instant.now();
        TransactionEntity transaction = transactionRepository.saveAndFlush(new TransactionEntity(
                initiator,
                source,
                destination,
                operation,
                request.amount(),
                account.getCurrency(),
                request.description() == null || request.description().isBlank()
                        ? null
                        : request.description().trim(),
                postedAt));

        LedgerEntrySide accountSide = operation == TransactionType.DEPOSIT
                ? LedgerEntrySide.DEBIT
                : LedgerEntrySide.CREDIT;
        LedgerEntrySide clearingSide = operation == TransactionType.DEPOSIT
                ? LedgerEntrySide.CREDIT
                : LedgerEntrySide.DEBIT;
        ledgerEntryRepository.saveAllAndFlush(List.of(
                new LedgerEntryEntity(transaction, account, accountSide, request.amount()),
                new LedgerEntryEntity(transaction, clearing, clearingSide, request.amount())));

        BigDecimal signedAmount = operation == TransactionType.DEPOSIT
                ? request.amount()
                : request.amount().negate();
        account.setBalance(account.getBalance().add(signedAmount));
        clearing.setBalance(clearing.getBalance().subtract(signedAmount));
        accountRepository.saveAllAndFlush(List.of(account, clearing));

        return new FinancialTransactionResponse(
                transaction.getId(),
                account.getId(),
                account.getAccountNumber(),
                operation,
                transaction.getStatus(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getCreatedAt(),
                transaction.getPostedAt());
    }
}
