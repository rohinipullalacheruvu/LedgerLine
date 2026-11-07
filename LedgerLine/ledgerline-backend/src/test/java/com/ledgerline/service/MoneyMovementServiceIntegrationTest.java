package com.ledgerline.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerline.dto.FinancialTransactionResponse;
import com.ledgerline.dto.MoneyOperationRequest;
import com.ledgerline.dto.AccountStatementResponse;
import com.ledgerline.dto.CurrencyBalanceSummary;
import com.ledgerline.dto.TransferRequest;
import com.ledgerline.dto.TransferResponse;
import com.ledgerline.entity.AccountEntity;
import com.ledgerline.entity.AuditLogEntity;
import com.ledgerline.entity.LedgerEntryEntity;
import com.ledgerline.entity.TransactionEntity;
import com.ledgerline.entity.UserEntity;
import com.ledgerline.enums.AccountType;
import com.ledgerline.enums.LedgerEntrySide;
import com.ledgerline.enums.TransactionStatus;
import com.ledgerline.enums.TransactionType;
import com.ledgerline.enums.UserRole;
import com.ledgerline.exception.InsufficientBalanceException;
import com.ledgerline.exception.AccountAccessDeniedException;
import com.ledgerline.exception.IdempotencyConflictException;
import com.ledgerline.exception.IdempotencyFailureException;
import com.ledgerline.exception.IdempotencyKeyRequiredException;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.AuditLogRepository;
import com.ledgerline.repository.IdempotencyRecordRepository;
import com.ledgerline.repository.LedgerEntryRepository;
import com.ledgerline.repository.TransactionRepository;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.LedgerLinePrincipal;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@SpringBootTest(properties = {
        "DB_USERNAME=sa",
        "DB_PASSWORD=",
        "DB_URL=jdbc:h2:mem:ledgerline-money;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.url=jdbc:h2:mem:ledgerline-money;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.jwt.secret=ZHVtbXktdGVzdC1zZWNyZXQtZm9yLWxlZGdlcmxpbmU=",
        "app.ledger.clearing-account-number=LL-CLEARING-TEST"
})
class MoneyMovementServiceIntegrationTest {

    @Autowired
    private MoneyMovementService moneyMovementService;

    @Autowired
    private MoneyTransferService moneyTransferService;

    @Autowired
    private AccountStatementService accountStatementService;

    @Autowired
    private AccountManagementService accountManagementService;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository ledgerEntryRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Validator validator;

