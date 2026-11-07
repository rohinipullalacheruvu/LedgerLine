package com.ledgerline.entity;

import com.ledgerline.enums.TransactionStatus;
import com.ledgerline.enums.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "transactions", indexes = {
        @Index(name = "ix_transactions_initiator_created", columnList = "initiated_by_user_id, created_at"),
        @Index(name = "ix_transactions_source_created", columnList = "source_account_id, created_at"),
        @Index(name = "ix_transactions_destination_created", columnList = "destination_account_id, created_at")
})
public class TransactionEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiated_by_user_id", nullable = false)
    private UserEntity initiatedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_account_id")
    private AccountEntity sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_account_id")
    private AccountEntity destinationAccount;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20)
    private TransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TransactionStatus status = TransactionStatus.PENDING;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(length = 500)
    private String description;

    @Column(name = "posted_at")
    private Instant postedAt;

    protected TransactionEntity() {
    }

    public TransactionEntity(
            UserEntity initiatedBy,
            AccountEntity sourceAccount,
            AccountEntity destinationAccount,
            TransactionType transactionType,
            BigDecimal amount,
            String currency,
            String description,
            Instant postedAt) {
        this.initiatedBy = initiatedBy;
        this.sourceAccount = sourceAccount;
        this.destinationAccount = destinationAccount;
        this.transactionType = transactionType;
        this.status = TransactionStatus.POSTED;
        this.amount = amount;
        this.currency = currency;
        this.description = description;
        this.postedAt = postedAt;
    }

    public UserEntity getInitiatedBy() {
        return initiatedBy;
    }

    public void setInitiatedBy(UserEntity initiatedBy) {
        this.initiatedBy = initiatedBy;
    }

    public AccountEntity getSourceAccount() {
        return sourceAccount;
    }

    public void setSourceAccount(AccountEntity sourceAccount) {
        this.sourceAccount = sourceAccount;
    }

    public AccountEntity getDestinationAccount() {
        return destinationAccount;
    }

    public void setDestinationAccount(AccountEntity destinationAccount) {
        this.destinationAccount = destinationAccount;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(TransactionType transactionType) {
        this.transactionType = transactionType;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(TransactionStatus status) {
        this.status = status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public void setPostedAt(Instant postedAt) {
        this.postedAt = postedAt;
    }
}
