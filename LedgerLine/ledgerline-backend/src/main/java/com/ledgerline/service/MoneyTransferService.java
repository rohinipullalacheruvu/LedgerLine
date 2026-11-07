package com.ledgerline.service;

import com.ledgerline.dto.TransferRequest;
import com.ledgerline.dto.TransferResponse;
import com.ledgerline.entity.AccountEntity;
import com.ledgerline.entity.LedgerEntryEntity;
import com.ledgerline.entity.TransactionEntity;
import com.ledgerline.entity.UserEntity;
import com.ledgerline.enums.AccountType;
import com.ledgerline.enums.AccountStatus;
import com.ledgerline.enums.LedgerEntrySide;
import com.ledgerline.enums.TransactionType;
import com.ledgerline.exception.AccountNotActiveException;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.exception.InsufficientBalanceException;
import com.ledgerline.exception.InvalidTransferException;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.LedgerEntryRepository;
import com.ledgerline.repository.TransactionRepository;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.LedgerLinePrincipal;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MoneyTransferService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final UserRepository userRepository;
    private final AccountAuthorizationService accountAuthorizationService;
    private final RefusedOperationAuditService refusedOperationAuditService;

    public MoneyTransferService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            UserRepository userRepository,
            AccountAuthorizationService accountAuthorizationService,
            RefusedOperationAuditService refusedOperationAuditService) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.userRepository = userRepository;
        this.accountAuthorizationService = accountAuthorizationService;
        this.refusedOperationAuditService = refusedOperationAuditService;
    }

    @Transactional(noRollbackFor = {AccountNotActiveException.class, InsufficientBalanceException.class})
    public TransferResponse transfer(TransferRequest request) {
        String senderNumber = request.senderAccountNumber().trim();
        String receiverNumber = request.receiverAccountNumber().trim();
        if (senderNumber.equals(receiverNumber)) {
            throw new InvalidTransferException("Sender and receiver accounts must be different");
        }

        AccountEntity senderCandidate = accountRepository.findByAccountNumber(senderNumber)
                .orElseThrow(AccountNotFoundException::new);
        LedgerLinePrincipal principal = accountAuthorizationService.currentPrincipalOrThrow();
        accountAuthorizationService.assertCanTransactOnAccount(senderCandidate.getId());

        List<AccountEntity> lockedAccounts = accountRepository.findAllByAccountNumbersForUpdate(
                List.of(senderNumber, receiverNumber));
        AccountEntity sender = lockedAccounts.stream()
                .filter(account -> account.getAccountNumber().equals(senderNumber))
                .findFirst()
                .orElseThrow(AccountNotFoundException::new);
        AccountEntity receiver = lockedAccounts.stream()
                .filter(account -> account.getAccountNumber().equals(receiverNumber))
                .findFirst()
                .orElseThrow(AccountNotFoundException::new);

        if (sender.getAccountType() == AccountType.EXTERNAL_CLEARING
                || receiver.getAccountType() == AccountType.EXTERNAL_CLEARING) {
            throw new InvalidTransferException("Transfers require customer accounts");
        }
        if (!sender.getCurrency().equalsIgnoreCase(receiver.getCurrency())) {
            throw new InvalidTransferException("Sender and receiver accounts must use the same currency");
        }
        if (sender.getStatus() != AccountStatus.ACTIVE || receiver.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException();
        }

        if (sender.getBalance().compareTo(request.amount()) < 0) {
            refusedOperationAuditService.recordRefusedAttempt(
                    principal,
                    TransactionType.TRANSFER.name(),
                    sender.getId(),
                    request.amount(),
                    sender.getCurrency(),
                    "Insufficient balance");
            throw new InsufficientBalanceException(request.amount(), sender.getBalance());
        }

        UserEntity initiator = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
        Instant postedAt = Instant.now();
        TransactionEntity transaction = transactionRepository.saveAndFlush(new TransactionEntity(
                initiator,
                sender,
                receiver,
                TransactionType.TRANSFER,
                request.amount(),
                sender.getCurrency(),
                request.description() == null || request.description().isBlank()
                        ? null
                        : request.description().trim(),
                postedAt));

        ledgerEntryRepository.saveAllAndFlush(List.of(
                new LedgerEntryEntity(transaction, sender, LedgerEntrySide.DEBIT, request.amount()),
                new LedgerEntryEntity(transaction, receiver, LedgerEntrySide.CREDIT, request.amount())));

        sender.setBalance(sender.getBalance().subtract(request.amount()));
        receiver.setBalance(receiver.getBalance().add(request.amount()));
        accountRepository.saveAllAndFlush(List.of(sender, receiver));

        return new TransferResponse(
                "LLTX-" + transaction.getId(),
                sender.getAccountNumber(),
                receiver.getAccountNumber(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                transaction.getCreatedAt(),
                transaction.getPostedAt());
    }
}
