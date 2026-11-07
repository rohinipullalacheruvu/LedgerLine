package com.ledgerline.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ledgerline.entity.UserEntity;
import com.ledgerline.enums.UserRole;
import com.ledgerline.exception.AccountAccessDeniedException;
import com.ledgerline.repository.AccountPermissionRepository;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.security.LedgerLinePrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AccountAuthorizationServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountPermissionRepository permissionRepository;

    @InjectMocks
    private AccountAuthorizationService authorizationService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void accountHolderCannotViewAnotherUsersAccount() {
        authenticate(41L, UserRole.ACCOUNT_HOLDER);
        when(accountRepository.existsByIdAndOwner_Id(7L, 41L)).thenReturn(false);
        when(permissionRepository.existsByAccount_IdAndUser_IdAndRoleIn(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(false);

        assertThrows(
                AccountAccessDeniedException.class,
                () -> authorizationService.assertCanViewAccount(7L));
        verify(accountRepository).existsByIdAndOwner_Id(7L, 41L);
        verify(permissionRepository).existsByAccount_IdAndUser_IdAndRoleIn(
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(41L),
                org.mockito.ArgumentMatchers.anyCollection());
    }

    @Test
    void auditorCanViewAccountsButCannotTransact() {
        authenticate(12L, UserRole.AUDITOR);

        authorizationService.assertCanViewAccount(7L);

        assertThrows(
                org.springframework.security.access.AccessDeniedException.class,
                () -> authorizationService.assertCanTransactOnAccount(7L));
        verifyNoInteractions(accountRepository, permissionRepository);
    }

    private void authenticate(Long userId, UserRole role) {
        UserEntity user = new UserEntity();
        user.setUsername("test-user");
        user.setPasswordHash("not-used");
        user.setRole(role);
        ReflectionTestUtils.setField(user, "id", userId);
        LedgerLinePrincipal principal = LedgerLinePrincipal.from(user);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        principal.getAuthorities()));
    }
}
