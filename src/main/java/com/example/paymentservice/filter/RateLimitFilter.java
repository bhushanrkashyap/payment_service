package com.example.paymentservice.filter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP-based token-bucket rate limiter for payment service endpoints (/api/payments/**).
 * Enforces per-endpoint limits on sensitive paths (initiate, confirm) and a general limit overall.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    @Value("${app.ratelimit.api.capacity:60}")
    private int globalCapacity;

    @Value("${app.ratelimit.api.refill-seconds:60}")
    private long globalRefillSeconds;

    @Value("${app.ratelimit.initiate.capacity:20}")
    private int initiateCapacity;

    @Value("${app.ratelimit.initiate.refill-seconds:60}")
    private long initiateRefillSeconds;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        if (!path.startsWith("/api/payments")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Webhooks should not be rate-limited by user IP bucket to prevent provider drops
        if (path.startsWith("/api/payments/webhook")) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = clientIp(request);

        // Check sensitive endpoints with strict bucket
        if (path.endsWith("/initiate") || path.endsWith("/confirm")) {
            String bucketKey = "strict:" + clientIp;
            Bucket strictBucket = buckets.computeIfAbsent(bucketKey, k -> Bucket.builder()
                    .addLimit(Bandwidth.classic(initiateCapacity,
                            Refill.greedy(initiateCapacity, Duration.ofSeconds(initiateRefillSeconds))))
                    .build());

            if (!strictBucket.tryConsume(1)) {
                reject(request, response, "Payment rate limit exceeded for sensitive operations. Please wait before retrying.");
                return;
            }
        }

        // Check global endpoint bucket
        String globalKey = "global:" + clientIp;
        Bucket globalBucket = buckets.computeIfAbsent(globalKey, k -> Bucket.builder()
                .addLimit(Bandwidth.classic(globalCapacity,
                        Refill.greedy(globalCapacity, Duration.ofSeconds(globalRefillSeconds))))
                .build());

        if (!globalBucket.tryConsume(1)) {
            reject(request, response, "Rate limit exceeded. Please slow down and try again later.");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        log.warn("Rate limit exceeded on {} from {}", request.getRequestURI(), clientIp(request));
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String body = String.format(
                "{\"timestamp\":\"%s\",\"status\":%d,\"error\":\"TOO_MANY_REQUESTS\",\"message\":\"%s\",\"path\":\"%s\"}",
                LocalDateTime.now(),
                HttpStatus.TOO_MANY_REQUESTS.value(),
                message.replace("\"", "'"),
                request.getRequestURI().replace("\"", "'")
        );
        response.getWriter().write(body);
    }

    private String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }
}
