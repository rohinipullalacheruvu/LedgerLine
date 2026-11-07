package com.ledgerline.repository;

import com.ledgerline.entity.LedgerEntryEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntryEntity, Long> {

    @Query("""
            select entry
            from LedgerEntryEntity entry
            join fetch entry.transaction transaction
            join fetch transaction.initiatedBy initiator
            left join fetch transaction.sourceAccount sourceAccount
            left join fetch transaction.destinationAccount destinationAccount
            where entry.account.id = :accountId
            order by coalesce(transaction.postedAt, transaction.createdAt), entry.id
            """)
    List<LedgerEntryEntity> findStatementEntriesByAccountId(@Param("accountId") Long accountId);
}
