package com.example.paymentservice.event;

import com.example.paymentservice.client.BookingClient;
import com.example.paymentservice.kafka.PaymentKafkaPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class PaymentCommitListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentCommitListener.class);
    private final PaymentKafkaPublisher publisher;
    private final BookingClient bookingClient;

    public PaymentCommitListener(PaymentKafkaPublisher publisher, BookingClient bookingClient) {
        this.publisher = publisher;
        this.bookingClient = bookingClient;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentCompleted(PaymentCompletedEvent event) {
        log.info("Payment {} committed successfully. Forwarding to Kafka.", event.paymentId());
        try {
            publisher.publishPaymentSuccess(
                    event.paymentId(),
                    event.bookingId(),
                    event.userId(),
                    event.email(),
                    event.amount()
            );
        } catch (Exception ex) {
            log.warn("Kafka publish failed for payment {}, falling back to REST: {}", event.paymentId(), ex.getMessage());
            bookingClient.confirmBooking(event.bookingId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaymentFailed(PaymentFailedEvent event) {
        log.info("Payment {} failure committed. Forwarding to Kafka.", event.paymentId());
        publisher.publishPaymentFailed(
                event.paymentId(),
                event.bookingId(),
                event.userId(),
                event.email(),
                event.reason()
        );
    }
}
