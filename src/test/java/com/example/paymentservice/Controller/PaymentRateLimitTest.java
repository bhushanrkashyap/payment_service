package com.example.paymentservice.Controller;

import com.example.paymentservice.Service.PaymentService;
import com.example.paymentservice.filter.RateLimitFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentRateLimitTest {

    @RestController
    static class DummyPaymentController {
        @GetMapping("/api/payments/test")
        public String test() {
            return "OK";
        }
    }

    private MockMvc mvc;
    private RateLimitFilter rateLimitFilter;

    @BeforeEach
    void setUp() {
        rateLimitFilter = new RateLimitFilter();
        ReflectionTestUtils.setField(rateLimitFilter, "globalCapacity", 3);
        ReflectionTestUtils.setField(rateLimitFilter, "globalRefillSeconds", 60L);
        ReflectionTestUtils.setField(rateLimitFilter, "initiateCapacity", 2);
        ReflectionTestUtils.setField(rateLimitFilter, "initiateRefillSeconds", 60L);

        mvc = MockMvcBuilders.standaloneSetup(new DummyPaymentController())
                .addFilters(rateLimitFilter)
                .build();
    }

    @Test
    void rejectsRequestsExceedingCapacityWith429() throws Exception {
        // First 3 requests should pass
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/api/payments/test"))
                    .andExpect(status().isOk());
        }

        // 4th request must be rejected with HTTP 429
        mvc.perform(get("/api/payments/test"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("TOO_MANY_REQUESTS"));
    }
}
