package com.example.paymentservice.Repository;

import com.example.paymentservice.model.PaymentWindow;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface PaymentWindowRepository extends JpaRepository<PaymentWindow, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from PaymentWindow w where w.bookingId = :bookingId")
    Optional<PaymentWindow> lockByBookingId(@Param("bookingId") Long bookingId);
}
