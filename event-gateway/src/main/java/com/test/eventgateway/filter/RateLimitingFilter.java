package com.test.eventgateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Servlet filter that rate-limits POST /events requests.
 *
 * Uses the Resilience4j "gateway" RateLimiter instance (configured
 * in application.properties). Only applies to event ingestion —
 * reads, health checks, and actuator endpoints pass through freely.
 *
 * Returns 429 Too Many Requests with a Retry-After header when
 * the limit is exceeded.
 */
@Component
@Slf4j
public class RateLimitingFilter extends OncePerRequestFilter {

    private final RateLimiterRegistry rateLimiterRegistry;
    private final ObjectMapper objectMapper;

    public RateLimitingFilter(RateLimiterRegistry registry, ObjectMapper objectMapper) {
        this.rateLimiterRegistry = registry;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain filterChain)
            throws ServletException, IOException {

        if (!isRateLimited(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        RateLimiter rateLimiter = rateLimiterRegistry.rateLimiter("gateway");

        try {
            RateLimiter.waitForPermission(rateLimiter);
            filterChain.doFilter(request, response);
        } catch (RequestNotPermitted e) {
            log.warn("Rate limit exceeded for POST /events from {}",
                    request.getRemoteAddr());

            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader("Retry-After", "1");

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("timestamp", Instant.now().toString());
            body.put("status", 429);
            body.put("error", "Too Many Requests");
            body.put("message", "Rate limit exceeded. Please retry after 1 second.");

            objectMapper.writeValue(response.getOutputStream(), body);
        }
    }

    /**
     * Only rate-limit POST /events — the write path.
     * GETs, health checks, actuator, etc. flow through unthrottled.
     */
    private boolean isRateLimited(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod())
                && "/events".equals(request.getRequestURI());
    }
}
