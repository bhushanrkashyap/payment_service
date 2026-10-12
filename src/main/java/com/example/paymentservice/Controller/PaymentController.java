package com.example.paymentservice.Controller;

import com.example.paymentservice.Service.PaymentService;
import com.example.paymentservice.dto.PaymentInitiateRequest;
import com.example.paymentservice.dto.PaymentResponse;
import com.example.paymentservice.model.GatewayOrderState;
import com.example.paymentservice.model.PaymentStatus;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /**
     * Initiate a payment against an existing booking.
     * POST /api/payments/initiate
     */
    @PostMapping("/initiate")
    public ResponseEntity<PaymentResponse> initiate(
            Authentication authentication,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentInitiateRequest request) {

        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim()
                : "";
        PaymentResponse response = paymentService.initiate(authentication, token, request, idempotencyKey);
        String responseIdempotencyKey = idempotencyKey == null ? null : idempotencyKey.trim();
        boolean terminal = response.getStatus() == PaymentStatus.SUCCESS
                || response.getStatus() == PaymentStatus.FAILED
                || response.getStatus() == PaymentStatus.CANCELLED
                || response.getStatus() == PaymentStatus.REFUNDED;
        boolean pending = !terminal && response.getGatewayOrderState() != GatewayOrderState.READY;
        var result = ResponseEntity.status(pending ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .header("Idempotency-Key", responseIdempotencyKey)
                .header(HttpHeaders.LOCATION, "/api/payments/" + response.getPaymentId());
        if (pending) {
            result.header(HttpHeaders.RETRY_AFTER, "5");
        }
        return result.body(response);
    }

    /**
     * Confirm a payment as successful (sandbox provider flow).
     * POST /api/payments/{paymentId}/confirm
     */
    @PostMapping("/{paymentId}/confirm")
    public ResponseEntity<PaymentResponse> confirm(
            Authentication authentication,
            @PathVariable String paymentId) {
        return ResponseEntity.ok(paymentService.confirmPayment(authentication, paymentId));
    }

    /**
     * Mark a payment as failed.
     * POST /api/payments/{paymentId}/fail
     */
    @PostMapping("/{paymentId}/fail")
    public ResponseEntity<PaymentResponse> fail(
            Authentication authentication,
            @PathVariable String paymentId,
            @RequestBody(required = false) String reason) {
        return ResponseEntity.ok(paymentService.failPayment(authentication, paymentId, reason));
    }

    /**
     * Cancel a payment that has not completed.
     * POST /api/payments/{paymentId}/cancel
     */
    @PostMapping("/{paymentId}/cancel")
    public ResponseEntity<PaymentResponse> cancel(
            Authentication authentication,
            @PathVariable String paymentId) {
        return ResponseEntity.ok(paymentService.cancelPayment(authentication, paymentId));
    }

    /**
     * Get payment details.
     * GET /api/payments/{paymentId}
     */
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponse> getPayment(
            Authentication authentication,
            @PathVariable String paymentId) {
        return ResponseEntity.ok(paymentService.getPayment(authentication, paymentId));
    }

    /**
     * Get the authenticated user's payment history.
     * GET /api/payments/me
     */
    @GetMapping("/me")
    public ResponseEntity<List<PaymentResponse>> myPayments(Authentication authentication) {
        return ResponseEntity.ok(paymentService.getUserPayments(authentication));
    }

    /**
     * Get the latest payment attempt for an owned booking.
     * GET /api/payments/booking/{bookingId}
     */
    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<PaymentResponse> getPaymentForBooking(
            Authentication authentication,
            @PathVariable Long bookingId) {
        return ResponseEntity.ok(paymentService.getPaymentForBooking(authentication, bookingId));
    }
}
