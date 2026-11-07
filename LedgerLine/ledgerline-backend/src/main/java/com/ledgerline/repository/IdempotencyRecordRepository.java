package com.ledgerline.repository;

import com.ledgerline.entity.IdempotencyRecordEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, Long> {

    @EntityGraph(attributePaths = {
            "transaction",
            "transaction.sourceAccount",
            "transaction.destinationAccount"
    })
    @Query("""
            select record from IdempotencyRecordEntity record
            where record.user.id = :userId and record.idempotencyKey = :key
            """)
    Optional<IdempotencyRecordEntity> findByUserIdAndKey(
            @Param("userId") Long userId,
            @Param("key") String key);
}
