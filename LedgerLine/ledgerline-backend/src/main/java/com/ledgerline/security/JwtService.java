package com.ledgerline.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private static final String TOKEN_VERSION_CLAIM = "ver";

    private final SecretKey signingKey;
    private final long expirationMillis;

    public JwtService(
            @Value("${app.jwt.secret}") String base64Secret,
            @Value("${app.jwt.expiration-ms}") long expirationMillis) {
        byte[] keyBytes = Decoders.BASE64.decode(base64Secret);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        if (expirationMillis <= 0) {
            throw new IllegalArgumentException("JWT expiration must be a positive number of milliseconds");
        }
        this.expirationMillis = expirationMillis;
    }

    public String generateToken(LedgerLinePrincipal principal) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plusMillis(expirationMillis);

        return Jwts.builder()
                .subject(principal.getUsername())
                .claim(TOKEN_VERSION_CLAIM, principal.getTokenVersion())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    public Optional<JwtIdentity> parseToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Object versionClaim = claims.get(TOKEN_VERSION_CLAIM);
            if (claims.getSubject() == null || !(versionClaim instanceof Number version)) {
                return Optional.empty();
            }
            return Optional.of(new JwtIdentity(claims.getSubject(), version.longValue()));
        } catch (JwtException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public long getExpirationMillis() {
        return expirationMillis;
    }

    public record JwtIdentity(String username, long tokenVersion) {
    }
}
