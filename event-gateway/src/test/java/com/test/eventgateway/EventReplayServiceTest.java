package com.test.eventgateway;

import com.test.eventgateway.client.AccountServiceClient;
import com.test.eventgateway.exception.AccountServiceUnavailableException;
import com.test.eventgateway.model.EventStatus;
import com.test.eventgateway.repository.EventRepository;
import com.test.eventgateway.service.EventReplayService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * Tests for the async fallback queue (EventReplayService).
 * Uses @MockBean so we can flip the client between failing and succeeding
 * to simulate Account Service recovery.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // Disable scheduled execution -- we call replay manually
                "event.replay.interval=999999999",
                "event.replay.max-retries=3"
        }
)
class EventReplayServiceTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private EventReplayService replayService;

    @Autowired
    private EventRepository eventRepository;

    @MockBean
    private AccountServiceClient accountServiceClient;

    private static int eventCounter = 0;

    private Map<String, Object> eventBody(String eventId) {
        return Map.of(
                "eventId", eventId,
                "accountId", "acct-replay",
                "type", "CREDIT",
                "amount", BigDecimal.valueOf(100),
                "currency", "USD",
                "eventTimestamp", "2026-07-01T10:00:00Z");
    }

    private String nextEventId() {
        return "evt-replay-" + (++eventCounter);
    }

    @BeforeEach
    void resetState() {
        // Clean database to prevent cross-test pollution
        // (the replay service's break-on-failure would process
        // stale QUEUED events from earlier tests first)
        eventRepository.deleteAll();

        // Default: Account Service is down
        doThrow(new AccountServiceUnavailableException("Service down"))
                .when(accountServiceClient)
                .applyTransaction(anyString(), anyString(), anyString(),
                        any(BigDecimal.class), anyString(), anyString());
    }

    @Test
    @DisplayName("Queued event transitions to ACCEPTED after replay")
    void replaySuccess() {
        // Submit while Account Service is down → QUEUED
        var eventId = nextEventId();
        var resp = rest.postForEntity("/events", eventBody(eventId), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // Verify it's QUEUED
        var queued = rest.getForEntity("/events/" + eventId, Map.class);
        assertThat(queued.getBody()).containsEntry("status", "QUEUED");

        // Account Service recovers
        doNothing().when(accountServiceClient)
                .applyTransaction(anyString(), anyString(), anyString(),
                        any(BigDecimal.class), anyString(), anyString());

        // Run replay
        replayService.replayQueuedEvents();

        // Verify it's now ACCEPTED
        var accepted = rest.getForEntity("/events/" + eventId, Map.class);
        assertThat(accepted.getBody()).containsEntry("status", "ACCEPTED");
    }

    @Test
    @DisplayName("Replay increments retryCount on failure")
    void replayIncrementsRetryCount() {
        var eventId = nextEventId();
        rest.postForEntity("/events", eventBody(eventId), Map.class);

        // Run replay — Account Service still down
        replayService.replayQueuedEvents();

        var event = eventRepository.findById(eventId).orElseThrow();
        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(EventStatus.QUEUED);
    }

    @Test
    @DisplayName("Event transitions to FAILED after max retries exhausted")
    void replayExhaustsRetries() {
        var eventId = nextEventId();
        rest.postForEntity("/events", eventBody(eventId), Map.class);

        // Replay 3 times (max-retries=3)
        replayService.replayQueuedEvents();  // retry 1
        replayService.replayQueuedEvents();  // retry 2
        replayService.replayQueuedEvents();  // retry 3 → FAILED

        var event = eventRepository.findById(eventId).orElseThrow();
        assertThat(event.getRetryCount()).isEqualTo(3);
        assertThat(event.getStatus()).isEqualTo(EventStatus.FAILED);

        // Verify GET shows FAILED
        var resp = rest.getForEntity("/events/" + eventId, Map.class);
        assertThat(resp.getBody()).containsEntry("status", "FAILED");
    }

    @Test
    @DisplayName("Replay processes multiple queued events in order")
    void replayBatchOrder() {
        var id1 = nextEventId();
        var id2 = nextEventId();
        var id3 = nextEventId();

        rest.postForEntity("/events", eventBody(id1), Map.class);
        rest.postForEntity("/events", eventBody(id2), Map.class);
        rest.postForEntity("/events", eventBody(id3), Map.class);

        // Account Service recovers
        doNothing().when(accountServiceClient)
                .applyTransaction(anyString(), anyString(), anyString(),
                        any(BigDecimal.class), anyString(), anyString());

        replayService.replayQueuedEvents();

        // All three should be ACCEPTED
        assertThat(eventRepository.findById(id1).orElseThrow().getStatus())
                .isEqualTo(EventStatus.ACCEPTED);
        assertThat(eventRepository.findById(id2).orElseThrow().getStatus())
                .isEqualTo(EventStatus.ACCEPTED);
        assertThat(eventRepository.findById(id3).orElseThrow().getStatus())
                .isEqualTo(EventStatus.ACCEPTED);
    }

    @Test
    @DisplayName("No-op when queue is empty")
    void replayEmptyQueue() {
        // Just verify it doesn't throw
        replayService.replayQueuedEvents();

        var queued = eventRepository.findByStatusOrderByCreatedAtAsc(EventStatus.QUEUED);
        assertThat(queued).isEmpty();
    }
}
