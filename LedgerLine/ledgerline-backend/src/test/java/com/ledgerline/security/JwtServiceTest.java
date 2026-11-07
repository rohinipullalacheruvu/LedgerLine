package com.ledgerline.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerline.entity.UserEntity;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "ledgerline-test-signing-key-32bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    @Test
    void generatedTokenCarriesSubjectAndRevocationVersion() {
        JwtService jwtService = new JwtService(SECRET, 60_000);
        UserEntity user = new UserEntity();
        user.setUsername("ledgeruser");
        user.setPasswordHash("not-used-by-token");
        user.setTokenVersion(7);
        LedgerLinePrincipal principal = LedgerLinePrincipal.from(user);

        String token = jwtService.generateToken(principal);

        assertEquals(
                new JwtService.JwtIdentity("ledgeruser", 7),
                jwtService.parseToken(token).orElseThrow());
    }

    @Test
    void malformedOrTamperedTokenIsRejected() {
        JwtService jwtService = new JwtService(SECRET, 60_000);

        assertTrue(jwtService.parseToken("not-a-jwt").isEmpty());
    }
}
