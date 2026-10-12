package com.example.paymentservice;

import com.example.paymentservice.Controller.PaymentController;
import com.example.paymentservice.Repository.PaymentRepository;
import com.example.paymentservice.Repository.PaymentRequestRepository;
import com.example.paymentservice.Repository.PaymentWindowRepository;
import com.example.paymentservice.Service.PaymentOrderCoordinator;
import com.example.paymentservice.Service.PaymentService;
import com.example.paymentservice.Service.PaymentTransactions;
import com.example.paymentservice.client.BookingClient;
import com.example.paymentservice.client.dto.BookingDetails;
import com.example.paymentservice.dto.PaymentInitiateRequest;
import com.example.paymentservice.dto.PaymentResponse;
import com.example.paymentservice.exception.*;
import com.example.paymentservice.gateway.PaymentGateway;
import com.example.paymentservice.model.GatewayOrderState;
import com.example.paymentservice.model.PaymentProvider;
import com.example.paymentservice.model.PaymentStatus;
import com.example.paymentservice.security.PaymentPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
class PaymentIdempotencyTest {
    private static final String KEY = "checkout-attempt-0001";
    private static final Authentication USER = auth(1L);

    @Autowired PaymentService service;
    @Autowired PaymentTransactions transactions;
    @Autowired PaymentOrderCoordinator coordinator;
    @Autowired PaymentRepository payments;
    @Autowired PaymentRequestRepository requests;
    @Autowired PaymentWindowRepository windows;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean BookingClient bookings;
    @MockitoBean(name = "paymentGateway") PaymentGateway gateway;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        requests.deleteAll();
        windows.deleteAll();
        payments.deleteAll();
        when(gateway.provider()).thenReturn(PaymentProvider.SANDBOX);
        when(bookings.fetchBooking(anyLong(), anyString())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Booking HTTP call must not hold a database transaction");
            return booking(invocation.getArgument(0), 1L);
        });
        when(gateway.createOrder(anyString(), any(), anyString())).thenAnswer(invocation -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Provider HTTP call must not hold a database transaction");
            return order(invocation.getArgument(0), false);
        });
        when(gateway.findOrder(anyString(), any(), anyString())).thenReturn(Optional.empty());
        mvc = MockMvcBuilders.standaloneSetup(new PaymentController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void sameKeyReplaysWithoutBookingServiceOrAnotherProviderCall() {
        PaymentResponse first = initiate(KEY, 101L);
        when(bookings.fetchBooking(anyLong(), anyString())).thenThrow(new PaymentProcessingException("offline"));
        PaymentResponse replay = initiate(KEY, 101L);
        assertEquals(first.getPaymentId(), replay.getPaymentId());
        assertEquals(first.getProviderReferenceId(), replay.getProviderReferenceId());
        verify(gateway, times(1)).createOrder(eq(first.getPaymentId()), eq(new BigDecimal("500.00")), eq("INR"));
        verify(bookings, times(1)).fetchBooking(anyLong(), anyString());
        assertEquals(1, payments.count());
        assertEquals(1, requests.count());
    }

    @Test
    void sameKeyCannotBeReboundToAnotherBooking() {
        initiate(KEY, 101L);
        assertThrows(IdempotencyConflictException.class, () -> initiate(KEY, 102L));
        assertEquals(1, payments.count());
        verify(bookings, times(1)).fetchBooking(anyLong(), anyString());
    }

    @Test
    void differentKeysStillShareOneActiveWindow() {
        PaymentResponse first = initiate(KEY, 101L);
        PaymentResponse second = initiate("different-tab-0002", 101L);
        assertEquals(first.getPaymentId(), second.getPaymentId());
        assertEquals(2, requests.count());
        assertEquals(1, windows.count());
        assertEquals(1, payments.count());
        verify(gateway, times(1)).createOrder(anyString(), any(), anyString());
    }

    @Test
    void concurrentRequestsWithSameAndDifferentKeysCreateOnlyOneOrder() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 12; i++) {
                String key = i % 2 == 0 ? KEY : "parallel-request-" + i;
                futures.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    return initiate(key, 101L).getPaymentId();
                }));
            }
            start.countDown();
            String paymentId = futures.getFirst().get(20, TimeUnit.SECONDS);
            for (Future<String> future : futures) {
                assertEquals(paymentId, future.get(20, TimeUnit.SECONDS));
            }
        }
        assertEquals(1, payments.count());
        assertEquals(1, windows.count());
        verify(gateway, times(1)).createOrder(anyString(), any(), anyString());
    }

    @Test
    void responseLostAfterProviderAcceptedIsRecoveredUsingTheSameReceipt() {
        when(gateway.provider()).thenReturn(PaymentProvider.RAZORPAY);
        var accepted = new AtomicReference<String>();
        when(gateway.createOrder(anyString(), any(), anyString())).thenAnswer(invocation -> {
            accepted.set(invocation.getArgument(0));
            throw new PaymentProcessingException("socket timed out after provider accepted");
        });
        PaymentResponse pending = initiate(KEY, 101L);
        assertEquals(PaymentStatus.CREATED, pending.getStatus());
        assertEquals(GatewayOrderState.UNKNOWN, pending.getGatewayOrderState());
        assertEquals(pending.getPaymentId(), accepted.get());
        assertEquals(pending.getPaymentId(), initiate("retry-from-new-tab-02", 101L).getPaymentId());
        assertThrows(InvalidPaymentStateException.class, () -> service.cancelPayment(USER, pending.getPaymentId()));

        when(gateway.findOrder(eq(accepted.get()), any(), eq("INR")))
                .thenReturn(Optional.of(order(accepted.get(), true)));
        makeRecoveryDue(pending.getPaymentId());
        // A new coordinator has no in-memory knowledge of the first request.
        new PaymentOrderCoordinator(transactions, gateway).process(pending.getPaymentId());
        PaymentResponse recovered = service.getPayment(USER, pending.getPaymentId());
        assertEquals(PaymentStatus.SUCCESS, recovered.getStatus());
        assertEquals(GatewayOrderState.READY, recovered.getGatewayOrderState());
        assertNotNull(recovered.getPaidAt());
        verify(gateway, times(1)).createOrder(anyString(), any(), anyString());
        verify(gateway, times(1)).findOrder(eq(accepted.get()), eq(new BigDecimal("500.00")), eq("INR"));
    }

    @Test
    void crashAfterDurableClaimNeverAllowsResubmissionEvenIfLookupIsEmpty() {
        String id = transactions.reserve(1L, USER.getName(), KEY, 101L,
                new BigDecimal("500.00"), "INR", PaymentProvider.RAZORPAY);
        when(gateway.provider()).thenReturn(PaymentProvider.RAZORPAY);
        assertTrue(transactions.claimGatewayWork(id).orElseThrow().create());
        // Simulate a crash after claim commit and before the HTTP call/result.
        for (int i = 0; i < 3; i++) {
            makeRecoveryDue(id);
            new PaymentOrderCoordinator(transactions, gateway).process(id);
        }
        assertEquals(GatewayOrderState.UNKNOWN, service.getPayment(USER, id).getGatewayOrderState());
        assertEquals(id, initiate("another-request-0002", 101L).getPaymentId());
        verify(gateway, never()).createOrder(anyString(), any(), anyString());
        verify(gateway, times(3)).findOrder(eq(id), any(), eq("INR"));
        assertEquals(1, payments.count());
    }

    @Test
    void recoverySubmitsAnIntentThatWasPersistedBeforeARequestCrashed() {
        String id = transactions.reserve(1L, USER.getName(), KEY, 101L,
                new BigDecimal("500.00"), "INR", PaymentProvider.SANDBOX);
        assertEquals(GatewayOrderState.NOT_STARTED, transactions.snapshot(id).getGatewayOrderState());
        new PaymentOrderCoordinator(transactions, gateway).process(id);
        coordinator.process(id);
        assertEquals(PaymentStatus.PROCESSING, transactions.snapshot(id).getStatus());
        verify(gateway, times(1)).createOrder(eq(id), any(), anyString());
    }

    @Test
    void successfulBookingCannotOpenAnotherWindowAndConfirmationIsIdempotent() {
        PaymentResponse first = initiate(KEY, 101L);
        PaymentResponse paid = service.confirmPayment(USER, first.getPaymentId());
        PaymentResponse repeated = service.confirmPayment(USER, first.getPaymentId());
        assertEquals(paid.getPaidAt(), repeated.getPaidAt());
        PaymentResponse otherKey = initiate("new-attempt-after-success", 101L);
        assertEquals(first.getPaymentId(), otherKey.getPaymentId());
        assertEquals(PaymentStatus.SUCCESS, otherKey.getStatus());
        assertThrows(InvalidPaymentStateException.class, () -> service.failPayment(USER, paid.getPaymentId(), "late"));
        assertThrows(InvalidPaymentStateException.class, () -> service.cancelPayment(USER, paid.getPaymentId()));
        verify(gateway, times(1)).createOrder(anyString(), any(), anyString());
    }

    @Test
    void terminalSandboxAttemptNeedsANewKeyAndOldKeysKeepTheirOriginalResult() {
        PaymentResponse first = initiate(KEY, 101L);
        service.cancelPayment(USER, first.getPaymentId());
        assertEquals(PaymentStatus.CANCELLED, initiate(KEY, 101L).getStatus());
        PaymentResponse second = initiate("explicit-new-attempt-02", 101L);
        assertNotEquals(first.getPaymentId(), second.getPaymentId());
        assertEquals(first.getPaymentId(), initiate(KEY, 101L).getPaymentId());
        assertEquals(PaymentStatus.CANCELLED, initiate(KEY, 101L).getStatus());
        verify(gateway, times(2)).createOrder(anyString(), any(), anyString());
    }

    @Test
    void parallelSuccessAndCancellationCannotOverwriteEachOther() throws Exception {
        String id = initiate(KEY, 101L).getPaymentId();
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> confirm = executor.submit(() -> transition(start, id, true));
            Future<Boolean> cancel = executor.submit(() -> transition(start, id, false));
            start.countDown();
            assertNotEquals(confirm.get(10, TimeUnit.SECONDS), cancel.get(10, TimeUnit.SECONDS),
                    "Only one conflicting transition may commit");
        }
        PaymentResponse finalState = service.getPayment(USER, id);
        assertTrue(finalState.getStatus() == PaymentStatus.SUCCESS || finalState.getStatus() == PaymentStatus.CANCELLED);
        assertEquals(finalState.getStatus() == PaymentStatus.SUCCESS, finalState.getPaidAt() != null);
    }

    @Test
    void realPaymentFailureDoesNotReopenWindowAndStaleEventsCannotUndoSuccess() {
        when(gateway.provider()).thenReturn(PaymentProvider.RAZORPAY);
        PaymentResponse payment = initiate(KEY, 101L);
        assertThrows(InvalidPaymentStateException.class, () -> service.confirmPayment(USER, payment.getPaymentId()));
        service.applyProviderFailure(payment.getProviderReferenceId(), "first attempt failed");
        assertEquals(payment.getPaymentId(), initiate("retry-after-failed-attempt", 101L).getPaymentId());
        PaymentResponse paid = service.applyProviderSuccess(payment.getProviderReferenceId());
        service.applyProviderFailure(payment.getProviderReferenceId(), "delayed failure");
        PaymentResponse duplicate = service.applyProviderSuccess(payment.getProviderReferenceId());
        assertEquals(PaymentStatus.SUCCESS, duplicate.getStatus());
        assertEquals(paid.getPaidAt(), duplicate.getPaidAt());
        assertNull(duplicate.getFailureReason());
        verify(gateway, times(1)).createOrder(anyString(), any(), anyString());
    }

    @Test
    void userScopedKeysDoNotGrantCrossUserAccess() {
        PaymentResponse first = initiate(KEY, 101L);
        when(bookings.fetchBooking(eq(202L), anyString())).thenReturn(booking(202L, 2L));
        PaymentResponse second = service.initiate(auth(2L), "token2", request(202L), KEY);
        assertNotEquals(first.getPaymentId(), second.getPaymentId());
        assertThrows(UnauthorizedPaymentException.class, () -> service.getPayment(auth(2L), first.getPaymentId()));
        assertThrows(UnauthorizedPaymentException.class, () -> service.confirmPayment(auth(2L), first.getPaymentId()));
        assertThrows(UnauthorizedPaymentException.class, () -> service.initiate(auth(0L), "", request(101L), KEY));
    }

    @Test
    void invalidOrUnavailableBookingDoesNotCreateAnIntent() {
        when(bookings.fetchBooking(anyLong(), anyString())).thenThrow(new PaymentProcessingException("offline"));
        assertThrows(PaymentProcessingException.class, () -> initiate(KEY, 101L));
        BookingDetails invalid = booking(101L, 1L);
        invalid.setFare(new BigDecimal("-1"));
        doReturn(invalid).when(bookings).fetchBooking(anyLong(), anyString());
        assertThrows(InvalidPaymentRequestException.class, () -> initiate(KEY, 101L));
        invalid.setFare(new BigDecimal("500.00"));
        invalid.setBookingStatus("CANCELLED");
        assertThrows(InvalidPaymentStateException.class, () -> initiate(KEY, 101L));
        assertEquals(0, payments.count());
        verify(gateway, never()).createOrder(anyString(), any(), anyString());
    }

    @Test
    void confirmedBookingCannotCreateASecondPaymentWithoutAnExistingPayment() {
        BookingDetails confirmed = booking(101L, 1L);
        confirmed.setBookingStatus("CONFIRMED");
        when(bookings.fetchBooking(anyLong(), anyString())).thenReturn(confirmed);

        assertThrows(InvalidPaymentStateException.class, () -> initiate(KEY, 101L));
        assertEquals(0, payments.count());
        verify(gateway, never()).createOrder(anyString(), any(), anyString());
    }

    @Test
    void apiRequiresAKeyAndReportsPendingRecoveryInsteadOfAFailedCharge() throws Exception {
        mvc.perform(post("/api/payments/initiate").principal(USER).contentType("application/json")
                        .content("{\"bookingId\":101}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_PAYMENT_REQUEST"));
        when(gateway.createOrder(anyString(), any(), anyString()))
                .thenThrow(new PaymentProcessingException("response lost"));
        mvc.perform(post("/api/payments/initiate").principal(USER).contentType("application/json")
                        .header("Idempotency-Key", KEY).content("{\"bookingId\":101}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Retry-After", "5"))
                .andExpect(header().string("Idempotency-Key", KEY))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.gatewayOrderState").value("UNKNOWN"));
        mvc.perform(post("/api/payments/initiate").principal(USER).contentType("application/json")
                        .header("Idempotency-Key", KEY).content("{\"bookingId\":102}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    private boolean transition(CountDownLatch start, String id, boolean confirm) throws InterruptedException {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        try {
            if (confirm) service.confirmPayment(USER, id);
            else service.cancelPayment(USER, id);
            return true;
        } catch (InvalidPaymentStateException ex) {
            return false;
        }
    }

    private PaymentResponse initiate(String key, Long bookingId) {
        return service.initiate(USER, "token", request(bookingId), key);
    }

    private void makeRecoveryDue(String paymentId) {
        jdbc.update("update payments set next_recovery_at = ? where payment_id = ?",
                java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)), paymentId);
    }

    private static PaymentGateway.ProviderOrder order(String receipt, boolean paid) {
        return new PaymentGateway.ProviderOrder("order_" + receipt, Map.of("receipt", receipt), paid);
    }

    private static PaymentInitiateRequest request(Long id) {
        var request = new PaymentInitiateRequest();
        request.setBookingId(id);
        return request;
    }

    private static BookingDetails booking(Long id, Long userId) {
        var booking = new BookingDetails();
        booking.setBookingId(id);
        booking.setUserId(userId);
        booking.setFare(new BigDecimal("500.00"));
        booking.setBookingStatus("PENDING_PAYMENT");
        return booking;
    }

    private static Authentication auth(Long id) {
        var principal = new PaymentPrincipal(id, "user" + id + "@example.com", "USER");
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }
}
