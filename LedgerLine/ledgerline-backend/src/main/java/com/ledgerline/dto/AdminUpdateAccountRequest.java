package com.ledgerline.dto;

import com.ledgerline.enums.AccountStatus;
import jakarta.validation.constraints.Size;

public record AdminUpdateAccountRequest(
        @Size(max = 100) String name,
        AccountStatus status) {

    public boolean isEmpty() {
        return name == null && status == null;
    }
}
