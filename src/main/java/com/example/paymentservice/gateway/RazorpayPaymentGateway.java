package com.example.paymentservice.gateway;

import com.example.paymentservice.exception.PaymentProcessingException;
import com.example.paymentservice.config.OutboundHttp;
import com.example.paymentservice.model.PaymentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import java.util.Optional;

/**
 * Razorpay gateway integration. Requires the RAZORPAY_KEY_ID / RAZORPAY_KEY_SECRET
 * environment variables. Webhook callbacks are verified using the Razorpay
 * HMAC-SHA256 signature mechanism before any payment status change is applied.
 */
@Component
public class RazorpayPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayPaymentGateway.class);
    private static final String BASE_URL = "https://api.razorpay.com/v1";

    private final RestClient restClient;
    private final String keyId;
    private final String keySecret;
    private final String webhookSecret;

    public RazorpayPaymentGateway(
            @Value("${payment.provider.razorpay.key.id:}") String keyId,
            @Value("${payment.provider.razorpay.key.secret:}") String keySecret,
            @Value("${payment.provider.razorpay.webhook.secret:}") String webhookSecret) {
        this.keyId = keyId;
        this.keySecret = keySecret;
        this.webhookSecret = webhookSecret;
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .requestFactory(OutboundHttp.requestFactory())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.RAZORPAY;
    }

    @Override
    public ProviderOrder createOrder(String referenceId, BigDecimal amount, String currency) {
        requireCredentials();
        long amountPaise = amount.movePointRight(2).longValueExact();
        // The API key maps to this persisted receipt. Do not invent an
        // Idempotency-Key header contract for the provider or generate a new
        // receipt when a response is lost. Recovery only fetches this receipt.
        Map<String, Object> body = Map.of("amount", amountPaise, "currency", currency,
                "receipt", referenceId, "partial_payment", false,
                "notes", Map.of("payment_request_key", referenceId));

        try {
            Map<?, ?> response = restClient.post()
                    .uri("/orders")
                    .headers(h -> h.setBasicAuth(keyId, keySecret))
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            log.info("Razorpay order created");
            return verifiedOrder(response, referenceId, amount, currency);
        } catch (RestClientException ex) {
            log.error("Razorpay create order failed ({})", ex.getClass().getSimpleName());
            throw new PaymentProcessingException("Payment provider rejected the order request.", ex);
        } catch (RuntimeException ex) {
            throw new PaymentProcessingException("Payment provider returned an unexpected response.", ex);
        }
    }

    @Override
    public Optional<ProviderOrder> findOrder(String referenceId, BigDecimal amount, String currency) {
        requireCredentials();
        try {
            Map<?, ?> response = restClient.get()
                    .uri(builder -> builder.path("/orders").queryParam("receipt", referenceId)
                            .queryParam("count", 100).build())
                    .headers(h -> h.setBasicAuth(keyId, keySecret))
                    .retrieve().body(Map.class);
            if (response == null || !(response.get("items") instanceof List<?> items)) {
                throw new PaymentProcessingException("Provider returned an invalid order collection");
            }
            ProviderOrder found = null;
            for (Object item : items) {
                if (item instanceof Map<?, ?> order && referenceId.equals(order.get("receipt"))) {
                    if (found != null) {
                        throw new PaymentProcessingException("Multiple provider orders require manual reconciliation");
                    }
                    found = verifiedOrder(order, referenceId, amount, currency);
                }
            }
            return Optional.ofNullable(found);
        } catch (RestClientException ex) {
            throw new PaymentProcessingException("Payment provider reconciliation is temporarily unavailable", ex);
        }
    }

    private ProviderOrder verifiedOrder(Map<?, ?> order, String referenceId, BigDecimal amount, String currency) {
        if (order == null || !(order.get("id") instanceof String id) || !id.startsWith("order_")
                || !referenceId.equals(order.get("receipt")) || !currency.equals(order.get("currency"))
                || !(order.get("amount") instanceof Number providerAmount)
                || new BigDecimal(providerAmount.toString()).compareTo(amount.movePointRight(2)) != 0) {
            throw new PaymentProcessingException("Provider order does not match the persisted payment request");
        }
        boolean paid = "paid".equals(order.get("status"));
        if (paid && (!(order.get("amount_paid") instanceof Number amountPaid)
                || new BigDecimal(amountPaid.toString()).compareTo(amount.movePointRight(2)) != 0)) {
            throw new PaymentProcessingException("Provider order is not fully paid");
        }
        return new ProviderOrder(id, Map.of("receipt", referenceId), paid);
    }

    private void requireCredentials() {
        if (keyId == null || keyId.isBlank() || keySecret == null || keySecret.isBlank()) {
            throw new PaymentProcessingException("Razorpay credentials are not configured");
        }
    }

    @Override
    public boolean verifyWebhook(String signature, String rawBody) {
        if (webhookSecret == null || webhookSecret.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec spec = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(spec);
            byte[] rawHmac = mac.doFinal(rawBody.getBytes(StandardCharsets.UTF_8));
            byte[] expected = Base64.getEncoder().encode(rawHmac);
            return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            log.error("Razorpay webhook signature verification failed ({})", ex.getClass().getSimpleName());
            return false;
        }
    }

}
