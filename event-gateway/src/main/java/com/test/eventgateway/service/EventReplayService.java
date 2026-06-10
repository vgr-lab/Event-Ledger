package com.test.eventgateway.service;

import com.test.eventgateway.client.AccountServiceClient;
import com.test.eventgateway.exception.AccountServiceUnavailableException;
import com.test.eventgateway.model.Event;
import com.test.eventgateway.model.EventStatus;
import com.test.eventgateway.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Background processor that replays QUEUED events to the Account Service.
 *
 * Runs on a fixed delay (default 30s). For each queued event:
 *   - Success → status transitions to ACCEPTED
 *   - Failure → retryCount incremented; after maxRetries → FAILED
 *
 * Stops processing the batch on the first failure to avoid hammering
 * a service that's still down (circuit breaker handles this too,
 * but belt-and-suspenders is fine here).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventReplayService {

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final MeterRegistry meterRegistry;

    @Value("${event.replay.max-retries:5}")
    private int maxRetries;

    @Scheduled(fixedDelayString = "${event.replay.interval:30000}")
    public void replayQueuedEvents() {
        List<Event> queued = eventRepository
                .findByStatusOrderByCreatedAtAsc(EventStatus.QUEUED);

        if (queued.isEmpty()) {
            return;
        }

        log.info("Replay: processing {} queued event(s)", queued.size());
        int accepted = 0;
        int failed = 0;

        for (Event event : queued) {
            try {
                accountServiceClient.applyTransaction(
                        event.getAccountId(),
                        event.getEventId(),
                        event.getType().name(),
                        event.getAmount(),
                        event.getCurrency(),
                        event.getEventTimestamp().toString());

                event.setStatus(EventStatus.ACCEPTED);
                eventRepository.save(event);
                accepted++;

                meterRegistry.counter("events.replayed",
                        "outcome", "accepted").increment();

                log.info("Replay: event {} delivered successfully", event.getEventId());

            } catch (AccountServiceUnavailableException e) {
                event.setRetryCount(event.getRetryCount() + 1);

                if (event.getRetryCount() >= maxRetries) {
                    event.setStatus(EventStatus.FAILED);
                    failed++;
                    meterRegistry.counter("events.replayed",
                            "outcome", "exhausted").increment();
                    log.warn("Replay: event {} exhausted {} retries — marked FAILED",
                            event.getEventId(), maxRetries);
                } else {
                    meterRegistry.counter("events.replayed",
                            "outcome", "retry").increment();
                    log.warn("Replay: event {} attempt {}/{} failed — will retry",
                            event.getEventId(), event.getRetryCount(), maxRetries);
                }

                eventRepository.save(event);

                // Stop processing batch — service is likely still down
                log.info("Replay: stopping batch (Account Service unavailable). "
                        + "{} accepted, {} failed, {} remaining",
                        accepted, failed, queued.size() - accepted - failed);
                break;
            }
        }

        if (accepted > 0 || failed > 0) {
            log.info("Replay: batch complete — {} accepted, {} failed", accepted, failed);
        }
    }
}
