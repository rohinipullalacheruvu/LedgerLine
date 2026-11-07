package com.ledgerline.dto;

import com.ledgerline.enums.UserRole;

public record UserSummary(
        String username,
        String email,
        String fullName,
        UserRole role) {
}
