package com.amazeawn.api.config;

import com.amazeawn.common.messaging.CorrelationId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Accepts an incoming X-Correlation-Id or mints one; it is echoed back and carried into every queue message. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CorrelationId.set(request.getHeader(HEADER));
        try {
            response.setHeader(HEADER, CorrelationId.current());
            chain.doFilter(request, response);
        } finally {
            CorrelationId.clear();
        }
    }
}
