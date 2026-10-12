package com.example.paymentservice.model;

import jakarta.persistence.*;

/** Database mutex and durable pointer to the current payment for a booking. */
@Entity
@Table(name = "payment_windows")
public class PaymentWindow {
    @Id
    @Column(name = "booking_id")
    private Long bookingId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Internal FK; public payment IDs remain an API concern. */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", referencedColumnName = "id", unique = true)
    private PaymentEntity payment;

    protected PaymentWindow() { }

    public PaymentWindow(Long bookingId, Long userId) {
        this.bookingId = bookingId;
        this.userId = userId;
    }

    public Long getUserId() { return userId; }
    public PaymentEntity getPayment() { return payment; }
    public String getPaymentId() { return payment == null ? null : payment.getPaymentId(); }
    public void setPayment(PaymentEntity payment) { this.payment = payment; }
}
