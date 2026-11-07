package com.ledgerline.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ledgerline.dto.AccountSummary;
import com.ledgerline.dto.CreateAccountRequest;
import com.ledgerline.dto.UpdateAccountRequest;
import com.ledgerline.entity.AccountEntity;
import com.ledgerline.entity.UserEntity;
import com.ledgerline.enums.AccountType;
import com.ledgerline.exception.AccountAccessDeniedException;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.TransactionRepository;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.LedgerLinePrincipal;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AccountManagementServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccountAuthorizationService accountAuthorizationService;

    @InjectMocks
    private AccountManagementService accountManagementService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void createAccountUsesAuthenticatedOwnerGeneratedNumberAndZeroBalance() {
        UserEntity owner = new UserEntity("holder", "holder@example.com", "hash", "Account Holder");
        ReflectionTestUtils.setField(owner, "id", 81L);
        authenticate(owner);
        when(accountAuthorizationService.currentPrincipalOrThrow())
                .thenReturn(LedgerLinePrincipal.from(owner));
        when(userRepository.findById(81L)).thenReturn(Optional.of(owner));
        when(accountRepository.existsByAccountNumber(any())).thenReturn(false);
        when(accountRepository.saveAndFlush(any(AccountEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        AccountSummary result = accountManagementService.createAccount(
                new CreateAccountRequest("Main account", AccountType.CHECKING, "usd"));

        ArgumentCaptor<AccountEntity> accountCaptor = ArgumentCaptor.forClass(AccountEntity.class);
        verify(accountRepository).saveAndFlush(accountCaptor.capture());
        AccountEntity saved = accountCaptor.getValue();
        assertEquals(owner, saved.getOwner());
        assertEquals("Main account", saved.getName());
        assertEquals("USD", saved.getCurrency());
        assertEquals(BigDecimal.ZERO.setScale(4), saved.getBalance());
        assertNotNull(saved.getAccountNumber());
        assertEquals(34, saved.getAccountNumber().length());
        assertEquals("LL", saved.getAccountNumber().substring(0, 2));
        assertEquals("USD", result.currency());
        assertTrueAccountNumberHasHexSuffix(saved.getAccountNumber());
        verify(accountAuthorizationService).currentPrincipalOrThrow();
    }

    @Test
    void updateChecksPermissionBeforeLoadingAccount() {
        doThrow(new AccountAccessDeniedException())
                .when(accountAuthorizationService)
                .assertCanManageAccount(19L);

        org.junit.jupiter.api.Assertions.assertThrows(
                AccountAccessDeniedException.class,
                () -> accountManagementService.updateAccount(19L, new UpdateAccountRequest("Renamed")));

        verify(accountAuthorizationService).assertCanManageAccount(19L);
        verifyNoInteractions(accountRepository, userRepository);
    }

    private void authenticate(UserEntity user) {
        LedgerLinePrincipal principal = LedgerLinePrincipal.from(user);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        principal,
                        null,
                        principal.getAuthorities()));
    }

    private void assertTrueAccountNumberHasHexSuffix(String accountNumber) {
        assertTrue(Pattern.matches("LL[0-9A-F]{32}", accountNumber));
    }
}
