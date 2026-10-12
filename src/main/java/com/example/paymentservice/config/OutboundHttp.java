package com.example.paymentservice.config;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import java.net.http.HttpClient;
import java.time.Duration;

/** Bounded calls without an application-level retry of payment POST requests. */
public final class OutboundHttp {
    private OutboundHttp() { }

    public static JdkClientHttpRequestFactory requestFactory() {
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }
}
