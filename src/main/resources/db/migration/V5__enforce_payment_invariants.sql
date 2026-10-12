ALTER TABLE payments ADD CONSTRAINT ck_payments_id_values
    CHECK (booking_id > 0 AND user_id > 0);
ALTER TABLE payments ADD CONSTRAINT ck_payments_amount_positive
    CHECK (amount > 0);
ALTER TABLE payments ADD CONSTRAINT ck_payments_currency
    CHECK (char_length(currency) = 3 AND currency = upper(currency));
ALTER TABLE payments ADD CONSTRAINT ck_payments_provider
    CHECK (provider IN ('SANDBOX', 'RAZORPAY'));
ALTER TABLE payments ADD CONSTRAINT ck_payments_status
    CHECK (status IN ('CREATED', 'PROCESSING', 'SUCCESS', 'FAILED', 'CANCELLED', 'REFUNDED'));
ALTER TABLE payments ADD CONSTRAINT ck_payments_gateway_order_state
    CHECK (gateway_order_state IN ('NOT_STARTED', 'SUBMITTING', 'UNKNOWN', 'READY'));
ALTER TABLE payments ADD CONSTRAINT ck_payments_recovery_attempts
    CHECK (recovery_attempts >= 0 AND version >= 0);

ALTER TABLE payment_windows ADD CONSTRAINT ck_payment_windows_user_id_positive
    CHECK (user_id > 0);
ALTER TABLE payment_requests ADD CONSTRAINT ck_payment_requests_user_id_positive
    CHECK (user_id > 0);
