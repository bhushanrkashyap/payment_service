package com.example.paymentservice.kafka;

import com.example.paymentservice.config.KafkaConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Publishes payment lifecycle events to Kafka topics for booking-service and notification-service.
 * All sends are non-blocking and catch exceptions safely so broker issues do not corrupt DB state.
 */
@Component
public class PaymentKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(PaymentKafkaPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Autowired(required = false)
    public PaymentKafkaPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishPaymentSuccess(String paymentId, Long bookingId, Long userId, String email, BigDecimal amount) {
        if (kafkaTemplate == null) {
            log.warn("KafkaTemplate not configured, skipping payment success event publish");
            return;
        }

        String key = String.valueOf(bookingId);

        // 1. Event for booking_service on "payment-events" topic
        String bookingPayload = String.format(
                "{\"bookingId\":%d,\"status\":\"COMPLETED\",\"reason\":null}",
                bookingId
        );
        sendSafely(KafkaConfig.PAYMENT_EVENTS_TOPIC, key, bookingPayload, "booking payment-events completed");

        // 2. Event for notification_service on "payment.success" topic
        double amt = amount != null ? amount.doubleValue() : 0.0;
        String eventId = "evt-pay-" + paymentId + "-success";
        String notificationPayload = String.format(Locale.ROOT,
                "{\"eventId\":\"%s\",\"paymentId\":\"%s\",\"bookingId\":%d,\"userId\":%d,\"email\":\"%s\",\"amount\":%.2f}",
                eventId, paymentId, bookingId, userId != null ? userId : 0L, escapeJson(email), amt
        );
        sendSafely(KafkaConfig.PAYMENT_SUCCESS_TOPIC, key, notificationPayload, "notification payment.success");
    }

    public void publishPaymentFailed(String paymentId, Long bookingId, Long userId, String email, String reason) {
        if (kafkaTemplate == null) {
            log.warn("KafkaTemplate not configured, skipping payment failed event publish");
            return;
        }

        String key = String.valueOf(bookingId);
        String safeReason = escapeJson(reason != null ? reason : "Payment unsuccessful");

        // 1. Event for booking_service on "payment-events" topic
        String bookingPayload = String.format(
                "{\"bookingId\":%d,\"status\":\"FAILED\",\"reason\":\"%s\"}",
                bookingId, safeReason
        );
        sendSafely(KafkaConfig.PAYMENT_EVENTS_TOPIC, key, bookingPayload, "booking payment-events failed");

        // 2. Event for notification_service on "payment.failed" topic
        String eventId = "evt-pay-" + paymentId + "-failed";
        String notificationPayload = String.format(
                "{\"eventId\":\"%s\",\"paymentId\":\"%s\",\"bookingId\":%d,\"userId\":%d,\"email\":\"%s\",\"reason\":\"%s\"}",
                eventId, paymentId, bookingId, userId != null ? userId : 0L, escapeJson(email), safeReason
        );
        sendSafely(KafkaConfig.PAYMENT_FAILED_TOPIC, key, notificationPayload, "notification payment.failed");
    }

    private void sendSafely(String topic, String key, String payload, String description) {
        try {
            log.info("Publishing {} to topic {} [key={}]: {}", description, topic, key, payload);
            kafkaTemplate.send(topic, key, payload).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to send Kafka message to topic {}: {}", topic, ex.getMessage(), ex);
                } else {
                    log.debug("Successfully sent Kafka message to topic {}", topic);
                }
            });
        } catch (Exception ex) {
            log.error("Synchronous error during Kafka send to topic {}: {}", topic, ex.getMessage(), ex);
        }
    }

    private String escapeJson(String raw) {
        if (raw == null) return "";
        return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }
}
