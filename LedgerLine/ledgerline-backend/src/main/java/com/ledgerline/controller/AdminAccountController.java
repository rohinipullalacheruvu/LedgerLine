package com.ledgerline.controller;

import com.ledgerline.dto.AdminAccountSummary;
import com.ledgerline.dto.AdminUpdateAccountRequest;
import com.ledgerline.service.AccountManagementService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/accounts")
public class AdminAccountController {

    private final AccountManagementService accountManagementService;

    public AdminAccountController(AccountManagementService accountManagementService) {
        this.accountManagementService = accountManagementService;
    }

    @GetMapping
    public List<AdminAccountSummary> getAllAccounts() {
        return accountManagementService.getAllAccountsForAdmin();
    }

    @GetMapping("/{accountId}")
    public AdminAccountSummary getAccount(@PathVariable Long accountId) {
        return accountManagementService.getAccountForAdmin(accountId);
    }

    @PatchMapping("/{accountId}")
    public AdminAccountSummary updateAccount(
            @PathVariable Long accountId,
            @Valid @RequestBody AdminUpdateAccountRequest request) {
        return accountManagementService.updateAccountAsAdmin(accountId, request);
    }
}