    private UserEntity user;
    private AccountEntity account;
    private AccountEntity receiver;
    private AccountEntity clearing;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM idempotency_records");
        jdbcTemplate.update("DELETE FROM audit_logs");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM account_permissions");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");

        user = userRepository.saveAndFlush(new UserEntity(
                "holder",
                "holder@example.com",
                "test-hash",
                "Test Holder"));
        account = accountRepository.saveAndFlush(new AccountEntity(
                user,
                "U" + UUID.randomUUID().toString().replace("-", ""),
                "Test account",
                AccountType.CHECKING,
                "USD"));
        receiver = accountRepository.saveAndFlush(new AccountEntity(
                user,
                "U" + UUID.randomUUID().toString().replace("-", ""),
                "Receiver account",
                AccountType.SAVINGS,
                "USD"));
        clearing = accountRepository.saveAndFlush(new AccountEntity(
                null,
                "LL-CLEARING-TEST",
                "Test external clearing",
                AccountType.EXTERNAL_CLEARING,
                "USD"));
        authenticate();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void depositUpdatesBalancesAndCreatesBalancedPostedLedgerEntries() {
        FinancialTransactionResponse response = moneyMovementService.deposit(
                account.getId(),
                new MoneyOperationRequest(new BigDecimal("25.7500"), "Cash deposit"));

        AccountEntity updatedAccount = accountRepository.findById(account.getId()).orElseThrow();
        AccountEntity updatedClearing = accountRepository.findById(clearing.getId()).orElseThrow();
        List<LedgerEntryEntity> entries = ledgerEntryRepository.findAll();
        TransactionEntity transaction = transactionRepository.findById(response.transactionId()).orElseThrow();

        assertEquals(new BigDecimal("25.7500"), updatedAccount.getBalance());
        assertEquals(new BigDecimal("-25.7500"), updatedClearing.getBalance());
        assertEquals(TransactionType.DEPOSIT, transaction.getTransactionType());
        assertEquals(TransactionStatus.POSTED, transaction.getStatus());
        assertEquals(user.getId(), transaction.getInitiatedBy().getId());
        assertEquals(2, entries.size());
        assertEntry(entries, account.getId(), LedgerEntrySide.DEBIT, "25.7500");
        assertEntry(entries, clearing.getId(), LedgerEntrySide.CREDIT, "25.7500");
        assertEquals(TransactionStatus.POSTED, response.status());
        assertEquals("USD", response.currency());
    }

    @Test
    void withdrawalDecreasesBalanceAndCreatesBalancedPostedLedgerEntries() {
        account.setBalance(new BigDecimal("40.0000"));
        accountRepository.saveAndFlush(account);

        FinancialTransactionResponse response = moneyMovementService.withdraw(
                account.getId(),
                new MoneyOperationRequest(new BigDecimal("12.5000"), "ATM withdrawal"));

        AccountEntity updatedAccount = accountRepository.findById(account.getId()).orElseThrow();
        AccountEntity updatedClearing = accountRepository.findById(clearing.getId()).orElseThrow();
        List<LedgerEntryEntity> entries = ledgerEntryRepository.findAll();

        assertEquals(new BigDecimal("27.5000"), updatedAccount.getBalance());
        assertEquals(new BigDecimal("12.5000"), updatedClearing.getBalance());
        assertEquals(TransactionType.WITHDRAWAL, transactionRepository.findById(response.transactionId())
                .orElseThrow().getTransactionType());
        assertEquals(2, entries.size());
        assertEntry(entries, account.getId(), LedgerEntrySide.CREDIT, "12.5000");
        assertEntry(entries, clearing.getId(), LedgerEntrySide.DEBIT, "12.5000");
    }

    @Test
    void insufficientWithdrawalIsAuditedWithoutTransactionOrLedgerPosting() {
        account.setBalance(new BigDecimal("9.0000"));
        accountRepository.saveAndFlush(account);

        assertThrows(
                InsufficientBalanceException.class,
                () -> moneyMovementService.withdraw(
                        account.getId(),
                        new MoneyOperationRequest(new BigDecimal("10.0000"), "Over balance")));

        assertEquals(new BigDecimal("9.0000"),
                accountRepository.findById(account.getId()).orElseThrow().getBalance());
        assertTrue(transactionRepository.findAll().isEmpty());
        assertTrue(ledgerEntryRepository.findAll().isEmpty());
        List<AuditLogEntity> auditLogs = auditLogRepository.findAll();
        assertEquals(1, auditLogs.size());
        assertEquals("WITHDRAWAL_REFUSED", auditLogs.get(0).getAction());
        assertEquals(account.getId(), auditLogs.get(0).getEntityId());
        assertEquals("Insufficient balance", auditLogs.get(0).getDetails().get("reason"));
    }

    @Test
    void transferMovesFundsAndCreatesBalancedPostedLedgerEntries() {
        account.setBalance(new BigDecimal("40.0000"));
        receiver.setBalance(new BigDecimal("7.5000"));
        accountRepository.saveAllAndFlush(List.of(account, receiver));

        TransferResponse response = moneyTransferService.transfer(new TransferRequest(
                account.getAccountNumber(),
                receiver.getAccountNumber(),
                new BigDecimal("12.2500"),
                "Internal transfer"));

        AccountEntity updatedSender = accountRepository.findById(account.getId()).orElseThrow();
        AccountEntity updatedReceiver = accountRepository.findById(receiver.getId()).orElseThrow();
        TransactionEntity transaction = transactionRepository.findAll().get(0);
        List<LedgerEntryEntity> entries = ledgerEntryRepository.findAll();

        assertEquals(new BigDecimal("27.7500"), updatedSender.getBalance());
        assertEquals(new BigDecimal("19.7500"), updatedReceiver.getBalance());
        assertEquals(new BigDecimal("47.5000"),
                updatedSender.getBalance().add(updatedReceiver.getBalance()));
        assertEquals(TransactionType.TRANSFER, transaction.getTransactionType());
        assertEquals(TransactionStatus.POSTED, transaction.getStatus());
        assertEquals(user.getId(), transaction.getInitiatedBy().getId());
        assertEquals(account.getId(), transaction.getSourceAccount().getId());
        assertEquals(receiver.getId(), transaction.getDestinationAccount().getId());
        assertEquals("LLTX-" + transaction.getId(), response.transactionReference());
        assertEquals(TransactionStatus.POSTED, response.status());
        assertEquals(2, entries.size());
        assertEntry(entries, account.getId(), LedgerEntrySide.DEBIT, "12.2500");
        assertEntry(entries, receiver.getId(), LedgerEntrySide.CREDIT, "12.2500");

        assertTrue(transactionRepository.findAll().stream()
                .filter(item -> item.getTransactionType() == TransactionType.TRANSFER)
                .allMatch(item -> {
                    List<LedgerEntryEntity> transferEntries = ledgerEntryRepository.findAll().stream()
                            .filter(entry -> entry.getTransaction().getId().equals(item.getId()))
                            .toList();
                    BigDecimal debits = transferEntries.stream()
                            .filter(entry -> entry.getEntrySide() == LedgerEntrySide.DEBIT)
                            .map(LedgerEntryEntity::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal credits = transferEntries.stream()
                            .filter(entry -> entry.getEntrySide() == LedgerEntrySide.CREDIT)
                            .map(LedgerEntryEntity::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    return debits.compareTo(credits) == 0;
                }));
    }

    @Test
    void statementReturnsAllEntriesWithChronologicalRunningBalancesAndFilters() {
        moneyMovementService.deposit(account.getId(),
                new MoneyOperationRequest(new BigDecimal("10.0000"), "Opening deposit"));
        moneyMovementService.withdraw(account.getId(),
                new MoneyOperationRequest(new BigDecimal("3.0000"), "Cash withdrawal"));
        moneyTransferService.transfer(new TransferRequest(
                account.getAccountNumber(),
                receiver.getAccountNumber(),
                new BigDecimal("2.0000"),
                "Move to savings"));

        AccountStatementResponse statement = accountStatementService.getStatement(
                account.getId(),
                null,
                null,
                null);
        AccountStatementResponse withdrawalsOnly = accountStatementService.getStatement(
                account.getId(),
                LocalDate.now(ZoneOffset.UTC),
                LocalDate.now(ZoneOffset.UTC),
                TransactionType.WITHDRAWAL);

        assertEquals(3, statement.entries().size());
        assertEquals(new BigDecimal("10.0000"), statement.entries().get(0).runningBalance());
        assertEquals(new BigDecimal("7.0000"), statement.entries().get(1).runningBalance());
        assertEquals(new BigDecimal("5.0000"), statement.entries().get(2).runningBalance());
        assertEquals(LedgerEntrySide.DEBIT, statement.entries().get(0).entrySide());
        assertEquals(LedgerEntrySide.CREDIT, statement.entries().get(1).entrySide());
        assertEquals(TransactionStatus.POSTED, statement.entries().get(1).status());
        assertEquals(TransactionType.WITHDRAWAL, withdrawalsOnly.entries().get(0).transactionType());
        assertEquals(new BigDecimal("7.0000"), withdrawalsOnly.entries().get(0).runningBalance());
        assertEquals(user.getUsername(), withdrawalsOnly.entries().get(0).initiatedByUsername());
        assertEquals(user.getFullName(), withdrawalsOnly.entries().get(0).initiatedByFullName());
        assertEquals(new BigDecimal("5.0000"), statement.currentBalance());
    }

    @Test
    void statementRejectsDateRangeWithStartAfterEnd() {
        assertThrows(
                com.ledgerline.exception.InvalidStatementFilterException.class,
                () -> accountStatementService.getStatement(
                        account.getId(),
                        LocalDate.now(ZoneOffset.UTC).plusDays(1),
                        LocalDate.now(ZoneOffset.UTC),
                        null));
    }

    @Test
    void portfolioSummaryGroupsOwnerBalancesAndExcludesSystemClearingAccount() {
        account.setBalance(new BigDecimal("125.5000"));
        receiver.setBalance(new BigDecimal("24.5000"));
        accountRepository.saveAndFlush(account);
        accountRepository.saveAndFlush(receiver);
        moneyMovementService.deposit(
                account.getId(),
                new MoneyOperationRequest(new BigDecimal("2.0000"), "Monthly deposit"));
        moneyMovementService.withdraw(
                receiver.getId(),
                new MoneyOperationRequest(new BigDecimal("4.0000"), "Monthly withdrawal"));

        AccountEntity euroAccount = new AccountEntity(
                user,
                "E" + UUID.randomUUID().toString().replace("-", ""),
                "Euro account",
                AccountType.CHECKING,
                "EUR");
        euroAccount.setBalance(new BigDecimal("40.0000"));
        accountRepository.saveAndFlush(euroAccount);

        UserEntity anotherUser = userRepository.saveAndFlush(new UserEntity(
                "another-holder",
                "another-holder@example.com",
                "test-hash",
                "Another Holder"));
        AccountEntity otherUserAccount = new AccountEntity(
                anotherUser,
                "O" + UUID.randomUUID().toString().replace("-", ""),
                "Other user's account",
                AccountType.CHECKING,
                "USD");
        otherUserAccount.setBalance(new BigDecimal("900.0000"));
        accountRepository.saveAndFlush(otherUserAccount);

        AccountEntity updatedClearing = accountRepository.findById(clearing.getId()).orElseThrow();
        updatedClearing.setBalance(new BigDecimal("-190.0000"));
        accountRepository.saveAndFlush(updatedClearing);

        List<CurrencyBalanceSummary> summary = accountManagementService.getPortfolioBalanceSummary();

        assertEquals(2, summary.size());
        assertEquals("EUR", summary.get(0).currency());
        assertEquals(new BigDecimal("40.0000"), summary.get(0).totalBalance());
        assertEquals(1, summary.get(0).accountCount());
        assertEquals("USD", summary.get(1).currency());
        assertEquals(new BigDecimal("148.0000"), summary.get(1).totalBalance());
        assertEquals(2, summary.get(1).accountCount());
        assertEquals(new BigDecimal("2.0000"), summary.get(1).monthlyDeposits());
        assertEquals(1, summary.get(1).monthlyDepositCount());
        assertEquals(new BigDecimal("4.0000"), summary.get(1).monthlyWithdrawals());
        assertEquals(1, summary.get(1).monthlyWithdrawalCount());

        user.setRole(UserRole.AUDITOR);
        authenticate();
        List<CurrencyBalanceSummary> systemSummary = accountManagementService.getPortfolioBalanceSummary();

        assertEquals(new BigDecimal("1048.0000"), systemSummary.get(1).totalBalance());
        assertEquals(3, systemSummary.get(1).accountCount());
        assertEquals(new BigDecimal("2.0000"), systemSummary.get(1).monthlyDeposits());
        assertEquals(new BigDecimal("4.0000"), systemSummary.get(1).monthlyWithdrawals());
    }

    @Test
    void insufficientTransferIsAuditedWithoutTransactionOrLedgerPosting() {
        account.setBalance(new BigDecimal("9.0000"));
        accountRepository.saveAndFlush(account);

        assertThrows(
                InsufficientBalanceException.class,
                () -> moneyTransferService.transfer(new TransferRequest(
                        account.getAccountNumber(),
                        receiver.getAccountNumber(),
                        new BigDecimal("10.0000"),
                        "Over balance")));

        assertEquals(new BigDecimal("9.0000"),
                accountRepository.findById(account.getId()).orElseThrow().getBalance());
        assertEquals(BigDecimal.ZERO.setScale(4),
                accountRepository.findById(receiver.getId()).orElseThrow().getBalance());
        assertTrue(transactionRepository.findAll().isEmpty());
        assertTrue(ledgerEntryRepository.findAll().isEmpty());
        List<AuditLogEntity> auditLogs = auditLogRepository.findAll();
        assertEquals(1, auditLogs.size());
        assertEquals("TRANSFER_REFUSED", auditLogs.get(0).getAction());
        assertEquals(account.getId(), auditLogs.get(0).getEntityId());
    }

    @Test
    void retryWithSameKeyAndPayloadReturnsOriginalTransaction() {
        MoneyOperationRequest request =
                new MoneyOperationRequest(new BigDecimal("5.0000"), "Retry-safe deposit");
        FinancialTransactionResponse original = idempotencyService.executeMoneyOperation(
                "deposit-retry",
                account.getId(),
                TransactionType.DEPOSIT,
                request,
                () -> moneyMovementService.deposit(account.getId(), request));
        FinancialTransactionResponse retry = idempotencyService.executeMoneyOperation(
                "deposit-retry",
                account.getId(),
                TransactionType.DEPOSIT,
                request,
                () -> {
                    throw new AssertionError("A completed request must not execute twice");
                });

        assertEquals(original.transactionId(), retry.transactionId());
        assertEquals(new BigDecimal("5.0000"),
                accountRepository.findById(account.getId()).orElseThrow().getBalance());
        assertEquals(1, transactionRepository.count());
        assertEquals(2, ledgerEntryRepository.count());
        assertEquals(1, idempotencyRecordRepository.count());
        assertEquals(64, idempotencyRecordRepository.findAll().get(0).getRequestHash().length());
    }

    @Test
    void reusingKeyWithDifferentRequestPayloadIsRejected() {
        MoneyOperationRequest originalRequest =
                new MoneyOperationRequest(new BigDecimal("5.0000"), "Original");
        idempotencyService.executeMoneyOperation(
                "payload-conflict",
                account.getId(),
                TransactionType.DEPOSIT,
                originalRequest,
                () -> moneyMovementService.deposit(account.getId(), originalRequest));

        assertThrows(
                IdempotencyConflictException.class,
                () -> idempotencyService.executeMoneyOperation(
                        "payload-conflict",
                        account.getId(),
                        TransactionType.DEPOSIT,
                        new MoneyOperationRequest(new BigDecimal("6.0000"), "Changed"),
                        () -> {
                            throw new AssertionError("A payload conflict must not execute");
                        }));

        assertEquals(new BigDecimal("5.0000"),
                accountRepository.findById(account.getId()).orElseThrow().getBalance());
        assertEquals(1, transactionRepository.count());
        assertEquals(1, idempotencyRecordRepository.count());
    }

    @Test
    void missingIdempotencyKeyIsRejectedBeforeMoneyMoves() {
        MoneyOperationRequest request =
                new MoneyOperationRequest(new BigDecimal("5.0000"), "Missing key");

        assertThrows(
                IdempotencyKeyRequiredException.class,
                () -> idempotencyService.executeMoneyOperation(
                        null,
                        account.getId(),
                        TransactionType.DEPOSIT,
                        request,
                        () -> moneyMovementService.deposit(account.getId(), request)));

        assertEquals(BigDecimal.ZERO.setScale(4),
                accountRepository.findById(account.getId()).orElseThrow().getBalance());
        assertTrue(transactionRepository.findAll().isEmpty());
        assertTrue(idempotencyRecordRepository.findAll().isEmpty());
    }

    @Test
    void refusedRequestIsRecordedAndSameKeyReplaysTheOriginalFailure() {
        account.setBalance(new BigDecimal("1.0000"));
        accountRepository.saveAndFlush(account);
        MoneyOperationRequest request =
                new MoneyOperationRequest(new BigDecimal("2.0000"), "Refused withdrawal");
        IdempotencyFailureException first = assertThrows(
                IdempotencyFailureException.class,
                () -> idempotencyService.executeMoneyOperation(
                        "refused-withdrawal",
                        account.getId(),
                        TransactionType.WITHDRAWAL,
                        request,
                        () -> moneyMovementService.withdraw(account.getId(), request)));
        AccountEntity fundedAccount = accountRepository.findById(account.getId()).orElseThrow();
        fundedAccount.setBalance(new BigDecimal("10.0000"));
        accountRepository.saveAndFlush(fundedAccount);

        IdempotencyFailureException retry = assertThrows(
                IdempotencyFailureException.class,
                () -> idempotencyService.executeMoneyOperation(
                        "refused-withdrawal",
                        account.getId(),
                        TransactionType.WITHDRAWAL,
                        request,
                        () -> {
                            throw new AssertionError("A refused request must replay its original outcome");
                        }));

        assertEquals(first.getMessage(), retry.getMessage());
        assertEquals(new BigDecimal("10.0000"),
                accountRepository.findById(account.getId()).orElseThrow().getBalance());
        assertTrue(transactionRepository.findAll().isEmpty());
        assertTrue(ledgerEntryRepository.findAll().isEmpty());
        assertEquals(1, idempotencyRecordRepository.count());
        assertEquals("FAILED", idempotencyRecordRepository.findAll().get(0).getStatus().name());
        assertEquals(
                "INSUFFICIENT_BALANCE",
                idempotencyRecordRepository.findAll().get(0).getFailureCode());
    }

    @Test
    void simultaneousRequestsWithSameKeyPostOnlyOnce() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first = executor.submit(() -> tryIdempotentDeposit(start));
            Future<Long> second = executor.submit(() -> tryIdempotentDeposit(start));
            start.countDown();

            assertEquals(first.get(), second.get());
            assertEquals(new BigDecimal("5.0000"),
                    accountRepository.findById(account.getId()).orElseThrow().getBalance());
            assertEquals(1, transactionRepository.count());
            assertEquals(2, ledgerEntryRepository.count());
            assertEquals(1, idempotencyRecordRepository.count());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void transferFromUnauthorizedSenderIsRejected() {
        UserEntity otherUser = userRepository.saveAndFlush(new UserEntity(
                "other-holder",
                "other-holder@example.com",
                "test-hash",
                "Other Holder"));
        AccountEntity unauthorizedSender = accountRepository.saveAndFlush(new AccountEntity(
                otherUser,
                "U" + UUID.randomUUID().toString().replace("-", ""),
                "Other user's account",
                AccountType.CHECKING,
                "USD"));

        assertThrows(
                AccountAccessDeniedException.class,
                () -> moneyTransferService.transfer(new TransferRequest(
                        unauthorizedSender.getAccountNumber(),
                        receiver.getAccountNumber(),
                        new BigDecimal("1.0000"),
                        null)));

        assertTrue(transactionRepository.findAll().isEmpty());
        assertTrue(ledgerEntryRepository.findAll().isEmpty());
    }

    @Test
    void receiverBalanceOverflowRollsBackTransferAndLedger() {
        account.setBalance(new BigDecimal("1.0000"));
        receiver.setBalance(new BigDecimal("999999999999999.9999"));
        accountRepository.saveAllAndFlush(List.of(account, receiver));

        assertThrows(
                RuntimeException.class,
                () -> moneyTransferService.transfer(new TransferRequest(
                        account.getAccountNumber(),
                        receiver.getAccountNumber(),
                        new BigDecimal("0.0001"),
                        "Overflow rollback")));

        assertEquals(new BigDecimal("1.0000"),
                accountRepository.findById(account.getId()).orElseThrow().getBalance());
        assertEquals(new BigDecimal("999999999999999.9999"),
                accountRepository.findById(receiver.getId()).orElseThrow().getBalance());
        assertTrue(transactionRepository.findAll().isEmpty());
        assertTrue(ledgerEntryRepository.findAll().isEmpty());
    }

    @Test
    void databaseFailureAfterPostingStartsRollsBackTransactionLedgerAndBalances() {
        account.setBalance(new BigDecimal("999999999999999.9999"));
        accountRepository.saveAndFlush(account);

        assertThrows(
                RuntimeException.class,
                () -> moneyMovementService.deposit(
                        account.getId(),
                        new MoneyOperationRequest(new BigDecimal("0.0001"), "Overflow rollback")));

        assertEquals(
                new BigDecimal("999999999999999.9999"),
                accountRepository.findById(account.getId()).orElseThrow().getBalance());
        assertEquals(BigDecimal.ZERO.setScale(4),
                accountRepository.findById(clearing.getId()).orElseThrow().getBalance());
        assertTrue(transactionRepository.findAll().isEmpty());
        assertTrue(ledgerEntryRepository.findAll().isEmpty());
    }

    @Test
    void amountMustBePositiveAndFitTheDatabaseScale() {
        assertTrue(validator.validate(new MoneyOperationRequest(
                new BigDecimal("0.0001"), "valid")).isEmpty());
        assertTrue(validator.validate(new MoneyOperationRequest(
                BigDecimal.ZERO, null)).stream().anyMatch(error -> error.getPropertyPath().toString().equals("amount")));
        assertTrue(validator.validate(new MoneyOperationRequest(
                new BigDecimal("-1.0000"), null)).stream().anyMatch(error -> error.getPropertyPath().toString().equals("amount")));
        assertTrue(validator.validate(new MoneyOperationRequest(
                new BigDecimal("1.00001"), null)).stream().anyMatch(error -> error.getPropertyPath().toString().equals("amount")));
    }

    @Test
    void concurrentWithdrawalsCannotSpendTheSameAvailableBalanceTwice() throws Exception {
        account.setBalance(new BigDecimal("10.0000"));
        accountRepository.saveAndFlush(account);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> tryWithdrawal(start, "withdraw-a"));
            Future<Boolean> second = executor.submit(() -> tryWithdrawal(start, "withdraw-b"));
            start.countDown();

            boolean firstSucceeded = first.get();
            boolean secondSucceeded = second.get();

            assertTrue(firstSucceeded ^ secondSucceeded);
            assertEquals(new BigDecimal("3.0000"),
                    accountRepository.findById(account.getId()).orElseThrow().getBalance());
            assertEquals(1, transactionRepository.findAll().size());
            assertEquals(2, ledgerEntryRepository.findAll().size());
            assertEquals(1, auditLogRepository.findAll().size());
            assertEquals(2, idempotencyRecordRepository.count());
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean tryWithdrawal(CountDownLatch start, String idempotencyKey) throws InterruptedException {
        LedgerLinePrincipal principal = LedgerLinePrincipal.from(user);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        principal.getAuthorities()));
        try {
            start.await();
            MoneyOperationRequest request =
                    new MoneyOperationRequest(new BigDecimal("7.0000"), "Concurrent withdrawal");
            idempotencyService.executeMoneyOperation(
                    idempotencyKey,
                    account.getId(),
                    TransactionType.WITHDRAWAL,
                    request,
                    () -> moneyMovementService.withdraw(account.getId(), request));
            return true;
        } catch (IdempotencyFailureException exception) {
            return false;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Long tryIdempotentDeposit(CountDownLatch start) throws InterruptedException {
        LedgerLinePrincipal principal = LedgerLinePrincipal.from(user);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        principal.getAuthorities()));
        try {
            start.await();
            MoneyOperationRequest request =
                    new MoneyOperationRequest(new BigDecimal("5.0000"), "Concurrent retry");
            return idempotencyService.executeMoneyOperation(
                            "same-concurrent-key",
                            account.getId(),
                            TransactionType.DEPOSIT,
                            request,
                            () -> moneyMovementService.deposit(account.getId(), request))
                    .transactionId();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void assertEntry(
            List<LedgerEntryEntity> entries,
            Long accountId,
            LedgerEntrySide side,
            String amount) {
        assertTrue(entries.stream().anyMatch(entry ->
                entry.getAccount().getId().equals(accountId)
                        && entry.getEntrySide() == side
                        && entry.getAmount().compareTo(new BigDecimal(amount)) == 0));
    }

    private void authenticate() {
        LedgerLinePrincipal principal = LedgerLinePrincipal.from(user);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        principal.getAuthorities()));
    }
}
