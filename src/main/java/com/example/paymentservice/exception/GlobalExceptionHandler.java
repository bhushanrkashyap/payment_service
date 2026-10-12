package com.example.paymentservice.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(InvalidPaymentRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidPaymentRequestException ex) {
        return ResponseEntity.badRequest().body(new ErrorResponse(
                "Invalid payment request", "INVALID_PAYMENT_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(IdempotencyConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
                "Idempotency conflict", "IDEMPOTENCY_CONFLICT", ex.getMessage()));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ErrorResponse> handlePersistenceUnavailable(RuntimeException ex) {
        log.error("Payment persistence unavailable ({})", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "5")
                .body(new ErrorResponse("Payment temporarily unavailable", "PAYMENT_RETRY_REQUIRED",
                        "Retry with the same Idempotency-Key. The previous outcome may still be pending."));
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePaymentNotFound(PaymentNotFoundException ex, WebRequest request) {
        log.warn("Payment lookup failed ({})", ex.getClass().getSimpleName());
        ErrorResponse error = new ErrorResponse("Payment not found", "PAYMENT_NOT_FOUND", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(InvalidPaymentStateException.class)
    public ResponseEntity<ErrorResponse> handleInvalidState(InvalidPaymentStateException ex, WebRequest request) {
        log.warn("Payment state transition rejected ({})", ex.getClass().getSimpleName());
        ErrorResponse error = new ErrorResponse("Invalid payment state", "INVALID_PAYMENT_STATE", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.CONFLICT);
    }

    @ExceptionHandler(InvalidWebhookException.class)
    public ResponseEntity<ErrorResponse> handleInvalidWebhook(InvalidWebhookException ex, WebRequest request) {
        log.warn("Webhook rejected ({})", ex.getClass().getSimpleName());
        ErrorResponse error = new ErrorResponse("Invalid webhook", "INVALID_WEBHOOK", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(PaymentProcessingException.class)
    public ResponseEntity<ErrorResponse> handleProcessingException(PaymentProcessingException ex, WebRequest request) {
        log.error("Payment processing failed ({})", ex.getClass().getSimpleName());
        ErrorResponse error = new ErrorResponse("Payment processing failed", "PAYMENT_PROCESSING_ERROR", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(UnauthorizedPaymentException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedPaymentException ex, WebRequest request) {
        log.warn("Unauthorized payment access attempt ({})", ex.getClass().getSimpleName());
        ErrorResponse error = new ErrorResponse("Unauthorized access", "UNAUTHORIZED", ex.getMessage());
        return new ResponseEntity<>(error, HttpStatus.FORBIDDEN);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, WebRequest request) {
        log.warn("Request validation failed ({})", ex.getClass().getSimpleName());
        String details = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + ", " + b)
                .orElse("Validation failed");
        ErrorResponse error = new ErrorResponse("Validation error", "VALIDATION_ERROR", details);
        return new ResponseEntity<>(error, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGlobal(Exception ex, WebRequest request) {
        log.error("Unexpected application error ({})", ex.getClass().getSimpleName());
        ErrorResponse error = new ErrorResponse("Internal server error", "INTERNAL_ERROR", "An unexpected error occurred");
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
