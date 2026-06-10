package com.test.eventgateway.service;

import com.test.eventgateway.client.AccountServiceClient;
import com.test.eventgateway.dto.EventRequest;
import com.test.eventgateway.dto.EventResponse;
import com.test.eventgateway.exception.AccountServiceUnavailableException;
import com.test.eventgateway.exception.DuplicateEventException;
import com.test.eventgateway.exception.EventNotFoundException;
import com.test.eventgateway.model.Event;
import com.test.eventgateway.model.EventStatus;
import com.test.eventgateway.model.TransactionType;
import com.test.eventgateway.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventService {

    private final EventRepository eventRepository;
    private final AccountServiceClient accountServiceClient;
    private final MeterRegistry meterRegistry;

    /**
     * Processes an incoming event with idempotency.
     * If the eventId already exists, returns the original event (duplicate).
     * Otherwise, persists the event and calls the Account Service.
     */
    @Transactional
    public EventResult processEvent(EventRequest request) {
        Timer.Sample timer = Timer.start(meterRegistry);

        // Check for duplicate
        var existing = eventRepository.findById(request.getEventId());
        if (existing.isPresent()) {
            log.info("Duplicate event detected: {}", request.getEventId());
            meterRegistry.counter("events.processed",
                    "type", existing.get().getType().name(),
                    "status", "DUPLICATE").increment();
            timer.stop(Timer.builder("events.processing.time")
                    .tag("outcome", "duplicate")
                    .register(meterRegistry));
            return new EventResult(toResponse(existing.get()), true, false);
        }

        Instant timestamp = parseTimestamp(request.getEventTimestamp());

        Event event = Event.builder()
                .eventId(request.getEventId())
                .accountId(request.getAccountId())
                .type(TransactionType.valueOf(request.getType()))
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .eventTimestamp(timestamp)
                .metadata(request.getMetadata())
                .status(EventStatus.PENDING)
                .build();

        eventRepository.save(event);

        // Call Account Service -- queue for retry if unreachable
        boolean serviceQueued = false;
        try {
            accountServiceClient.applyTransaction(
                    event.getAccountId(),
                    event.getEventId(),
                    event.getType().name(),
                    event.getAmount(),
                    event.getCurrency(),
                    request.getEventTimestamp()
            );
            event.setStatus(EventStatus.ACCEPTED);
        } catch (AccountServiceUnavailableException e) {
            log.warn("Account Service unavailable for event {}: {} -- queuing for retry",
                    event.getEventId(), e.getMessage());
            event.setStatus(EventStatus.QUEUED);
            serviceQueued = true;
        }

        eventRepository.save(event);

        meterRegistry.counter("events.processed",
                "type", event.getType().name(),
                "status", event.getStatus().name()).increment();
        timer.stop(Timer.builder("events.processing.time")
                .tag("outcome", event.getStatus().name().toLowerCase())
                .register(meterRegistry));

        return new EventResult(toResponse(event), false, serviceQueued);
    }

    public EventResponse getEvent(String eventId) {
        return eventRepository.findById(eventId)
                .map(this::toResponse)
                .orElseThrow(() -> new EventNotFoundException(
                        "Event not found: " + eventId));
    }

    public List<EventResponse> getEventsByAccount(String accountId) {
        return eventRepository.findByAccountIdOrderByEventTimestampAsc(accountId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private EventResponse toResponse(Event event) {
        return EventResponse.builder()
                .eventId(event.getEventId())
                .accountId(event.getAccountId())
                .type(event.getType().name())
                .amount(event.getAmount())
                .currency(event.getCurrency())
                .eventTimestamp(event.getEventTimestamp().toString())
                .metadata(event.getMetadata())
                .status(event.getStatus().name())
                .build();
    }

    private Instant parseTimestamp(String timestamp) {
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Invalid eventTimestamp format. Expected ISO 8601 (e.g. 2026-05-15T14:02:11Z)");
        }
    }

    /**
     * Wraps the response with a flag indicating if this was a duplicate.
     */
    public record EventResult(EventResponse response, boolean duplicate,
                               boolean serviceQueued) {}
}
