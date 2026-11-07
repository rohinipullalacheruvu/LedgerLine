package com.ledgerline.service;

import com.ledgerline.dto.AccountBalanceResponse;
import com.ledgerline.dto.AccountSummary;
import com.ledgerline.dto.AdminAccountSummary;
import com.ledgerline.dto.AdminUpdateAccountRequest;
import com.ledgerline.dto.CreateAccountRequest;
import com.ledgerline.dto.CurrencyBalance;
import com.ledgerline.dto.CurrencyBalanceSummary;
import com.ledgerline.dto.CurrencyMovementSummary;
import com.ledgerline.dto.UpdateAccountRequest;
import com.ledgerline.entity.AccountEntity;
import com.ledgerline.entity.UserEntity;
import com.ledgerline.enums.AccountType;
import com.ledgerline.enums.UserRole;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.exception.AccountNumberConflictException;
import com.ledgerline.exception.InvalidAccountUpdateException;
import com.ledgerline.exception.InvalidCustomerAccountTypeException;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.TransactionRepository;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.LedgerLinePrincipal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountManagementService {

    private static final int ACCOUNT_NUMBER_ATTEMPTS = 5;

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final AccountAuthorizationService accountAuthorizationService;

    public AccountManagementService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            UserRepository userRepository,
            AccountAuthorizationService accountAuthorizationService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.accountAuthorizationService = accountAuthorizationService;
    }

    @Transactional
    public AccountSummary createAccount(CreateAccountRequest request) {
        if (request.accountType() == AccountType.EXTERNAL_CLEARING) {
            throw new InvalidCustomerAccountTypeException();
        }
        LedgerLinePrincipal principal = accountAuthorizationService.currentPrincipalOrThrow();
        if (principal.getRole() != UserRole.ACCOUNT_HOLDER && principal.getRole() != UserRole.ADMIN) {
            throw new AccessDeniedException("Only account holders and administrators can create accounts");
        }

        UserEntity owner = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new IllegalStateException("Authenticated account owner no longer exists"));
        String accountNumber = generateAvailableAccountNumber();
        AccountEntity account = new AccountEntity(
                owner,
                accountNumber,
                request.name().trim(),
                request.accountType(),
                request.currency().trim().toUpperCase(Locale.ROOT));
        try {
            return toSummary(accountRepository.saveAndFlush(account));
        } catch (DataIntegrityViolationException exception) {
            if (accountRepository.existsByAccountNumber(accountNumber)) {
                throw new AccountNumberConflictException();
            }
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<AccountSummary> getMyAccounts() {
        Long ownerId = accountAuthorizationService.currentPrincipalOrThrow().getUserId();
        return accountRepository.findAllByOwner_IdOrderByCreatedAtDesc(ownerId)
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CurrencyBalanceSummary> getPortfolioBalanceSummary() {
        LedgerLinePrincipal principal = accountAuthorizationService.currentPrincipalOrThrow();
        boolean allCustomerAccounts = principal.getRole() == UserRole.ADMIN
                || principal.getRole() == UserRole.AUDITOR;
        if (!allCustomerAccounts && principal.getRole() != UserRole.ACCOUNT_HOLDER) {
            throw new AccessDeniedException("Account portfolio access is required");
        }

        LocalDate monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        java.time.Instant fromInclusive = monthStart.atStartOfDay(ZoneOffset.UTC).toInstant();
        java.time.Instant toExclusive = monthStart.plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<String, CurrencyBalanceSummary> summaries = new TreeMap<>();

        List<CurrencyBalance> balances = allCustomerAccounts
                ? accountRepository.summarizeAllCustomerBalances(AccountType.EXTERNAL_CLEARING)
                : accountRepository.summarizeCustomerBalancesByOwner(
                        principal.getUserId(),
                        AccountType.EXTERNAL_CLEARING);
        for (CurrencyBalance balance : balances) {
            summaries.put(balance.currency(), new CurrencyBalanceSummary(
                    balance.currency(),
                    balance.totalBalance(),
                    balance.accountCount(),
                    BigDecimal.ZERO.setScale(4),
                    0,
                    BigDecimal.ZERO.setScale(4),
                    0));
        }

        List<CurrencyMovementSummary> deposits = allCustomerAccounts
                ? transactionRepository.summarizeAllPostedTransactions(
                        com.ledgerline.enums.TransactionType.DEPOSIT,
                        false,
                        fromInclusive,
                        toExclusive)
                : transactionRepository.summarizePostedTransactionsByOwner(
                        principal.getUserId(),
                        com.ledgerline.enums.TransactionType.DEPOSIT,
                        false,
                        fromInclusive,
                        toExclusive);
        addMovementSummaries(summaries, deposits, true);

        List<CurrencyMovementSummary> withdrawals = allCustomerAccounts
                ? transactionRepository.summarizeAllPostedTransactions(
                        com.ledgerline.enums.TransactionType.WITHDRAWAL,
                        true,
                        fromInclusive,
                        toExclusive)
                : transactionRepository.summarizePostedTransactionsByOwner(
                        principal.getUserId(),
                        com.ledgerline.enums.TransactionType.WITHDRAWAL,
                        true,
                        fromInclusive,
                        toExclusive);
        addMovementSummaries(summaries, withdrawals, false);

        return List.copyOf(summaries.values());
    }

    private void addMovementSummaries(
            Map<String, CurrencyBalanceSummary> summaries,
            List<CurrencyMovementSummary> movements,
            boolean deposits) {
        for (CurrencyMovementSummary movement : movements) {
            CurrencyBalanceSummary current = summaries.getOrDefault(
                    movement.currency(),
                    new CurrencyBalanceSummary(
                            movement.currency(),
                            BigDecimal.ZERO.setScale(4),
                            0,
                            BigDecimal.ZERO.setScale(4),
                            0,
                            BigDecimal.ZERO.setScale(4),
                            0));
            summaries.put(movement.currency(), deposits
                    ? new CurrencyBalanceSummary(
                            movement.currency(),
                            current.totalBalance(),
                            current.accountCount(),
                            movement.totalAmount(),
                            movement.transactionCount(),
                            current.monthlyWithdrawals(),
                            current.monthlyWithdrawalCount())
                    : new CurrencyBalanceSummary(
                            movement.currency(),
                            current.totalBalance(),
                            current.accountCount(),
                            current.monthlyDeposits(),
                            current.monthlyDepositCount(),
                            movement.totalAmount(),
                            movement.transactionCount()));
        }
    }

    @Transactional(readOnly = true)
    public AccountSummary getAccount(Long accountId) {
        accountAuthorizationService.assertCanViewAccount(accountId);
        return toSummary(findAccount(accountId));
    }

    @Transactional(readOnly = true)
    public AccountBalanceResponse getAccountBalance(Long accountId) {
        accountAuthorizationService.assertCanViewAccount(accountId);
        AccountEntity account = findAccount(accountId);
        return new AccountBalanceResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getCurrency(),
                account.getBalance());
    }

    @Transactional
    public AccountSummary updateAccount(Long accountId, UpdateAccountRequest request) {
        accountAuthorizationService.assertCanManageAccount(accountId);
        AccountEntity account = findAccount(accountId);
        account.setName(request.name().trim());
        return toSummary(accountRepository.save(account));
    }

    @Transactional(readOnly = true)
    public List<AdminAccountSummary> getAllAccountsForAdmin() {
        assertRole(UserRole.ADMIN);
        return accountRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toAdminSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public AdminAccountSummary getAccountForAdmin(Long accountId) {
        assertRole(UserRole.ADMIN);
        return toAdminSummary(findAccount(accountId));
    }

    @Transactional(readOnly = true)
    public List<AdminAccountSummary> getAllAccountsForAuditor() {
        LedgerLinePrincipal principal = accountAuthorizationService.currentPrincipalOrThrow();
        if (principal.getRole() != UserRole.ADMIN && principal.getRole() != UserRole.AUDITOR) {
            throw new AccessDeniedException("Auditor or administrator access is required");
        }
        return accountRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(this::toAdminSummary)
                .toList();
    }

    @Transactional
    public AdminAccountSummary updateAccountAsAdmin(
            Long accountId,
            AdminUpdateAccountRequest request) {
        assertRole(UserRole.ADMIN);
        if (request.isEmpty()) {
            throw new InvalidAccountUpdateException();
        }
        AccountEntity account = findAccount(accountId);
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new InvalidAccountUpdateException();
            }
            account.setName(request.name().trim());
        }
        if (request.status() != null) {
            account.setStatus(request.status());
        }
        return toAdminSummary(accountRepository.save(account));
    }

    private String generateAvailableAccountNumber() {
        for (int attempt = 0; attempt < ACCOUNT_NUMBER_ATTEMPTS; attempt++) {
            String accountNumber = "LL" + UUID.randomUUID().toString().replace("-", "")
                    .toUpperCase(Locale.ROOT);
            if (!accountRepository.existsByAccountNumber(accountNumber)) {
                return accountNumber;
            }
        }
        throw new AccountNumberConflictException();
    }

    private AccountEntity findAccount(Long accountId) {
        return accountRepository.findById(accountId).orElseThrow(AccountNotFoundException::new);
    }

    private void assertRole(UserRole requiredRole) {
        if (accountAuthorizationService.currentPrincipalOrThrow().getRole() != requiredRole) {
            throw new AccessDeniedException("Administrator access is required");
        }
    }

    private AccountSummary toSummary(AccountEntity account) {
        return new AccountSummary(
                account.getId(),
                account.getAccountNumber(),
                account.getName(),
                account.getAccountType(),
                account.getCurrency(),
                account.getBalance(),
                account.getStatus());
    }

    private AdminAccountSummary toAdminSummary(AccountEntity account) {
        return new AdminAccountSummary(
                account.getId(),
                account.getAccountNumber(),
                account.getName(),
                account.getAccountType(),
                account.getCurrency(),
                account.getBalance(),
                account.getStatus(),
                account.getOwner() == null ? null : account.getOwner().getId(),
                account.getOwner() == null ? null : account.getOwner().getUsername(),
                account.getCreatedAt());
    }
}
