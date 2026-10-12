package com.example.paymentservice.gateway;

import com.example.paymentservice.model.PaymentProvider;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Simulated payment gateway for local development and testing. Always creates
 * an order successfully; success is later confirmed explicitly through the
 * confirm endpoint (mimicking a provider completing the transaction).
 */
public class SandboxPaymentGateway implements PaymentGateway {

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.SANDBOX;
    }

    @Override
    public ProviderOrder createOrder(String referenceId, BigDecimal amount, String currency) {
        String providerOrderId = "sandbox_" + UUID.nameUUIDFromBytes(
                referenceId.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        return new ProviderOrder(providerOrderId, Map.of("simulated", true));
    }

    @Override
    public Optional<ProviderOrder> findOrder(String referenceId, BigDecimal amount, String currency) {
        // No money or external side effect exists in the sandbox. Its order is
        // deterministic, so recovery works across application restarts.
        return Optional.of(createOrder(referenceId, amount, currency));
    }

    @Override
    public boolean verifyWebhook(String signature, String rawBody) {
        // Sandbox gateway does not deliver webhooks; any webhook is rejected.
        return false;
    }
}
