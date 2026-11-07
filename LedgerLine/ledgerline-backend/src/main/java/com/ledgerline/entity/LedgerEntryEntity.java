package com.ledgerline.entity;

import com.ledgerline.enums.LedgerEntrySide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PreRemove;
import jakarta.persistence.PreUpdate;
import java.math.BigDecimal;

@Entity
@Table(name = "ledger_entries", indexes = {
        @Index(name = "ix_ledger_entries_transaction", columnList = "transaction_id"),
        @Index(name = "ix_ledger_entries_account_created", columnList = "account_id, created_at")
})
public class LedgerEntryEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private TransactionEntity transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private AccountEntity account;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_side", nullable = false, length = 10)
    private LedgerEntrySide entrySide;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Transient
    private boolean persisted;

    protected LedgerEntryEntity() {
    }

    public LedgerEntryEntity(
            TransactionEntity transaction,
            AccountEntity account,
            LedgerEntrySide entrySide,
            BigDecimal amount) {
        this.transaction = transaction;
        this.account = account;
        this.entrySide = entrySide;
        this.amount = amount;
    }

    @PostLoad
    @PostPersist
    private void markPersisted() {
        persisted = true;
    }

    @PreUpdate
    @PreRemove
    private void rejectMutation() {
        if (persisted) {
            throw new IllegalStateException("Ledger entries are append-only; post a reversing entry instead.");
        }
    }

    public TransactionEntity getTransaction() {
        return transaction;
    }

    public void setTransaction(TransactionEntity transaction) {
        this.transaction = transaction;
    }

    public AccountEntity getAccount() {
        return account;
    }

    public void setAccount(AccountEntity account) {
        this.account = account;
    }

    public LedgerEntrySide getEntrySide() {
        return entrySide;
    }

    public void setEntrySide(LedgerEntrySide entrySide) {
        this.entrySide = entrySide;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
