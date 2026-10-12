package com.example.paymentservice.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
@ConditionalOnProperty(name = "spring.kafka.admin.auto-create", havingValue = "true", matchIfMissing = true)
public class KafkaConfig {

    public static final String PAYMENT_EVENTS_TOPIC = "payment-events";
    public static final String PAYMENT_EVENTS_DLT = "payment-events.DLT";
    public static final String PAYMENT_SUCCESS_TOPIC = "payment.success";
    public static final String PAYMENT_FAILED_TOPIC = "payment.failed";

    @Bean
    public NewTopic paymentEventsTopic() {
        return TopicBuilder.name(PAYMENT_EVENTS_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentEventsDeadLetterTopic() {
        return TopicBuilder.name(PAYMENT_EVENTS_DLT).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentSuccessTopic() {
        return TopicBuilder.name(PAYMENT_SUCCESS_TOPIC).partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentFailedTopic() {
        return TopicBuilder.name(PAYMENT_FAILED_TOPIC).partitions(3).replicas(1).build();
    }
}
