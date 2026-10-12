package com.example.paymentservice.Controller;

import com.example.paymentservice.Service.PaymentService;
import com.example.paymentservice.dto.PaymentResponse;
import com.example.paymentservice.gateway.PaymentGateway;
import com.example.paymentservice.model.PaymentStatus;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PaymentWebhookControllerTest {

    private final PaymentService paymentService = mock(PaymentService.class);
    private final PaymentGateway paymentGateway = mock(PaymentGateway.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(
                        new PaymentWebhookController(paymentService, paymentGateway, new ObjectMapper()))
                .build();
        when(paymentGateway.verifyWebhook(anyString(), anyString())).thenReturn(true);
        PaymentResponse response = new PaymentResponse();
        response.setStatus(PaymentStatus.SUCCESS);
        when(paymentService.applyProviderSuccess(eq("order_123"))).thenReturn(response);
    }

    @Test
    void acceptsWhitespaceAndNestedJsonFromProvider() throws Exception {
        String body = """
                {
                  "event": "payment.captured",
                  "payload": {
                    "payment": {
                      "entity": {
                        "order_id": "order_123"
                      }
                    }
                  }
                }
                """;

        mvc.perform(post("/api/payments/webhook")
                        .header("X-Razorpay-Signature", "valid")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        verify(paymentService).applyProviderSuccess("order_123");
    }
}
