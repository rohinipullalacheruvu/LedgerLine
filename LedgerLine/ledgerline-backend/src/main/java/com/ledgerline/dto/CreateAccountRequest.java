package com.ledgerline.dto;

import com.ledgerline.enums.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull AccountType accountType,
        @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency) {
}
