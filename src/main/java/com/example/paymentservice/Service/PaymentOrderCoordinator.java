package com.example.paymentservice.Service;

import com.example.paymentservice.gateway.PaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** At-most-once order submission followed exclusively by read-only recovery. */
@Service
public class PaymentOrderCoordinator {
    private static final Logger log = LoggerFactory.getLogger(PaymentOrderCoordinator.class);
    private final PaymentTransactions transactions;
    private final PaymentGateway gateway;

    public PaymentOrderCoordinator(PaymentTransactions transactions, PaymentGateway paymentGateway) {
        this.transactions = transactions;
        this.gateway = paymentGateway;
    }

    public void process(String paymentId) {
        var claimed = transactions.claimGatewayWork(paymentId);
        if (claimed.isEmpty()) {
            return;
        }
        var work = claimed.get();
        try {
            if (work.provider() != gateway.provider()) {
                throw new IllegalStateException("Payment provider configuration changed for an unresolved payment");
            }
            // paymentId is the durable, globally unique gateway request key.
            // API request keys map to it; it is never regenerated on retry.
            var order = work.create()
                    ? java.util.Optional.of(gateway.createOrder(work.paymentId(), work.amount(), work.currency()))
                    : gateway.findOrder(work.paymentId(), work.amount(), work.currency());
            if (order.isPresent()) {
                transactions.recordOrder(paymentId, order.get());
            } else {
                transactions.recordUncertain(paymentId);
            }
        } catch (RuntimeException ex) {
            log.warn("Payment {} requires reconciliation ({})", paymentId, ex.getClass().getSimpleName());
            // A network/parse/commit failure must not be interpreted as a failed
            // charge or followed by another create. If DB is unavailable this
            // may also fail; the previously committed claim survives a restart.
            transactions.recordUncertain(paymentId);
        }
    }
}
