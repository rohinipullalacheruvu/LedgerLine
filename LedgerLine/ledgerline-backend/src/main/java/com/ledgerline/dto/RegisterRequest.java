package com.ledgerline.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Size(min = 3, max = 50)
        @Pattern(regexp = "^[A-Za-z0-9._-]+$")
        String username,
        @NotBlank
        @Email
        @Size(max = 254)
        String email,
        @NotBlank
        @Size(min = 12, max = 72)
        String password,
        @NotBlank
        @Size(max = 120)
        String fullName) {
}
