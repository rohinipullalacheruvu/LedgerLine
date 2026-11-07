package com.ledgerline.service;

import com.ledgerline.entity.AuditLogEntity;
import com.ledgerline.entity.UserEntity;
import com.ledgerline.repository.AuditLogRepository;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.LedgerLinePrincipal;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefusedOperationAuditService {

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;

    public RefusedOperationAuditService(
            AuditLogRepository auditLogRepository,
            UserRepository userRepository) {
        this.auditLogRepository = auditLogRepository;
        this.userRepository = userRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRefusedAttempt(
            LedgerLinePrincipal principal,
            String operation,
            Long accountId,
            BigDecimal amount,
            String currency,
            String reason) {
        UserEntity actor = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
        Map<String, String> details = new LinkedHashMap<>();
        details.put("operation", operation);
        details.put("amount", amount.toPlainString());
        details.put("currency", currency);
        details.put("reason", reason);

        auditLogRepository.saveAndFlush(new AuditLogEntity(
                actor,
                operation + "_REFUSED",
                "ACCOUNT",
                accountId,
                details));
    }
}
