package com.ledgerline.security;

import com.ledgerline.repository.UserRepository;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerLineUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    public LedgerLineUserDetailsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String usernameOrEmail) throws UsernameNotFoundException {
        return userRepository.findByUsernameOrEmail(usernameOrEmail, usernameOrEmail)
                .map(LedgerLinePrincipal::from)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }
}
