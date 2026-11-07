package com.ledgerline.service;

import com.ledgerline.dto.AuthResponse;
import com.ledgerline.dto.LoginRequest;
import com.ledgerline.dto.RegisterRequest;
import com.ledgerline.dto.UserSummary;
import com.ledgerline.entity.UserEntity;
import com.ledgerline.exception.InvalidPasswordException;
import com.ledgerline.exception.UserAlreadyExistsException;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.JwtService;
import com.ledgerline.security.LedgerLinePrincipal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public AuthService(
            UserRepository userRepository,
            AuthenticationManager authenticationManager,
            JwtService jwtService,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidPasswordException();
        }
        if (userRepository.existsByUsername(username) || userRepository.existsByEmail(email)) {
            throw new UserAlreadyExistsException();
        }

        UserEntity user = new UserEntity(
                username,
                email,
                passwordEncoder.encode(request.password()),
                request.fullName().trim());
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw new UserAlreadyExistsException();
        }

        return createAuthResponse(LedgerLinePrincipal.from(user));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        if (request.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new InvalidPasswordException();
        }
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(
                        request.usernameOrEmail().trim(),
                        request.password()));
        LedgerLinePrincipal principal = (LedgerLinePrincipal) authentication.getPrincipal();
        return createAuthResponse(principal);
    }

    @Transactional(readOnly = true)
    public UserSummary getCurrentUser(LedgerLinePrincipal principal) {
        UserEntity user = userRepository.findById(principal.getUserId())
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
        return new UserSummary(user.getUsername(), user.getEmail(), user.getFullName(), user.getRole());
    }

    private AuthResponse createAuthResponse(LedgerLinePrincipal principal) {
        UserEntity user = userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new IllegalStateException("Authenticated user no longer exists"));
        return new AuthResponse(
                jwtService.generateToken(principal),
                "Bearer",
                jwtService.getExpirationMillis() / 1000,
                new UserSummary(user.getUsername(), user.getEmail(), user.getFullName(), user.getRole()));
    }
}
