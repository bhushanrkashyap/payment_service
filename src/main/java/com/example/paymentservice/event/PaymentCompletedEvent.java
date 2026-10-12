package com.example.paymentservice.event;

import java.math.BigDecimal;

public record PaymentCompletedEvent(
        String paymentId,
        Long bookingId,
        Long userId,
        String email,
        BigDecimal amount
) {}
