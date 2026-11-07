package com.ledgerline.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerline.dto.MoneyOperationRequest;
import com.ledgerline.entity.AccountEntity;
import com.ledgerline.entity.IdempotencyRecordEntity;
import com.ledgerline.entity.LedgerEntryEntity;
import com.ledgerline.entity.TransactionEntity;
import com.ledgerline.entity.UserEntity;
import com.ledgerline.enums.AccountType;
import com.ledgerline.enums.IdempotencyStatus;
import com.ledgerline.enums.LedgerEntrySide;
import com.ledgerline.enums.TransactionType;
import com.ledgerline.exception.IdempotencyFailureException;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.IdempotencyRecordRepository;
import com.ledgerline.repository.LedgerEntryRepository;
import com.ledgerline.repository.TransactionRepository;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.LedgerLinePrincipal;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.ledger.clearing-account-number=LL-CLEARING-TEST",
        "app.jwt.secret=ZHVtbXktdGVzdC1zZWNyZXQtZm9yLWxlZGdlcmxpbmU="
})
@EnabledIfEnvironmentVariable(named = "LEDGERLINE_MYSQL_TEST_URL", matches = "jdbc:mysql:.*")
class MySqlMoneyConcurrencyIntegrationTest {

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> requiredEnvironment("LEDGERLINE_MYSQL_TEST_URL"));
        registry.add("spring.datasource.username", () -> requiredEnvironment("LEDGERLINE_MYSQL_TEST_USERNAME"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault(
                "LEDGERLINE_MYSQL_TEST_PASSWORD",
                ""));
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    private MoneyMovementService moneyMovementService;

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
    private IdempotencyRecordRepository idempotencyRecordRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UserEntity user;
    private AccountEntity account;
    private AccountEntity clearing;

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set to run MySQL concurrency integration tests");
        }
        return value;
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM idempotency_records");
        jdbcTemplate.update("DELETE FROM ledger_entries");
        jdbcTemplate.update("DELETE FROM audit_logs");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM account_permissions");
        jdbcTemplate.update("DELETE FROM accounts");
        jdbcTemplate.update("DELETE FROM users");

        user = userRepository.saveAndFlush(new UserEntity(
                "mysql-holder",
                "mysql-holder@example.com",
                "test-hash",
                "MySQL Test Holder"));
        account = accountRepository.saveAndFlush(new AccountEntity(
                user,
                "U" + UUID.randomUUID().toString().replace("-", ""),
                "MySQL test account",
                AccountType.CHECKING,
                "USD"));
        clearing = accountRepository.saveAndFlush(new AccountEntity(
                null,
                "LL-CLEARING-TEST",
                "MySQL test clearing",
                AccountType.EXTERNAL_CLEARING,
                "USD"));
        authenticate();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void simultaneousSameKeyDepositsReturnOneCommittedResult() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Long> first = executor.submit(() -> tryDeposit(start, "mysql-same-key"));
            Future<Long> second = executor.submit(() -> tryDeposit(start, "mysql-same-key"));
            start.countDown();

            Long transactionId = first.get();
            assertEquals(transactionId, second.get());
            assertEquals(new BigDecimal("5.0000"),
                    accountRepository.findById(account.getId()).orElseThrow().getBalance());
            assertEquals(1, transactionRepository.count());
            assertEquals(2, ledgerEntryRepository.count());
            assertEquals(1, idempotencyRecordRepository.count());
            IdempotencyRecordEntity record = idempotencyRecordRepository
                    .findByUserIdAndKey(user.getId(), "mysql-same-key")
                    .orElseThrow();
            assertEquals(IdempotencyStatus.COMPLETED, record.getStatus());
            assertEquals(transactionId, record.getTransaction().getId());
            assertEquals(64, record.getRequestHash().length());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void concurrentEightHundredAndSevenHundredWithdrawalsAllowOnlyOneToCommit() throws Exception {
        account.setBalance(new BigDecimal("1000.0000"));
        accountRepository.saveAndFlush(account);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> eightHundred = executor.submit(
                    () -> tryWithdrawal(start, "mysql-withdraw-800", "800.0000"));
            Future<Boolean> sevenHundred = executor.submit(
                    () -> tryWithdrawal(start, "mysql-withdraw-700", "700.0000"));
            start.countDown();

            boolean eightHundredSucceeded = eightHundred.get();
            boolean sevenHundredSucceeded = sevenHundred.get();
            assertTrue(eightHundredSucceeded ^ sevenHundredSucceeded);

            BigDecimal finalBalance = accountRepository.findById(account.getId()).orElseThrow().getBalance();
            assertTrue(finalBalance.compareTo(new BigDecimal("200.0000")) == 0
                    || finalBalance.compareTo(new BigDecimal("300.0000")) == 0);
            List<TransactionEntity> transactions = transactionRepository.findAll();
            List<LedgerEntryEntity> entries = ledgerEntryRepository.findAll();
            assertEquals(1, transactions.size());
            assertEquals(TransactionType.WITHDRAWAL, transactions.get(0).getTransactionType());
            assertEquals(2, entries.size());
            assertTrue(entries.stream().anyMatch(entry ->
                    entry.getAccount().getId().equals(account.getId())
                            && entry.getEntrySide() == LedgerEntrySide.CREDIT
                            && entry.getAmount().compareTo(transactions.get(0).getAmount()) == 0));
            assertTrue(entries.stream().anyMatch(entry ->
                    entry.getAccount().getId().equals(clearing.getId())
                            && entry.getEntrySide() == LedgerEntrySide.DEBIT
                            && entry.getAmount().compareTo(transactions.get(0).getAmount()) == 0));
            assertEquals(transactions.get(0).getAmount(),
                    accountRepository.findById(clearing.getId()).orElseThrow().getBalance());
            assertEquals(2, idempotencyRecordRepository.count());
            IdempotencyStatus eightHundredStatus = idempotencyRecordRepository
                    .findByUserIdAndKey(user.getId(), "mysql-withdraw-800")
                    .orElseThrow()
                    .getStatus();
            IdempotencyStatus sevenHundredStatus = idempotencyRecordRepository
                    .findByUserIdAndKey(user.getId(), "mysql-withdraw-700")
                    .orElseThrow()
                    .getStatus();
            assertEquals(eightHundredSucceeded ? IdempotencyStatus.COMPLETED : IdempotencyStatus.FAILED,
                    eightHundredStatus);
            assertEquals(sevenHundredSucceeded ? IdempotencyStatus.COMPLETED : IdempotencyStatus.FAILED,
                    sevenHundredStatus);
            assertEquals(1, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE action = 'WITHDRAWAL_REFUSED'",
                    Integer.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedDatabaseWriteRollsBackBalancesLedgersTransactionAndIdempotency() {
        account.setBalance(new BigDecimal("999999999999999.9999"));
        accountRepository.saveAndFlush(account);
        MoneyOperationRequest request =
                new MoneyOperationRequest(new BigDecimal("0.0001"), "Overflow rollback");

        assertThrows(RuntimeException.class, () -> idempotencyService.executeMoneyOperation(
                "mysql-rollback",
                account.getId(),
                TransactionType.DEPOSIT,
                request,
                () -> moneyMovementService.deposit(account.getId(), request)));

        assertEquals(new BigDecimal("999999999999999.9999"),
                accountRepository.findById(account.getId()).orElseThrow().getBalance());
        assertEquals(BigDecimal.ZERO.setScale(4),
                accountRepository.findById(clearing.getId()).orElseThrow().getBalance());
        assertEquals(0, transactionRepository.count());
        assertEquals(0, ledgerEntryRepository.count());
        assertEquals(0, idempotencyRecordRepository.count());
    }

    private Long tryDeposit(CountDownLatch start, String key) throws Exception {
        authenticate();
        try {
            start.await();
            MoneyOperationRequest request =
                    new MoneyOperationRequest(new BigDecimal("5.0000"), "Same key concurrent deposit");
            return idempotencyService.executeMoneyOperation(
                            key,
                            account.getId(),
                            TransactionType.DEPOSIT,
                            request,
                            () -> moneyMovementService.deposit(account.getId(), request))
                    .transactionId();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private boolean tryWithdrawal(CountDownLatch start, String key, String amount) throws Exception {
        authenticate();
        try {
            start.await();
            BigDecimal requestedAmount = new BigDecimal(amount);
            MoneyOperationRequest request = new MoneyOperationRequest(requestedAmount, "Concurrent withdrawal");
            idempotencyService.executeMoneyOperation(
                    key,
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

    private void authenticate() {
        LedgerLinePrincipal principal = LedgerLinePrincipal.from(user);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        principal.getAuthorities()));
    }
}
