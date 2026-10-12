package com.example.paymentservice.model;

import jakarta.persistence.*;
import java.time.Instant;

/** Keys are scoped to a user and permanently bound to the original request. */
@Entity
@Table(name = "payment_requests", uniqueConstraints = @UniqueConstraint(
        name = "uk_payment_request_user_key", columnNames = {"user_id", "idempotency_key"}))
public class PaymentRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private Long userId;

    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false, updatable = false)
    private PaymentEntity payment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PaymentRequest() { }

    public PaymentRequest(Long userId, String key, PaymentEntity payment) {
        this.userId = userId;
        this.idempotencyKey = key;
        this.payment = payment;
        this.createdAt = Instant.now();
    }

    public PaymentEntity getPayment() { return payment; }
}
