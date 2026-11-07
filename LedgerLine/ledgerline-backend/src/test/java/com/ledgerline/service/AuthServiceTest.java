package com.ledgerline.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ledgerline.dto.AuthResponse;
import com.ledgerline.dto.LoginRequest;
import com.ledgerline.dto.RegisterRequest;
import com.ledgerline.entity.UserEntity;
import com.ledgerline.enums.UserRole;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.JwtService;
import com.ledgerline.security.LedgerLinePrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private JwtService jwtService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    @BeforeEach
    void setUp() {
        when(jwtService.generateToken(any(LedgerLinePrincipal.class))).thenReturn("signed.jwt.token");
        when(jwtService.getExpirationMillis()).thenReturn(900_000L);
    }

    @Test
    void registerHashesPasswordAndAlwaysAssignsAccountHolderRole() {
        RegisterRequest request = new RegisterRequest(
                "LedgerUser",
                "ledger@example.com",
                "long-and-unique-passphrase",
                "Ledger User");
        when(userRepository.existsByUsername("ledgeruser")).thenReturn(false);
        when(userRepository.existsByEmail("ledger@example.com")).thenReturn(false);
        when(passwordEncoder.encode(request.password())).thenReturn("$2a$12$hashed");
        when(userRepository.saveAndFlush(any(UserEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        UserEntity user = new UserEntity();
        user.setUsername("ledgeruser");
        user.setEmail("ledger@example.com");
        user.setFullName("Ledger User");
        user.setPasswordHash("$2a$12$hashed");
        user.setRole(UserRole.ACCOUNT_HOLDER);
        when(userRepository.findByUsername("ledgeruser")).thenReturn(java.util.Optional.of(user));

        AuthResponse response = authService.register(request);

        ArgumentCaptor<UserEntity> savedUser = ArgumentCaptor.forClass(UserEntity.class);
        verify(userRepository).saveAndFlush(savedUser.capture());
        assertEquals("ledgeruser", savedUser.getValue().getUsername());
        assertEquals("$2a$12$hashed", savedUser.getValue().getPasswordHash());
        assertEquals(UserRole.ACCOUNT_HOLDER, savedUser.getValue().getRole());
        assertEquals("signed.jwt.token", response.accessToken());
        assertEquals(UserRole.ACCOUNT_HOLDER, response.user().role());
        verify(passwordEncoder).encode(request.password());
    }

    @Test
    void loginAuthenticatesProvidedUsernameAndPassword() {
        UserEntity user = new UserEntity();
        user.setUsername("ledgeruser");
        user.setEmail("ledger@example.com");
        user.setFullName("Ledger User");
        user.setPasswordHash("$2a$12$hashed");
        LedgerLinePrincipal principal = LedgerLinePrincipal.from(user);
        Authentication authenticated = UsernamePasswordAuthenticationToken.authenticated(
                principal,
                null,
                principal.getAuthorities());
        when(authenticationManager.authenticate(any(Authentication.class))).thenReturn(authenticated);
        when(userRepository.findByUsername("ledgeruser")).thenReturn(java.util.Optional.of(user));

        AuthResponse response = authService.login(new LoginRequest("ledgeruser", "provided-password"));

        ArgumentCaptor<Authentication> authenticationCaptor = ArgumentCaptor.forClass(Authentication.class);
        verify(authenticationManager).authenticate(authenticationCaptor.capture());
        assertEquals("ledgeruser", authenticationCaptor.getValue().getPrincipal());
        assertEquals("provided-password", authenticationCaptor.getValue().getCredentials());
        assertNotNull(response.accessToken());
        assertEquals(900, response.expiresIn());
    }

}
