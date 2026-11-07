package com.ledgerline.service;

import com.ledgerline.dto.FinancialTransactionResponse;
import com.ledgerline.dto.MoneyOperationRequest;
import com.ledgerline.dto.TransferRequest;
import com.ledgerline.dto.TransferResponse;
import com.ledgerline.entity.IdempotencyRecordEntity;
import com.ledgerline.entity.TransactionEntity;
import com.ledgerline.entity.UserEntity;
import com.ledgerline.enums.IdempotencyStatus;
import com.ledgerline.enums.TransactionType;
import com.ledgerline.exception.AccountNotActiveException;
import com.ledgerline.exception.IdempotencyConflictException;
import com.ledgerline.exception.IdempotencyFailureException;
import com.ledgerline.exception.IdempotencyKeyRequiredException;
import com.ledgerline.exception.InsufficientBalanceException;
import com.ledgerline.repository.IdempotencyRecordRepository;
import com.ledgerline.repository.TransactionRepository;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.LedgerLinePrincipal;
import java.math.BigDecimal;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IdempotencyService {

    private static final Pattern VALID_KEY = Pattern.compile("[\\x21-\\x7E]{1,128}");

    private final IdempotencyRecordRepository idempotencyRecordRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final AccountAuthorizationService accountAuthorizationService;
    private final TransactionTemplate transactionTemplate;

    public IdempotencyService(
            IdempotencyRecordRepository idempotencyRecordRepository,
            TransactionRepository transactionRepository,
            UserRepository userRepository,
            AccountAuthorizationService accountAuthorizationService,
            PlatformTransactionManager transactionManager) {
        this.idempotencyRecordRepository = idempotencyRecordRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.accountAuthorizationService = accountAuthorizationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public FinancialTransactionResponse executeMoneyOperation(
            String key,
            Long accountId,
            TransactionType operation,
            MoneyOperationRequest request,
            Supplier<FinancialTransactionResponse> action) {
        LedgerLinePrincipal principal = accountAuthorizationService.currentPrincipalOrThrow();
        String hash = IdempotencyRequestHasher.sha256(
                operation.name(),
                accountId.toString(),
                canonicalAmount(request.amount()),
                canonicalDescription(request.description()));
        return execute(
                principal,
                key,
                hash,
                FinancialTransactionResponse::transactionId,
                this::toFinancialResponse,
                action);
    }

    public TransferResponse executeTransfer(
            String key,
            TransferRequest request,
            Supplier<TransferResponse> action) {
        LedgerLinePrincipal principal = accountAuthorizationService.currentPrincipalOrThrow();
        String hash = IdempotencyRequestHasher.sha256(
                TransactionType.TRANSFER.name(),
                request.senderAccountNumber().trim(),
                request.receiverAccountNumber().trim(),
                canonicalAmount(request.amount()),
                canonicalDescription(request.description()));
        return execute(
                principal,
                key,
                hash,
                response -> transactionIdFromReference(response.transactionReference()),
                this::toTransferResponse,
                action);
    }

    private <T> T execute(
            LedgerLinePrincipal principal,
            String rawKey,
            String requestHash,
            Function<T, Long> transactionId,
            Function<TransactionEntity, T> replayResponse,
            Supplier<T> action) {
        String key = validateKey(rawKey);
        try {
            ExecutionOutcome<T> outcome = transactionTemplate.execute(status -> {
                UserEntity user = userRepository.findById(principal.getUserId())
                        .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
                IdempotencyRecordEntity record =
                        idempotencyRecordRepository.saveAndFlush(new IdempotencyRecordEntity(
                                user,
                                key,
                                requestHash));

                try {
                    T response = action.get();
                    TransactionEntity transaction = transactionRepository.findById(transactionId.apply(response))
                            .orElseThrow(() -> new IllegalStateException(
                                    "Money operation completed without a persisted transaction"));
                    record.setTransaction(transaction);
                    record.setStatus(IdempotencyStatus.COMPLETED);
                    idempotencyRecordRepository.saveAndFlush(record);
                    return ExecutionOutcome.success(response);
                } catch (InsufficientBalanceException exception) {
                    return persistFailure(record, "INSUFFICIENT_BALANCE", exception.getMessage());
                } catch (AccountNotActiveException exception) {
                    return persistFailure(record, "ACCOUNT_NOT_ACTIVE", exception.getMessage());
                }
            });
            if (outcome.failureCode() != null) {
                throw new IdempotencyFailureException(outcome.failureCode(), outcome.failureMessage());
            }
            return outcome.response();
        } catch (DataIntegrityViolationException exception) {
            IdempotencyRecordEntity existing = idempotencyRecordRepository
                    .findByUserIdAndKey(principal.getUserId(), key)
                    .orElse(null);
            if (existing == null) {
                throw exception;
            }
            if (!existing.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException(
                        "Idempotency-Key has already been used with different request details");
            }
            if (existing.getStatus() == IdempotencyStatus.FAILED
                    && existing.getFailureCode() != null
                    && existing.getFailureMessage() != null) {
                throw new IdempotencyFailureException(existing.getFailureCode(), existing.getFailureMessage());
            }
            if (existing.getStatus() != IdempotencyStatus.COMPLETED || existing.getTransaction() == null) {
                throw new IdempotencyConflictException(
                        "The request associated with this Idempotency-Key is not complete");
            }
            return replayResponse.apply(existing.getTransaction());
        }
    }

    private <T> ExecutionOutcome<T> persistFailure(
            IdempotencyRecordEntity record,
            String failureCode,
            String failureMessage) {
        record.setStatus(IdempotencyStatus.FAILED);
        record.setFailureCode(failureCode);
        record.setFailureMessage(failureMessage);
        idempotencyRecordRepository.saveAndFlush(record);
        return ExecutionOutcome.failure(failureCode, failureMessage);
    }

    private record ExecutionOutcome<T>(T response, String failureCode, String failureMessage) {

        private static <T> ExecutionOutcome<T> success(T response) {
            return new ExecutionOutcome<>(response, null, null);
        }

        private static <T> ExecutionOutcome<T> failure(String code, String message) {
            return new ExecutionOutcome<>(null, code, message);
        }
    }

    private String validateKey(String key) {
        if (key == null || !VALID_KEY.matcher(key).matches()) {
            throw new IdempotencyKeyRequiredException();
        }
        return key;
    }

    private String canonicalAmount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private String canonicalDescription(String description) {
        return description == null ? "" : description.trim();
    }

    private Long transactionIdFromReference(String reference) {
        String prefix = "LLTX-";
        if (reference == null || !reference.startsWith(prefix)) {
            throw new IllegalStateException("Transfer response contains an invalid transaction reference");
        }
        try {
            return Long.parseLong(reference.substring(prefix.length()));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("Transfer response contains an invalid transaction reference", exception);
        }
    }

    private FinancialTransactionResponse toFinancialResponse(TransactionEntity transaction) {
        boolean deposit = transaction.getTransactionType() == TransactionType.DEPOSIT;
        var account = deposit ? transaction.getDestinationAccount() : transaction.getSourceAccount();
        return new FinancialTransactionResponse(
                transaction.getId(),
                account.getId(),
                account.getAccountNumber(),
                transaction.getTransactionType(),
                transaction.getStatus(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getCreatedAt(),
                transaction.getPostedAt());
    }

    private TransferResponse toTransferResponse(TransactionEntity transaction) {
        return new TransferResponse(
                "LLTX-" + transaction.getId(),
                transaction.getSourceAccount().getAccountNumber(),
                transaction.getDestinationAccount().getAccountNumber(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getStatus(),
                transaction.getCreatedAt(),
                transaction.getPostedAt());
    }
}
