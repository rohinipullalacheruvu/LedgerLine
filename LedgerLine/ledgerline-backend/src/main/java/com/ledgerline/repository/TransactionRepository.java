package com.ledgerline.repository;

import com.ledgerline.dto.CurrencyMovementSummary;
import com.ledgerline.entity.TransactionEntity;
import com.ledgerline.enums.TransactionType;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<TransactionEntity, Long> {

    @Query("""
            select new com.ledgerline.dto.CurrencyMovementSummary(
                transaction.currency, sum(transaction.amount), count(transaction))
            from TransactionEntity transaction
            where transaction.status = com.ledgerline.enums.TransactionStatus.POSTED
                and transaction.transactionType = :transactionType
                and transaction.postedAt >= :fromInclusive
                and transaction.postedAt < :toExclusive
                and (
                    (:sourceSide = true and transaction.sourceAccount.owner.id = :ownerId)
                    or (:sourceSide = false and transaction.destinationAccount.owner.id = :ownerId)
                )
            group by transaction.currency
            order by transaction.currency
            """)
    List<CurrencyMovementSummary> summarizePostedTransactionsByOwner(
            @Param("ownerId") Long ownerId,
            @Param("transactionType") TransactionType transactionType,
            @Param("sourceSide") boolean sourceSide,
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toExclusive") Instant toExclusive);

    @Query("""
            select new com.ledgerline.dto.CurrencyMovementSummary(
                transaction.currency, sum(transaction.amount), count(transaction))
            from TransactionEntity transaction
            where transaction.status = com.ledgerline.enums.TransactionStatus.POSTED
                and transaction.transactionType = :transactionType
                and transaction.postedAt >= :fromInclusive
                and transaction.postedAt < :toExclusive
                and (
                    (:sourceSide = true and transaction.sourceAccount.owner is not null)
                    or (:sourceSide = false and transaction.destinationAccount.owner is not null)
                )
            group by transaction.currency
            order by transaction.currency
            """)
    List<CurrencyMovementSummary> summarizeAllPostedTransactions(
            @Param("transactionType") TransactionType transactionType,
            @Param("sourceSide") boolean sourceSide,
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toExclusive") Instant toExclusive);
}
