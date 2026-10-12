package com.example.paymentservice.Service;

import com.example.paymentservice.Repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "payment.recovery.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentRecoveryJob {
    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryJob.class);
    private final PaymentRepository payments;
    private final PaymentOrderCoordinator orders;

    public PaymentRecoveryJob(PaymentRepository payments, PaymentOrderCoordinator orders) {
        this.payments = payments;
        this.orders = orders;
    }

    @Scheduled(fixedDelayString = "${payment.recovery.interval-ms:10000}")
    public void recover() {
        try {
            for (String id : payments.findRecoveryCandidates(Instant.now(), PageRequest.of(0, 50))) {
                try {
                    orders.process(id);
                } catch (RuntimeException ex) {
                    log.warn("Recovery deferred for payment {} ({})", id, ex.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException ex) {
            log.warn("Payment recovery temporarily unavailable ({})", ex.getClass().getSimpleName());
        }
    }
}
