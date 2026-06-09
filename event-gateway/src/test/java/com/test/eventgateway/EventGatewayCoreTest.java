package com.test.eventgateway;

import com.test.eventgateway.client.AccountServiceClient;
import com.test.eventgateway.exception.AccountServiceUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * Core Gateway tests with a mocked AccountServiceClient.
 * Covers idempotency, out-of-order tolerance, validation,
 * local reads, and graceful degradation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EventGatewayCoreTest {

    @Autowired
    private TestRestTemplate rest;

    @MockBean
    private AccountServiceClient accountServiceClient;

    private Map<String, Object> eventBody(String eventId, String accountId,
                                           String type, double amount,
                                           String timestamp) {
        return Map.of(
                "eventId", eventId,
                "accountId", accountId,
                "type", type,
                "amount", BigDecimal.valueOf(amount),
                "currency", "USD",
                "eventTimestamp", timestamp);
    }

    @Nested
    @DisplayName("Core event processing")
    class CoreProcessing {

        @Test
        @DisplayName("Submit event returns 201 with ACCEPTED status")
        void submitEvent() {
            doNothing().when(accountServiceClient)
                    .applyTransaction(anyString(), anyString(), anyString(),
                            any(BigDecimal.class), anyString(), anyString());

            var resp = rest.postForEntity("/events",
                    eventBody("evt-c1", "acct-c1", "CREDIT", 500.0,
                            "2026-01-01T10:00:00Z"),
                    Map.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody())
                    .containsEntry("eventId", "evt-c1")
                    .containsEntry("status", "ACCEPTED");
        }

        @Test
        @DisplayName("GET /events/{id} retrieves stored event")
        void getEventById() {
            doNothing().when(accountServiceClient)
                    .applyTransaction(anyString(), anyString(), anyString(),
                            any(BigDecimal.class), anyString(), anyString());

            rest.postForEntity("/events",
                    eventBody("evt-get1", "acct-get", "CREDIT", 100.0,
                            "2026-01-01T10:00:00Z"),
                    Map.class);

            var resp = rest.getForEntity("/events/evt-get1", Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .containsEntry("eventId", "evt-get1")
                    .containsEntry("accountId", "acct-get")
                    .containsEntry("status", "ACCEPTED");
        }

        @Test
        @DisplayName("GET /events/{id} returns 404 for unknown event")
        void getEventNotFound() {
            var resp = rest.getForEntity("/events/evt-unknown-999", Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("Idempotency")
    class Idempotency {

        @Test
        @DisplayName("Duplicate eventId returns 200 OK with original data")
        void duplicateEvent() {
            doNothing().when(accountServiceClient)
                    .applyTransaction(anyString(), anyString(), anyString(),
                            any(BigDecimal.class), anyString(), anyString());

            var body = eventBody("evt-dup", "acct-dup", "CREDIT", 250.0,
                    "2026-02-01T10:00:00Z");

            var first = rest.postForEntity("/events", body, Map.class);
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            var second = rest.postForEntity("/events", body, Map.class);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(second.getBody())
                    .containsEntry("eventId", "evt-dup")
                    .containsEntry("status", "ACCEPTED");
        }
    }

    @Nested
    @DisplayName("Out-of-order tolerance")
    class OutOfOrder {

        @Test
        @DisplayName("Events stored and returned in chronological order")
        void chronologicalOrder() {
            doNothing().when(accountServiceClient)
                    .applyTransaction(anyString(), anyString(), anyString(),
                            any(BigDecimal.class), anyString(), anyString());

            // Submit in reverse chronological order
            rest.postForEntity("/events",
                    eventBody("evt-ooo-3", "acct-ooo", "DEBIT", 50.0,
                            "2026-03-03T10:00:00Z"),
                    Map.class);
            rest.postForEntity("/events",
                    eventBody("evt-ooo-1", "acct-ooo", "CREDIT", 200.0,
                            "2026-03-01T10:00:00Z"),
                    Map.class);
            rest.postForEntity("/events",
                    eventBody("evt-ooo-2", "acct-ooo", "CREDIT", 100.0,
                            "2026-03-02T10:00:00Z"),
                    Map.class);

            var resp = rest.getForEntity("/events?account=acct-ooo", List.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

            var events = resp.getBody();
            assertThat(events).hasSize(3);

            // Verify chronological ordering despite out-of-order submission
            var first = (Map<String, Object>) events.get(0);
            var second = (Map<String, Object>) events.get(1);
            var third = (Map<String, Object>) events.get(2);
            assertThat(first.get("eventId")).isEqualTo("evt-ooo-1");
            assertThat(second.get("eventId")).isEqualTo("evt-ooo-2");
            assertThat(third.get("eventId")).isEqualTo("evt-ooo-3");
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("Missing required fields returns 400")
        void missingFields() {
            var resp = rest.postForEntity("/events",
                    Map.of("eventId", "evt-bad"), Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Invalid transaction type returns 400")
        void invalidType() {
            var resp = rest.postForEntity("/events",
                    eventBody("evt-bad-type", "acct-v", "TRANSFER", 100.0,
                            "2026-01-01T10:00:00Z"),
                    Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Zero amount returns 400")
        void zeroAmount() {
            var resp = rest.postForEntity("/events",
                    eventBody("evt-zero", "acct-v", "CREDIT", 0.0,
                            "2026-01-01T10:00:00Z"),
                    Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Invalid timestamp format returns 400")
        void badTimestamp() {
            var resp = rest.postForEntity("/events",
                    Map.of("eventId", "evt-bad-ts", "accountId", "acct-v",
                            "type", "CREDIT", "amount", 100,
                            "currency", "USD", "eventTimestamp", "not-a-date"),
                    Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    @DisplayName("Graceful degradation")
    class GracefulDegradation {

        @Test
        @DisplayName("POST /events returns 503 when Account Service is down")
        void postReturns503WhenDown() {
            doThrow(new AccountServiceUnavailableException("Service down"))
                    .when(accountServiceClient)
                    .applyTransaction(anyString(), anyString(), anyString(),
                            any(BigDecimal.class), anyString(), anyString());

            var resp = rest.postForEntity("/events",
                    eventBody("evt-503", "acct-503", "CREDIT", 100.0,
                            "2026-04-01T10:00:00Z"),
                    Map.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(resp.getBody())
                    .containsEntry("status", 503)
                    .containsEntry("eventId", "evt-503")
                    .containsEntry("eventStatus", "FAILED")
                    .containsKey("message");
        }

        @Test
        @DisplayName("GET /events still works when Account Service is down")
        void getEventsWorksWhenDown() {
            // Submit an event that succeeds
            doNothing().when(accountServiceClient)
                    .applyTransaction(anyString(), anyString(), anyString(),
                            any(BigDecimal.class), anyString(), anyString());
            rest.postForEntity("/events",
                    eventBody("evt-local", "acct-local", "CREDIT", 100.0,
                            "2026-05-01T10:00:00Z"),
                    Map.class);

            // Now simulate Account Service down -- reads should still work
            doThrow(new AccountServiceUnavailableException("Service down"))
                    .when(accountServiceClient)
                    .applyTransaction(anyString(), anyString(), anyString(),
                            any(BigDecimal.class), anyString(), anyString());

            var byId = rest.getForEntity("/events/evt-local", Map.class);
            assertThat(byId.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(byId.getBody()).containsEntry("eventId", "evt-local");

            var byAccount = rest.getForEntity("/events?account=acct-local", List.class);
            assertThat(byAccount.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(byAccount.getBody()).isNotEmpty();
        }

        @Test
        @DisplayName("Balance proxy returns 503 when Account Service is down")
        void balanceReturns503WhenDown() {
            doThrow(new AccountServiceUnavailableException("Service down"))
                    .when(accountServiceClient).getBalance(anyString());

            var resp = rest.getForEntity("/accounts/acct-x/balance", Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(resp.getBody())
                    .containsEntry("status", 503)
                    .containsEntry("accountId", "acct-x")
                    .containsKey("message");
        }
    }

    @Nested
    @DisplayName("Health check")
    class HealthCheck {

        @Test
        @DisplayName("Health endpoint returns UP with database info")
        void healthUp() {
            var resp = rest.getForEntity("/health", Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .containsEntry("status", "UP")
                    .containsEntry("service", "event-gateway")
                    .containsKey("database");
        }
    }
}
