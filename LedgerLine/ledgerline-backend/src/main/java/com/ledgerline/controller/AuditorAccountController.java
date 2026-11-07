package com.ledgerline.controller;

import com.ledgerline.dto.AdminAccountSummary;
import com.ledgerline.service.AccountManagementService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auditor/accounts")
public class AuditorAccountController {

    private final AccountManagementService accountManagementService;

    public AuditorAccountController(AccountManagementService accountManagementService) {
        this.accountManagementService = accountManagementService;
    }

    @GetMapping
    public List<AdminAccountSummary> getAllAccounts() {
        return accountManagementService.getAllAccountsForAuditor();
    }
}
