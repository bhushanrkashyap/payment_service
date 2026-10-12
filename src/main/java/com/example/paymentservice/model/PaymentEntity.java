package com.example.paymentservice.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Represents a payment record associated with a booking.
 * Kept relational but not JPA-linked to the booking entity from another
 * service, as each microservice owns its own database.
 */
@Entity
@Table(name = "payments", uniqueConstraints = @UniqueConstraint(
        name = "uk_payment_provider_reference", columnNames = {"provider", "provider_reference_id"}),
        indexes = {
                @Index(name = "idx_payment_booking", columnList = "booking_id"),
                @Index(name = "idx_payment_user_created", columnList = "user_id,created_at"),
                @Index(name = "idx_payment_recovery", columnList = "next_recovery_at")
        })
public class PaymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "payment_id", nullable = false, unique = true, updatable = false, length = 40)
    private String paymentId;

    @Column(name = "order_id", nullable = false, unique = true, updatable = false, length = 40)
    private String orderId;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private Long bookingId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    /** Immutable payer snapshot retained for financial/audit correspondence. */
    @Column(name = "user_email", nullable = false, updatable = false, length = 255)
    private String userEmail;

    @Column(name = "amount", nullable = false, updatable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false, updatable = false, length = 20)
    private PaymentProvider provider;

    @Column(name = "provider_reference_id", length = 80)
    private String providerReferenceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    // Submission state is separate from the financial outcome. A timeout is
    // UNKNOWN, never evidence that the customer's payment failed.
    @Enumerated(EnumType.STRING)
    @Column(name = "gateway_order_state", nullable = false, length = 20)
    private GatewayOrderState gatewayOrderState = GatewayOrderState.NOT_STARTED;

    @Column(name = "gateway_submitted_at")
    private Instant gatewaySubmittedAt;

    @Column(name = "next_recovery_at")
    private Instant nextRecoveryAt;

    @Column(name = "recovery_attempts", nullable = false)
    private int recoveryAttempts;

    public PaymentEntity() {
    }

    @PrePersist
    public void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = PaymentStatus.CREATED;
        }
        if (currency == null || currency.isBlank()) {
            currency = "INR";
        }
        if (provider == null) {
            provider = PaymentProvider.SANDBOX;
        }
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(String paymentId) {
        this.paymentId = paymentId;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public Long getBookingId() {
        return bookingId;
    }

    public void setBookingId(Long bookingId) {
        this.bookingId = bookingId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public void setUserEmail(String userEmail) {
        this.userEmail = userEmail;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public PaymentProvider getProvider() {
        return provider;
    }

    public void setProvider(PaymentProvider provider) {
        this.provider = provider;
    }

    public String getProviderReferenceId() {
        return providerReferenceId;
    }

    public void setProviderReferenceId(String providerReferenceId) {
        this.providerReferenceId = providerReferenceId;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(LocalDateTime paidAt) {
        this.paidAt = paidAt;
    }

    public GatewayOrderState getGatewayOrderState() { return gatewayOrderState; }
    public void setGatewayOrderState(GatewayOrderState value) { gatewayOrderState = value; }
    public Instant getGatewaySubmittedAt() { return gatewaySubmittedAt; }
    public void setGatewaySubmittedAt(Instant value) { gatewaySubmittedAt = value; }
    public Instant getNextRecoveryAt() { return nextRecoveryAt; }
    public void setNextRecoveryAt(Instant value) { nextRecoveryAt = value; }
    public int getRecoveryAttempts() { return recoveryAttempts; }
    public void setRecoveryAttempts(int value) { recoveryAttempts = value; }
}
