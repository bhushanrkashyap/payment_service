package com.example.paymentservice.Repository;

import com.example.paymentservice.model.PaymentRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PaymentRequestRepository extends JpaRepository<PaymentRequest, Long> {
    Optional<PaymentRequest> findByUserIdAndIdempotencyKey(Long userId, String idempotencyKey);
}
