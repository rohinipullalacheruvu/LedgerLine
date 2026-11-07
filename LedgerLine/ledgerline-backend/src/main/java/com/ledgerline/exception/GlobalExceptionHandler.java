package com.ledgerline.exception;

import com.ledgerline.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        Map<String, String> validationErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(fieldError ->
                validationErrors.put(fieldError.getField(), fieldError.getDefaultMessage()));

        return createResponse(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                request,
                validationErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request) {
        Map<String, String> validationErrors = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation ->
                validationErrors.put(
                        violation.getPropertyPath().toString(),
                        violation.getMessage()));

        return createResponse(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                request,
                validationErrors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableMessage(
            HttpMessageNotReadableException exception,
            HttpServletRequest request) {
        return createResponse(
                HttpStatus.BAD_REQUEST,
                "Request body is missing or malformed",
                request,
                Map.of());
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ApiError> handleUserAlreadyExists(
            UserAlreadyExistsException exception,
            HttpServletRequest request) {
        return createResponse(HttpStatus.CONFLICT, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(InvalidPasswordException.class)
    public ResponseEntity<ApiError> handleInvalidPassword(
            InvalidPasswordException exception,
            HttpServletRequest request) {
        return createResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthenticationFailure(
            AuthenticationException exception,
            HttpServletRequest request) {
        return createResponse(
                HttpStatus.UNAUTHORIZED,
                "Invalid username/email or password",
                request,
                Map.of());
    }

    @ExceptionHandler(AccountAccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccountAccessDenied(
            AccountAccessDeniedException exception,
            HttpServletRequest request) {
        return createResponse(HttpStatus.FORBIDDEN, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ApiError> handleAccountNotFound(
            AccountNotFoundException exception,
            HttpServletRequest request) {
        return createResponse(HttpStatus.NOT_FOUND, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(AccountNumberConflictException.class)
    public ResponseEntity<ApiError> handleAccountNumberConflict(
            AccountNumberConflictException exception,
            HttpServletRequest request) {
        return createResponse(HttpStatus.CONFLICT, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(InvalidAccountUpdateException.class)
    public ResponseEntity<ApiError> handleInvalidAccountUpdate(
            InvalidAccountUpdateException exception,
            HttpServletRequest request) {
        return createResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(InsufficientBalanceException.class)
    public ResponseEntity<ApiError> handleInsufficientBalance(
            InsufficientBalanceException exception,
            HttpServletRequest request) {
        return createResponse(HttpStatus.CONFLICT, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(AccountNotActiveException.class)
    public ResponseEntity<ApiError> handleInactiveAccount(
            AccountNotActiveException exception,
            HttpServletRequest request) {
        return createResponse(HttpStatus.CONFLICT, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(InvalidStatementFilterException.class)
    public ResponseEntity<ApiError> handleInvalidStatementFilter(
            InvalidStatementFilterException exception,
            HttpServletRequest request) {
        return createResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(InvalidCustomerAccountTypeException.class)
    public ResponseEntity<ApiError> handleInvalidCustomerAccountType(
            InvalidCustomerAccountTypeException exception,
            HttpServletRequest request) {
        return createResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(ClearingAccountException.class)
    public ResponseEntity<ApiError> handleClearingAccountConfiguration(
            ClearingAccountException exception,
            HttpServletRequest request) {
        return createResponse(HttpStatus.SERVICE_UNAVAILABLE, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(IdempotencyKeyRequiredException.class)
    public ResponseEntity<ApiError> handleMissingIdempotencyKey(
            IdempotencyKeyRequiredException exception,
            HttpServletRequest request) {
        return createResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(
            IdempotencyConflictException exception,
            HttpServletRequest request) {
        return createResponse(HttpStatus.CONFLICT, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(IdempotencyFailureException.class)
    public ResponseEntity<ApiError> handleIdempotencyFailure(
            IdempotencyFailureException exception,
            HttpServletRequest request) {
        return createResponse(HttpStatus.CONFLICT, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(InvalidTransferException.class)
    public ResponseEntity<ApiError> handleInvalidTransfer(
            InvalidTransferException exception,
            HttpServletRequest request) {
        return createResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            AccessDeniedException exception,
            HttpServletRequest request) {
        return createResponse(
                HttpStatus.FORBIDDEN,
                "You are not allowed to perform this operation",
                request,
                Map.of());
    }

    private ResponseEntity<ApiError> createResponse(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            Map<String, String> validationErrors) {
        ApiError body = new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                validationErrors);
        return ResponseEntity.status(status).body(body);
    }
}
