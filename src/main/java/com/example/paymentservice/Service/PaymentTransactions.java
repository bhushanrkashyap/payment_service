package com.example.paymentservice.Service;

import com.example.paymentservice.event.PaymentCompletedEvent;
import com.example.paymentservice.event.PaymentFailedEvent;
import org.springframework.context.ApplicationEventPublisher;

import com.example.paymentservice.Repository.PaymentRepository;
import com.example.paymentservice.Repository.PaymentRequestRepository;
import com.example.paymentservice.Repository.PaymentWindowRepository;
import com.example.paymentservice.dto.PaymentResponse;
import com.example.paymentservice.exception.IdempotencyConflictException;
import com.example.paymentservice.exception.InvalidPaymentStateException;
import com.example.paymentservice.exception.PaymentNotFoundException;
import com.example.paymentservice.exception.UnauthorizedPaymentException;
import com.example.paymentservice.gateway.PaymentGateway.ProviderOrder;
import com.example.paymentservice.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

/** Short, independently committed transactions. No network I/O belongs here. */
@Service
@Transactional(propagation = Propagation.REQUIRES_NEW)
public class PaymentTransactions {
    private final PaymentRepository payments;
    private final PaymentRequestRepository requests;
    private final PaymentWindowRepository windows;
    private final ApplicationEventPublisher eventPublisher;

