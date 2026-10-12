package com.example.paymentservice.Repository;

import com.example.paymentservice.model.PaymentEntity;
import com.example.paymentservice.model.PaymentStatus;
import com.example.paymentservice.model.PaymentProvider;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<PaymentEntity, Long> {

    Optional<PaymentEntity> findByPaymentId(String paymentId);

    Optional<PaymentEntity> findByOrderId(String orderId);

    List<PaymentEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<PaymentEntity> findFirstByBookingIdAndUserIdOrderByCreatedAtDesc(Long bookingId, Long userId);

    List<PaymentEntity> findByBookingId(Long bookingId);

    @Query("select p.paymentId from PaymentEntity p where p.bookingId = :bookingId")
    List<String> findPaymentIdsByBookingId(@Param("bookingId") Long bookingId);

    boolean existsByBookingIdAndStatus(Long bookingId, PaymentStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentEntity p where p.paymentId = :paymentId")
    Optional<PaymentEntity> lockByPaymentId(@Param("paymentId") String paymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentEntity p where p.provider = :provider and p.providerReferenceId = :reference")
    Optional<PaymentEntity> lockByProviderReference(@Param("provider") PaymentProvider provider,
                                                   @Param("reference") String reference);

    @Query("select p.paymentId from PaymentEntity p where p.nextRecoveryAt <= :now order by p.nextRecoveryAt")
    List<String> findRecoveryCandidates(@Param("now") Instant now, Pageable pageable);
}
