package com.test.eventgateway.controller;

import com.test.eventgateway.client.AccountServiceClient;
import com.test.eventgateway.dto.EventRequest;
import com.test.eventgateway.dto.EventResponse;
import com.test.eventgateway.exception.AccountServiceUnavailableException;
import com.test.eventgateway.service.EventService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class EventController {

    private final EventService eventService;
    private final AccountServiceClient accountServiceClient;
    private final DataSource dataSource;

    @Value("${spring.application.name}")
    private String serviceName;

    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> submitEvent(@Valid @RequestBody EventRequest request) {
        log.info("Received event {} for account {}", request.getEventId(), request.getAccountId());
        var result = eventService.processEvent(request);

        if (result.serviceQueued()) {
            log.info("Event {} queued for deferred processing -- Account Service unavailable",
                    request.getEventId());
            var body = new LinkedHashMap<String, Object>();
            body.put("eventId", result.response().getEventId());
            body.put("accountId", result.response().getAccountId());
            body.put("status", result.response().getStatus());
            body.put("message", "Event accepted for deferred processing. "
                    + "The Account Service is temporarily unavailable.");
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
        }

        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        log.info("Event {} processed -- status={}, duplicate={}",
                request.getEventId(), result.response().getStatus(), result.duplicate());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", result.response().getEventId());
        body.put("accountId", result.response().getAccountId());
        body.put("type", result.response().getType());
        body.put("amount", result.response().getAmount());
        body.put("currency", result.response().getCurrency());
        body.put("eventTimestamp", result.response().getEventTimestamp());
        body.put("metadata", result.response().getMetadata());
        body.put("status", result.response().getStatus());
        return ResponseEntity.status(status).body(body);
    }

    /**
     * Local-only: reads from Gateway's own database.
     * Works even when Account Service is down.
     */
    @GetMapping("/events/{id}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable String id) {
        return ResponseEntity.ok(eventService.getEvent(id));
    }

    /**
     * Local-only: reads from Gateway's own database.
     * Works even when Account Service is down.
     */
    @GetMapping("/events")
    public ResponseEntity<List<EventResponse>> getEventsByAccount(
            @RequestParam("account") String accountId) {
        return ResponseEntity.ok(eventService.getEventsByAccount(accountId));
    }

    /**
     * Proxies balance queries to the Account Service.
     * Returns 503 with a clear error when unreachable.
     */
    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<Map<String, Object>> getBalance(@PathVariable String accountId) {
        try {
            return ResponseEntity.ok(accountServiceClient.getBalance(accountId));
        } catch (AccountServiceUnavailableException e) {
            log.warn("Balance query failed for account {}: {}", accountId, e.getMessage());
            var body = new LinkedHashMap<String, Object>();
            body.put("timestamp", Instant.now().toString());
            body.put("status", 503);
            body.put("error", "Service Unavailable");
            body.put("message", "Account Service is unreachable. Balance data is unavailable.");
            body.put("accountId", accountId);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        var body = new LinkedHashMap<String, Object>();
        body.put("status", "UP");
        body.put("service", serviceName);
        body.put("uptime", ManagementFactory.getRuntimeMXBean().getUptime() + "ms");

        try (var conn = dataSource.getConnection()) {
            body.put("database", Map.of(
                    "status", "UP",
                    "product", conn.getMetaData().getDatabaseProductName(),
                    "url", conn.getMetaData().getURL()
            ));
        } catch (Exception e) {
            body.put("status", "DOWN");
            body.put("database", Map.of("status", "DOWN", "error", e.getMessage()));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }

        return ResponseEntity.ok(body);
    }
}
