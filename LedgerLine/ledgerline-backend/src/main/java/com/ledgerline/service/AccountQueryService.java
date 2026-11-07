package com.ledgerline.service;

import com.ledgerline.dto.AccountSummary;
import com.ledgerline.entity.AccountEntity;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.repository.AccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountQueryService {

    private final AccountRepository accountRepository;
    private final AccountAuthorizationService accountAuthorizationService;

    public AccountQueryService(
            AccountRepository accountRepository,
            AccountAuthorizationService accountAuthorizationService) {
        this.accountRepository = accountRepository;
        this.accountAuthorizationService = accountAuthorizationService;
    }

    @Transactional(readOnly = true)
    public AccountSummary getAccount(Long accountId) {
        accountAuthorizationService.assertCanViewAccount(accountId);
        AccountEntity account = accountRepository.findById(accountId)
                .orElseThrow(AccountNotFoundException::new);
        return new AccountSummary(
                account.getId(),
                account.getAccountNumber(),
                account.getName(),
                account.getAccountType(),
                account.getCurrency(),
                account.getBalance(),
                account.getStatus());
    }
}
