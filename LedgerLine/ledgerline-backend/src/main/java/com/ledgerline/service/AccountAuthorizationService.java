package com.ledgerline.service;

import com.ledgerline.enums.AccountRole;
import com.ledgerline.enums.UserRole;
import com.ledgerline.exception.AccountAccessDeniedException;
import com.ledgerline.repository.AccountPermissionRepository;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.security.LedgerLinePrincipal;
import java.util.List;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountAuthorizationService {

    private static final List<AccountRole> VIEW_ROLES =
            List.of(AccountRole.ACCOUNT_ADMIN, AccountRole.TRANSACT, AccountRole.VIEW);
    private static final List<AccountRole> TRANSACTION_ROLES =
            List.of(AccountRole.ACCOUNT_ADMIN, AccountRole.TRANSACT);

    private final AccountRepository accountRepository;
    private final AccountPermissionRepository permissionRepository;

    public AccountAuthorizationService(
            AccountRepository accountRepository,
            AccountPermissionRepository permissionRepository) {
        this.accountRepository = accountRepository;
        this.permissionRepository = permissionRepository;
    }

    @Transactional(readOnly = true)
    public void assertCanViewAccount(Long accountId) {
        LedgerLinePrincipal principal = currentPrincipal();
        if (principal.getRole() == UserRole.ADMIN || principal.getRole() == UserRole.AUDITOR) {
            return;
        }
        if (!accountRepository.existsByIdAndOwner_Id(accountId, principal.getUserId())
                && !permissionRepository.existsByAccount_IdAndUser_IdAndRoleIn(
                        accountId, principal.getUserId(), VIEW_ROLES)) {
            throw new AccountAccessDeniedException();
        }
    }

    @Transactional(readOnly = true)
    public void assertCanTransactOnAccount(Long accountId) {
        LedgerLinePrincipal principal = currentPrincipal();
        if (principal.getRole() != UserRole.ACCOUNT_HOLDER) {
            throw new AccessDeniedException("Only account holders can perform account transactions");
        }
        if (!accountRepository.existsByIdAndOwner_Id(accountId, principal.getUserId())
                && !permissionRepository.existsByAccount_IdAndUser_IdAndRoleIn(
                        accountId, principal.getUserId(), TRANSACTION_ROLES)) {
            throw new AccountAccessDeniedException();
        }
    }

    @Transactional(readOnly = true)
    public void assertCanManageAccount(Long accountId) {
        LedgerLinePrincipal principal = currentPrincipal();
        if (principal.getRole() == UserRole.ADMIN) {
            return;
        }
        if (principal.getRole() != UserRole.ACCOUNT_HOLDER
                || (!accountRepository.existsByIdAndOwner_Id(accountId, principal.getUserId())
                && !permissionRepository.existsByAccount_IdAndUser_IdAndRoleIn(
                        accountId, principal.getUserId(), List.of(AccountRole.ACCOUNT_ADMIN)))) {
            throw new AccountAccessDeniedException();
        }
    }

    public LedgerLinePrincipal currentPrincipalOrThrow() {
        return currentPrincipal();
    }

    private LedgerLinePrincipal currentPrincipal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof LedgerLinePrincipal principal)) {
            throw new AccessDeniedException("Authenticated user context is required");
        }
        return principal;
    }
}
