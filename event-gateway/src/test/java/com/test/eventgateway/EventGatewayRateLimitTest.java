package com.test.eventgateway;

import com.test.eventgateway.client.AccountServiceClient;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

/**
 * Rate limiting tests with a deliberately tight limit (3 req/period)
 * so we can trigger 429 without sending 50+ requests.
 *
 * Uses a long refresh period (10 min) so permits never auto-refill
 * during a test. Each test replaces the rate limiter instance in the
 * registry with a fresh one to get a clean permit bucket.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Disable replay scheduler
                "event.replay.interval=999999999"
        }
)
class EventGatewayRateLimitTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private RateLimiterRegistry rateLimiterRegistry;

    @MockBean
    private AccountServiceClient accountServiceClient;

    private static final AtomicInteger idCounter = new AtomicInteger(0);

    /** Fresh rate limiter with 3 permits and no auto-refill during tests. */
    private static final RateLimiterConfig TEST_CONFIG = RateLimiterConfig.custom()
            .limitForPeriod(3)
            .limitRefreshPeriod(Duration.ofMinutes(10))
            .timeoutDuration(Duration.ZERO)
            .build();

    @BeforeEach
    void setup() {
        doNothing().when(accountServiceClient)
                .applyTransaction(anyString(), anyString(), anyString(),
                        any(BigDecimal.class), anyString(), anyString());

        // Replace the "gateway" rate limiter with a fresh instance each test.
        // The filter looks up by name on every request, so it sees this new one.
        rateLimiterRegistry.remove("gateway");
        rateLimiterRegistry.rateLimiter("gateway", TEST_CONFIG);
    }

    private Map<String, Object> eventBody(String eventId) {
        return Map.of(
                "eventId", eventId,
                "accountId", "acct-rl",
                "type", "CREDIT",
                "amount", BigDecimal.valueOf(50),
                "currency", "USD",
                "eventTimestamp", "2026-08-01T10:00:00Z");
    }

    private String nextEventId() {
        return "evt-rl-" + idCounter.incrementAndGet();
    }

    @Test
    @DisplayName("Requests within limit succeed normally")
    void withinLimit() {
        for (int i = 0; i < 3; i++) {
            var resp = rest.postForEntity("/events",
                    eventBody(nextEventId()), Map.class);
            assertThat(resp.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("Requests exceeding limit receive 429 Too Many Requests")
    void exceedsLimit() {
        // Burn through the 3-request limit
        for (int i = 0; i < 3; i++) {
            rest.postForEntity("/events", eventBody(nextEventId()), Map.class);
        }

        // The 4th request should be rejected
        var resp = rest.postForEntity("/events",
                eventBody(nextEventId()), Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(resp.getBody())
                .containsEntry("status", 429)
                .containsEntry("error", "Too Many Requests")
                .containsKey("message");
    }

    @Test
    @DisplayName("GET endpoints are not rate-limited")
    void getEndpointsNotLimited() {
        // Burn through the POST limit
        for (int i = 0; i < 3; i++) {
            rest.postForEntity("/events", eventBody(nextEventId()), Map.class);
        }

        // GETs should still work even though POST limit is exhausted
        var health = rest.getForEntity("/health", Map.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);

        var events = rest.getForEntity("/events?account=acct-rl", Object.class);
        assertThat(events.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Rate limit recovers when permits are refreshed")
    void recoversAfterRefresh() {
        // Exhaust the limit
        for (int i = 0; i < 3; i++) {
            rest.postForEntity("/events", eventBody(nextEventId()), Map.class);
        }

        var blocked = rest.postForEntity("/events",
                eventBody(nextEventId()), Map.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // Simulate refresh by replacing with a fresh rate limiter
        rateLimiterRegistry.remove("gateway");
        rateLimiterRegistry.rateLimiter("gateway", TEST_CONFIG);

        // Should work again
        var resp = rest.postForEntity("/events",
                eventBody(nextEventId()), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }
}
