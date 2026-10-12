package com.example.paymentservice;

import org.flywaydb.core.Flyway;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.junit.jupiter.api.Assertions.*;

class PaymentMigrationTest {
    @Test
    void adoptsHistoricalPaymentsWithoutMakingUncertainOrdersResubmittable() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:legacy_payments;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__payment_baseline.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        jdbc.update("""
                insert into payments(payment_id, order_id, booking_id, user_id, user_email, amount, currency,
                    provider, status, created_at, updated_at)
                values('PAY_legacy', 'ORDER_legacy', 1, 1, 'user@example.com', 500, 'INR',
                    'RAZORPAY', 'PROCESSING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """);
        Flyway.configure().dataSource(source).baselineOnMigrate(true).baselineVersion("0").load().migrate();
        assertEquals(1, jdbc.queryForObject("select count(*) from payments", Integer.class));
        assertEquals("UNKNOWN", jdbc.queryForObject("select gateway_order_state from payments", String.class));
        assertEquals(0L, jdbc.queryForObject("select version from payments", Long.class));
        assertNotNull(jdbc.queryForObject("select next_recovery_at from payments", java.time.OffsetDateTime.class));
        assertEquals(0, jdbc.queryForObject("select count(*) from payment_requests", Integer.class));
        assertEquals("BIGINT", jdbc.queryForObject("""
                select data_type from information_schema.columns
                where table_name = 'PAYMENT_WINDOWS' and column_name = 'PAYMENT_ID'
                """, String.class));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'PAYMENT_REQUESTS' and column_name = 'BOOKING_ID'
                """, Integer.class));
    }
}
