package com.example.paymentservice.event;

public record PaymentFailedEvent(
        String paymentId,
        Long bookingId,
        Long userId,
        String email,
        String reason
) {}
