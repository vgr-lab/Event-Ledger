package com.test.eventgateway.controller;

import com.test.eventgateway.dto.EventRequest;
import com.test.eventgateway.dto.EventResponse;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class EventController {

    private final EventService eventService;
    private final DataSource dataSource;

    @Value("${spring.application.name}")
    private String serviceName;

    @PostMapping("/events")
    public ResponseEntity<EventResponse> submitEvent(@Valid @RequestBody EventRequest request) {
        log.info("Received event {} for account {}", request.getEventId(), request.getAccountId());
        var result = eventService.processEvent(request);

        // Duplicate → 200 OK with original event; New → 201 Created
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        log.info("Event {} processed — status={}, duplicate={}",
                request.getEventId(), result.response().getStatus(), result.duplicate());
        return ResponseEntity.status(status).body(result.response());
    }

    @GetMapping("/events/{id}")
    public ResponseEntity<EventResponse> getEvent(@PathVariable String id) {
        return ResponseEntity.ok(eventService.getEvent(id));
    }

    @GetMapping("/events")
    public ResponseEntity<List<EventResponse>> getEventsByAccount(
            @RequestParam("account") String accountId) {
        return ResponseEntity.ok(eventService.getEventsByAccount(accountId));
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
