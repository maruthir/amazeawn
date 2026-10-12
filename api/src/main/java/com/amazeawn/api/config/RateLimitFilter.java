package com.amazeawn.api.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client-IP token bucket. Off by default so load tests are not throttled; switch on with
 * {@code RATE_LIMIT_ENABLED=true} to observe 429 behaviour under load.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final AppProperties.RateLimit config;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(AppProperties props) {
        this.config = props.rateLimit();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !config.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String ip = request.getRemoteAddr();
        Bucket bucket = buckets.computeIfAbsent(ip, k -> new Bucket(config.burst(), config.requestsPerSecond()));
        if (bucket.tryTake()) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate_limited\",\"message\":\"Too many requests\"}");
        }
    }

    private static final class Bucket {
        private final int capacity;
        private final double refillPerNanos;
        private double tokens;
        private long last = System.nanoTime();

        Bucket(int capacity, int perSecond) {
            this.capacity = capacity;
            this.refillPerNanos = perSecond / 1_000_000_000d;
            this.tokens = capacity;
        }

        synchronized boolean tryTake() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - last) * refillPerNanos);
            last = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }
}
