package com.example.paymentservice.Service;

import com.example.paymentservice.Repository.PaymentRepository;
import com.example.paymentservice.client.BookingClient;
import com.example.paymentservice.client.dto.BookingDetails;
import com.example.paymentservice.dto.PaymentInitiateRequest;
import com.example.paymentservice.dto.PaymentResponse;
import com.example.paymentservice.exception.*;
import com.example.paymentservice.gateway.PaymentGateway;
import com.example.paymentservice.model.PaymentStatus;
import com.example.paymentservice.security.PaymentPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

@Service
public class PaymentService {
    private final PaymentRepository paymentRepository;
    private final BookingClient bookingClient;
    private final PaymentGateway paymentGateway;
    private final PaymentTransactions transactions;
    private final PaymentOrderCoordinator orders;
    private final String currency;

    public PaymentService(PaymentRepository paymentRepository, BookingClient bookingClient,
                          PaymentGateway paymentGateway, PaymentTransactions transactions,
                          PaymentOrderCoordinator orders, @Value("${payment.currency:INR}") String currency) {
        this.paymentRepository = paymentRepository;
        this.bookingClient = bookingClient;
        this.paymentGateway = paymentGateway;
        this.transactions = transactions;
        this.orders = orders;
        this.currency = (currency == null ? "INR" : currency.trim()).toUpperCase(Locale.ROOT);
        if (!this.currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("payment.currency must be a three-letter currency code");
        }
    }

    // Deliberately not @Transactional: reserve/claim commit before network I/O.
    public PaymentResponse initiate(Authentication authentication, String bearerToken,
                                    PaymentInitiateRequest request, String idempotencyKey) {
        Long userId = resolveUserId(authentication);
        String normalizedIdempotencyKey = idempotencyKey == null ? null : idempotencyKey.trim();
        if (normalizedIdempotencyKey == null
                || !normalizedIdempotencyKey.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new InvalidPaymentRequestException(
                    "Idempotency-Key is required: use 16-128 letters, digits, underscores or hyphens");
        }
        if (request == null || request.getBookingId() == null || request.getBookingId() <= 0) {
            throw new InvalidPaymentRequestException("bookingId must be positive");
        }

        // Lost client responses remain replayable even when Booking Service is down.
        var replay = transactions.replay(userId, normalizedIdempotencyKey, request.getBookingId());
        String paymentId;
        if (replay.isPresent()) {
            paymentId = replay.get().getPaymentId();
        } else {
            BookingDetails booking = bookingClient.fetchBooking(request.getBookingId(), bearerToken);
            validateBooking(booking, request.getBookingId(), userId);
            paymentId = reserve(userId, authentication.getName(), normalizedIdempotencyKey, booking);
        }
        orders.process(paymentId);
        return transactions.snapshot(paymentId);
    }

    private String reserve(Long userId, String email, String key, BookingDetails booking) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return transactions.reserve(userId, email, key, booking.getBookingId(),
                        booking.getFare().setScale(2, RoundingMode.UNNECESSARY), currency, paymentGateway.provider());
            } catch (DataIntegrityViolationException ex) {
                // Concurrent first requests may collide on the window PK or the
                // user/key constraint. No provider call has happened in this path.
                if (attempt == 2) {
                    throw new PaymentProcessingException("Payment is busy. Retry with the same Idempotency-Key.", ex);
                }
            }
        }
        throw new IllegalStateException("Unreachable reservation state");
    }

    public PaymentResponse confirmPayment(Authentication auth, String paymentId) {
        return transactions.sandboxTransition(resolveUserId(auth), paymentId, PaymentStatus.SUCCESS, null);
    }

    public PaymentResponse failPayment(Authentication auth, String paymentId, String reason) {
        return transactions.sandboxTransition(resolveUserId(auth), paymentId, PaymentStatus.FAILED, reason);
    }

    public PaymentResponse cancelPayment(Authentication auth, String paymentId) {
        return transactions.sandboxTransition(resolveUserId(auth), paymentId, PaymentStatus.CANCELLED, null);
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(Authentication auth, String paymentId) {
        Long userId = resolveUserId(auth);
        var payment = paymentRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));
        if (!userId.equals(payment.getUserId())) {
            throw new UnauthorizedPaymentException("You do not have access to this payment");
        }
        return PaymentResponse.fromEntity(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> getUserPayments(Authentication auth) {
        return paymentRepository.findByUserIdOrderByCreatedAtDesc(resolveUserId(auth)).stream()
                .map(PaymentResponse::fromEntity).toList();
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPaymentForBooking(Authentication auth, Long bookingId) {
        if (bookingId == null || bookingId <= 0) {
            throw new InvalidPaymentRequestException("bookingId must be positive");
        }
        return paymentRepository.findFirstByBookingIdAndUserIdOrderByCreatedAtDesc(bookingId, resolveUserId(auth))
                .map(PaymentResponse::fromEntity)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found for booking: " + bookingId));
    }

    public PaymentResponse applyProviderSuccess(String providerOrderId) {
        return transactions.providerTransition(paymentGateway.provider(), providerOrderId, true, null);
    }

    public PaymentResponse applyProviderFailure(String providerOrderId, String reason) {
        return transactions.providerTransition(paymentGateway.provider(), providerOrderId, false, reason);
    }

    private void validateBooking(BookingDetails booking, Long requestedId, Long userId) {
        if (booking == null || !requestedId.equals(booking.getBookingId())) {
            throw new PaymentProcessingException("Booking service returned an invalid booking");
        }
        if (booking.getUserId() != null && !userId.equals(booking.getUserId())) {
            throw new UnauthorizedPaymentException("You do not have access to this booking");
        }
        if (booking.getFare() == null || booking.getFare().signum() <= 0
                || booking.getFare().stripTrailingZeros().scale() > 2
                || booking.getFare().precision() - booking.getFare().scale() > 10) {
            throw new InvalidPaymentRequestException("Booking fare must be positive and fit a two-decimal monetary amount");
        }
        if (booking.getBookingStatus() == null
                || !"PENDING_PAYMENT".equals(booking.getBookingStatus().toUpperCase(Locale.ROOT))) {
            throw new InvalidPaymentStateException("This booking is not eligible for payment");
        }
    }

    private Long resolveUserId(Authentication auth) {
        if (auth != null && auth.isAuthenticated() && auth.getPrincipal() instanceof PaymentPrincipal principal
                && principal.getId() != null && principal.getId() > 0) {
            return principal.getId();
        }
        throw new UnauthorizedPaymentException("A valid authenticated user ID is required");
    }
}
