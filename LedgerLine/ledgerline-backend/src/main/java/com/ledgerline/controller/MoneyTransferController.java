package com.ledgerline.controller;

import com.ledgerline.dto.TransferRequest;
import com.ledgerline.dto.TransferResponse;
import com.ledgerline.service.IdempotencyService;
import com.ledgerline.service.MoneyTransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
public class MoneyTransferController {

    private final MoneyTransferService moneyTransferService;
    private final IdempotencyService idempotencyService;

    public MoneyTransferController(
            MoneyTransferService moneyTransferService,
            IdempotencyService idempotencyService) {
        this.moneyTransferService = moneyTransferService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                idempotencyService.executeTransfer(
                        idempotencyKey,
                        request,
                        () -> moneyTransferService.transfer(request)));
    }
}
