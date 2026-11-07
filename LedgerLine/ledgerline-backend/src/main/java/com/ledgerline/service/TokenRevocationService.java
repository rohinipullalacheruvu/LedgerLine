package com.ledgerline.service;

import com.ledgerline.entity.UserEntity;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.LedgerLinePrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TokenRevocationService {

    private final UserRepository userRepository;

    public TokenRevocationService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional
    public void revokeAllUserTokens(LedgerLinePrincipal principal) {
        UserEntity user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
        user.setTokenVersion(user.getTokenVersion() + 1);
        userRepository.save(user);
    }
}
