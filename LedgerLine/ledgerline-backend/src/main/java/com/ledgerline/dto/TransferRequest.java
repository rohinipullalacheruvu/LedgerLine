package com.ledgerline.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record TransferRequest(
        @NotBlank
        @Size(max = 34)
        String senderAccountNumber,
        @NotBlank
        @Size(max = 34)
        String receiverAccountNumber,
        @NotNull
        @DecimalMin(value = "0.0000", inclusive = false)
        @Digits(integer = 15, fraction = 4)
        BigDecimal amount,
        @Size(max = 500)
        String description) {
}
