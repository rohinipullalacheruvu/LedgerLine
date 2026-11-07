package com.ledgerline.repository;

import com.ledgerline.entity.AccountPermissionEntity;
import com.ledgerline.enums.AccountRole;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountPermissionRepository extends JpaRepository<AccountPermissionEntity, Long> {

    boolean existsByAccount_IdAndUser_IdAndRoleIn(
            Long accountId,
            Long userId,
            Collection<AccountRole> roles);
}
