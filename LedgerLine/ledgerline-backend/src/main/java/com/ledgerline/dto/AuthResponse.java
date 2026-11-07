package com.ledgerline.dto;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserSummary user) {
}
