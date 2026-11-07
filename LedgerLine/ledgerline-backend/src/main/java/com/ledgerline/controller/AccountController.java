package com.ledgerline.controller;

import com.ledgerline.dto.AccountBalanceResponse;
import com.ledgerline.dto.AccountSummary;
import com.ledgerline.dto.CreateAccountRequest;
import com.ledgerline.dto.CurrencyBalanceSummary;
import com.ledgerline.dto.FinancialTransactionResponse;
import com.ledgerline.dto.MoneyOperationRequest;
import com.ledgerline.dto.UpdateAccountRequest;
import com.ledgerline.enums.TransactionType;
import com.ledgerline.service.AccountManagementService;
import com.ledgerline.service.IdempotencyService;
import com.ledgerline.service.MoneyMovementService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountManagementService accountManagementService;
    private final MoneyMovementService moneyMovementService;
    private final IdempotencyService idempotencyService;

    public AccountController(
            AccountManagementService accountManagementService,
            MoneyMovementService moneyMovementService,
            IdempotencyService idempotencyService) {
        this.accountManagementService = accountManagementService;
        this.moneyMovementService = moneyMovementService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<AccountSummary> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(accountManagementService.createAccount(request));
    }

    @GetMapping
    public List<AccountSummary> getMyAccounts() {
        return accountManagementService.getMyAccounts();
    }

    @GetMapping("/summary")
    public List<CurrencyBalanceSummary> getPortfolioBalanceSummary() {
        return accountManagementService.getPortfolioBalanceSummary();
    }

    @GetMapping("/{accountId}")
    public AccountSummary getAccount(@PathVariable Long accountId) {
        return accountManagementService.getAccount(accountId);
    }

    @GetMapping("/{accountId}/balance")
    public AccountBalanceResponse getAccountBalance(@PathVariable Long accountId) {
        return accountManagementService.getAccountBalance(accountId);
    }

    @PatchMapping("/{accountId}")
    public AccountSummary updateAccount(
            @PathVariable Long accountId,
            @Valid @RequestBody UpdateAccountRequest request) {
        return accountManagementService.updateAccount(accountId, request);
    }

    @PostMapping("/{accountId}/deposits")
    public ResponseEntity<FinancialTransactionResponse> deposit(
            @PathVariable Long accountId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody MoneyOperationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(idempotencyService.executeMoneyOperation(
                        idempotencyKey,
                        accountId,
                        TransactionType.DEPOSIT,
                        request,
                        () -> moneyMovementService.deposit(accountId, request)));
    }

    @PostMapping("/{accountId}/withdrawals")
    public ResponseEntity<FinancialTransactionResponse> withdraw(
            @PathVariable Long accountId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody MoneyOperationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(idempotencyService.executeMoneyOperation(
                        idempotencyKey,
                        accountId,
                        TransactionType.WITHDRAWAL,
                        request,
                        () -> moneyMovementService.withdraw(accountId, request)));
    }
}