    public PaymentTransactions(PaymentRepository payments, PaymentRequestRepository requests,
                               PaymentWindowRepository windows, ApplicationEventPublisher eventPublisher) {
        this.payments = payments;
        this.requests = requests;
        this.windows = windows;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<PaymentResponse> replay(Long userId, String key, Long bookingId) {
        return requests.findByUserIdAndIdempotencyKey(userId, key).map(request -> {
            requireSameRequest(request, bookingId);
            return PaymentResponse.fromEntity(request.getPayment());
        });
    }

    public String reserve(Long userId, String email, String key, Long bookingId,
                          BigDecimal amount, String currency, PaymentProvider provider) {
        var previous = requests.findByUserIdAndIdempotencyKey(userId, key);
        if (previous.isPresent()) {
            requireSameRequest(previous.get(), bookingId);
            return previous.get().getPayment().getPaymentId();
        }

        // On the first request, the primary-key constraint serializes competing
        // inserts. The caller retries a constraint race in a fresh transaction.
        PaymentWindow window = windows.lockByBookingId(bookingId)
                .orElseGet(() -> windows.saveAndFlush(new PaymentWindow(bookingId, userId)));
        if (!userId.equals(window.getUserId())) {
            throw new UnauthorizedPaymentException("You do not have access to this payment window");
        }

        if (window.getPayment() == null) {
            var legacy = payments.findPaymentIdsByBookingId(bookingId);
            if (legacy.size() > 1) {
                throw new InvalidPaymentStateException(
                        "Multiple existing payments require reconciliation before another payment can be opened");
            }
            if (!legacy.isEmpty()) {
                window.setPayment(locked(legacy.getFirst()));
            }
        }

        PaymentEntity payment = window.getPayment();
        if (payment != null && !userId.equals(payment.getUserId())) {
            throw new UnauthorizedPaymentException("You do not have access to this payment");
        }
        // Only a terminal simulated attempt is known to be incapable of a late
        // charge. Real orders keep the window until authoritative closure is
        // implemented, even after a failed attempt or local cancellation.
        if (payment == null || canOpenNextSandboxWindow(payment)) {
            payment = new PaymentEntity();
            payment.setPaymentId(reference("PAY"));
            payment.setOrderId(reference("ORDER"));
            payment.setBookingId(bookingId);
            payment.setUserId(userId);
            payment.setUserEmail(email);
            payment.setAmount(amount);
            payment.setCurrency(currency);
            payment.setProvider(provider);
            payment.setStatus(PaymentStatus.CREATED);
            payment.setGatewayOrderState(GatewayOrderState.NOT_STARTED);
            payment.setNextRecoveryAt(Instant.now());
            payments.saveAndFlush(payment);
            window.setPayment(payment);
        } else if (payment.getStatus() != PaymentStatus.SUCCESS && payment.getStatus() != PaymentStatus.REFUNDED
                && (payment.getAmount().compareTo(amount) != 0 || !payment.getCurrency().equals(currency))) {
            throw new InvalidPaymentStateException("The fare changed while a payment is unresolved");
        }

        windows.save(window);
        requests.saveAndFlush(new PaymentRequest(userId, key, payment));
        return payment.getPaymentId();
    }

    /** Commit the submission marker BEFORE sending any external request. */
    public Optional<GatewayWork> claimGatewayWork(String paymentId) {
        PaymentEntity payment = locked(paymentId);
        if (payment.getStatus() == PaymentStatus.SUCCESS || payment.getStatus() == PaymentStatus.REFUNDED) {
            return Optional.empty();
        }
        if (payment.getProvider() == PaymentProvider.SANDBOX
                && payment.getStatus() != PaymentStatus.CREATED) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        if (payment.getNextRecoveryAt() == null || payment.getNextRecoveryAt().isAfter(now)) {
            return Optional.empty();
        }
        boolean create = payment.getGatewayOrderState() == GatewayOrderState.NOT_STARTED;
        if (create) {
            payment.setGatewayOrderState(GatewayOrderState.SUBMITTING);
            payment.setGatewaySubmittedAt(now);
        }
        // A lease limits parallel read-only reconciliation across instances.
        // An expired lease NEVER authorizes another create-order call.
        payment.setNextRecoveryAt(now.plusSeconds(30));
        payment.setRecoveryAttempts(Math.min(30, payment.getRecoveryAttempts() + 1));
        return Optional.of(new GatewayWork(paymentId, payment.getAmount(), payment.getCurrency(),
                payment.getProvider(), create));
    }

    public void recordOrder(String paymentId, ProviderOrder order) {
        PaymentEntity payment = locked(paymentId);
        if (order == null || order.getProviderOrderId() == null || order.getProviderOrderId().isBlank()) {
            throw new InvalidPaymentStateException("Provider returned an invalid order reference");
        }
        if (payment.getProviderReferenceId() != null
                && !payment.getProviderReferenceId().equals(order.getProviderOrderId())) {
            throw new InvalidPaymentStateException("A different provider order is already associated with this payment");
        }
        payment.setProviderReferenceId(order.getProviderOrderId());
        payment.setGatewayOrderState(GatewayOrderState.READY);
        if (order.isPaid()) {
            succeed(payment);
        } else if (payment.getStatus() == PaymentStatus.CREATED) {
            payment.setStatus(PaymentStatus.PROCESSING);
            payment.setFailureReason(null);
        }
        if (payment.getStatus() != PaymentStatus.SUCCESS && payment.getStatus() != PaymentStatus.REFUNDED) {
            payment.setNextRecoveryAt(payment.getProvider() == PaymentProvider.SANDBOX
                    ? null : Instant.now().plusSeconds(60));
        }
    }

    public void recordUncertain(String paymentId) {
        PaymentEntity payment = locked(paymentId);
        if (payment.getStatus() == PaymentStatus.SUCCESS || payment.getStatus() == PaymentStatus.REFUNDED) {
            return;
        }
        if (payment.getGatewayOrderState() != GatewayOrderState.READY) {
            payment.setGatewayOrderState(GatewayOrderState.UNKNOWN);
            payment.setFailureReason("Provider outcome is pending verification. Retry with the same Idempotency-Key.");
        }
        long delay = Math.min(300, 5L << Math.min(payment.getRecoveryAttempts(), 6));
        payment.setNextRecoveryAt(Instant.now().plusSeconds(delay));
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public PaymentResponse snapshot(String paymentId) {
        return PaymentResponse.fromEntity(payments.findByPaymentId(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId)));
    }

    public PaymentResponse sandboxTransition(Long userId, String paymentId, PaymentStatus target, String reason) {
        PaymentEntity payment = locked(paymentId);
        if (!userId.equals(payment.getUserId())) {
            throw new UnauthorizedPaymentException("You do not have access to this payment");
        }
        if (payment.getProvider() != PaymentProvider.SANDBOX) {
            throw new InvalidPaymentStateException("Real payment outcomes must be verified with the provider");
        }
        if (payment.getStatus() == target) {
            return PaymentResponse.fromEntity(payment);
        }
        if (payment.getStatus() != PaymentStatus.PROCESSING
                || payment.getGatewayOrderState() != GatewayOrderState.READY) {
            throw new InvalidPaymentStateException("Payment cannot be changed from " + payment.getStatus());
        }
        if (target == PaymentStatus.SUCCESS) {
            succeed(payment);
        } else {
            payment.setStatus(target);
            String failReason = target == PaymentStatus.FAILED ? sanitize(reason) : "Cancelled by user";
            payment.setFailureReason(failReason);
            payment.setNextRecoveryAt(null);
            eventPublisher.publishEvent(new PaymentFailedEvent(
                    payment.getPaymentId(),
                    payment.getBookingId(),
                    payment.getUserId(),
                    payment.getUserEmail(),
                    failReason
            ));
        }
        return PaymentResponse.fromEntity(payment);
    }

    public PaymentResponse providerTransition(PaymentProvider provider, String reference, boolean paid, String reason) {
        PaymentEntity payment = payments.lockByProviderReference(provider, reference)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found for provider order: " + reference));
        if (paid) {
            succeed(payment);
        } else if (payment.getStatus() != PaymentStatus.SUCCESS && payment.getStatus() != PaymentStatus.REFUNDED) {
            // A failed attempt is not proof that the external order is closed.
            payment.setFailureReason(sanitize(reason));
            payment.setNextRecoveryAt(Instant.now().plusSeconds(60));
        }
        return PaymentResponse.fromEntity(payment);
    }

    private void succeed(PaymentEntity payment) {
        if (payment.getStatus() == PaymentStatus.SUCCESS || payment.getStatus() == PaymentStatus.REFUNDED) {
            return;
        }
        payment.setStatus(PaymentStatus.SUCCESS);
        // PostgreSQL TIMESTAMP values have microsecond precision. Normalize the
        // value before returning the managed entity so an immediate response and
        // a subsequent idempotent read expose the same paidAt value.
        payment.setPaidAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
        payment.setFailureReason(null);
        payment.setNextRecoveryAt(null);
        eventPublisher.publishEvent(new PaymentCompletedEvent(
                payment.getPaymentId(),
                payment.getBookingId(),
                payment.getUserId(),
                payment.getUserEmail(),
                payment.getAmount()
        ));
    }

    private PaymentEntity locked(String paymentId) {
        return payments.lockByPaymentId(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));
    }

    private void requireSameRequest(PaymentRequest request, Long bookingId) {
        if (!request.getPayment().getBookingId().equals(bookingId)) {
            throw new IdempotencyConflictException("Idempotency-Key is already bound to a different booking");
        }
    }

    private boolean canOpenNextSandboxWindow(PaymentEntity payment) {
        return payment.getProvider() == PaymentProvider.SANDBOX
                && payment.getGatewayOrderState() == GatewayOrderState.READY
                && (payment.getStatus() == PaymentStatus.FAILED || payment.getStatus() == PaymentStatus.CANCELLED);
    }

    private String reference(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private String sanitize(String reason) {
        if (reason == null) {
            return null;
        }
        String normalized = reason.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    public record GatewayWork(String paymentId, BigDecimal amount, String currency,
                              PaymentProvider provider, boolean create) { }
}
