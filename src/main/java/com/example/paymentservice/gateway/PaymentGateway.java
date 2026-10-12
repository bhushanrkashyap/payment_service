package com.example.paymentservice.gateway;

import com.example.paymentservice.model.PaymentProvider;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Abstraction over an external payment gateway. Implementations are resolved
 * at runtime from configuration so the provider can be switched (SANDBOX for
 * local development, RAZORPAY for production) without changing service code.
 */
public interface PaymentGateway {

    PaymentProvider provider();

    /**
     * Creates a payment order with the external provider and returns the
     * provider's order reference. referenceId is the persisted payment ID and
     * stable gateway idempotency/correlation key, never a per-retry random ID.
     * This method is invoked at most once per payment by the coordinator.
     */
    ProviderOrder createOrder(String referenceId, BigDecimal amount, String currency);

    /** Read-only reconciliation. An empty result NEVER authorizes recreation. */
    Optional<ProviderOrder> findOrder(String referenceId, BigDecimal amount, String currency);

    /**
     * Verifies a webhook signature. Only used by gateways that deliver
     * asynchronous callbacks (e.g. Razorpay).
     */
    boolean verifyWebhook(String signature, String rawBody);

    /**
     * Encapsulates the provider response for a created order.
     */
    final class ProviderOrder {
        private final String providerOrderId;
        private final Map<String, Object> metadata;
        private final boolean paid;

        public ProviderOrder(String providerOrderId, Map<String, Object> metadata) {
            this(providerOrderId, metadata, false);
        }

        public ProviderOrder(String providerOrderId, Map<String, Object> metadata, boolean paid) {
            this.providerOrderId = providerOrderId;
            this.metadata = metadata;
            this.paid = paid;
        }

        public String getProviderOrderId() {
            return providerOrderId;
        }

        public Map<String, Object> getMetadata() {
            return metadata;
        }

        public boolean isPaid() { return paid; }
    }
}
