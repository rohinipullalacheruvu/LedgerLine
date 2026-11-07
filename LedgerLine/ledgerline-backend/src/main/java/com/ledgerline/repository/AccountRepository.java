package com.ledgerline.repository;

import com.ledgerline.entity.AccountEntity;
import com.ledgerline.dto.CurrencyBalance;
import com.ledgerline.enums.AccountType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<AccountEntity, Long> {

    boolean existsByIdAndOwner_Id(Long accountId, Long ownerId);

    boolean existsByAccountNumber(String accountNumber);

    Optional<AccountEntity> findByAccountNumber(String accountNumber);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select account from AccountEntity account
            where account.accountNumber in :accountNumbers
            order by account.id
            """)
    List<AccountEntity> findAllByAccountNumbersForUpdate(
            @Param("accountNumbers") List<String> accountNumbers);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select account from AccountEntity account
            where account.id = :accountId or account.accountNumber = :clearingAccountNumber
            order by account.id
            """)
    List<AccountEntity> findAccountAndClearingAccountForUpdate(
            @Param("accountId") Long accountId,
            @Param("clearingAccountNumber") String clearingAccountNumber);

    @EntityGraph(attributePaths = "owner")
    List<AccountEntity> findAllByOwner_IdOrderByCreatedAtDesc(Long ownerId);

    @EntityGraph(attributePaths = "owner")
    List<AccountEntity> findAllByOrderByCreatedAtDesc();

    @Query("""
            select new com.ledgerline.dto.CurrencyBalance(
                account.currency, sum(account.balance), count(account))
            from AccountEntity account
            where account.owner.id = :ownerId
                and account.accountType <> :systemAccountType
            group by account.currency
            order by account.currency
            """)
    List<CurrencyBalance> summarizeCustomerBalancesByOwner(
            @Param("ownerId") Long ownerId,
            @Param("systemAccountType") AccountType systemAccountType);

    @Query("""
            select new com.ledgerline.dto.CurrencyBalance(
                account.currency, sum(account.balance), count(account))
            from AccountEntity account
            where account.owner is not null
                and account.accountType <> :systemAccountType
            group by account.currency
            order by account.currency
            """)
    List<CurrencyBalance> summarizeAllCustomerBalances(
            @Param("systemAccountType") AccountType systemAccountType);
}
