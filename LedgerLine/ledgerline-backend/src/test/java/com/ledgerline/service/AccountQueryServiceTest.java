package com.ledgerline.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.ledgerline.exception.AccountAccessDeniedException;
import com.ledgerline.repository.AccountRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountQueryServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountAuthorizationService accountAuthorizationService;

    @InjectMocks
    private AccountQueryService accountQueryService;

    @Test
    void accountAuthorizationIsCheckedBeforeLoadingAccount() {
        doThrow(new AccountAccessDeniedException())
                .when(accountAuthorizationService)
                .assertCanViewAccount(77L);

        assertThrows(
                AccountAccessDeniedException.class,
                () -> accountQueryService.getAccount(77L));
        verify(accountAuthorizationService).assertCanViewAccount(77L);
        verifyNoInteractions(accountRepository);
    }

    @Test
    void missingAuthorizedAccountReturnsNotFound() {
        when(accountRepository.findById(77L)).thenReturn(Optional.empty());

        assertThrows(
                com.ledgerline.exception.AccountNotFoundException.class,
                () -> accountQueryService.getAccount(77L));
        verify(accountAuthorizationService).assertCanViewAccount(77L);
        verify(accountRepository).findById(77L);
    }
}
