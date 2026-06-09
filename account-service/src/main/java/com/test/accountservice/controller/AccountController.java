package com.test.accountservice.controller;

import com.test.accountservice.dto.AccountDetailsResponse;
import com.test.accountservice.dto.BalanceResponse;
import com.test.accountservice.dto.TransactionRequest;
import com.test.accountservice.dto.TransactionResponse;
import com.test.accountservice.service.AccountManager;
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
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class AccountController {

    private final AccountManager accountManager;
    private final DataSource dataSource;

    @Value("${spring.application.name}")
    private String serviceName;

    @PostMapping("/accounts/{accountId}/transactions")
    public ResponseEntity<TransactionResponse> applyTransaction(
            @PathVariable String accountId,
            @Valid @RequestBody TransactionRequest request) {
        log.info("Received transaction {} for account {}", request.getTransactionId(), accountId);
        var result = accountManager.applyTransaction(accountId, request);
        HttpStatus status = result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
        log.info("Transaction {} applied — duplicate={}", request.getTransactionId(), result.duplicate());
        return ResponseEntity.status(status).body(result.response());
    }

    @GetMapping("/accounts/{accountId}/balance")
    public ResponseEntity<BalanceResponse> getBalance(@PathVariable String accountId) {
        return ResponseEntity.ok(accountManager.getBalance(accountId));
    }

    @GetMapping("/accounts/{accountId}")
    public ResponseEntity<AccountDetailsResponse> getAccountDetails(
            @PathVariable String accountId) {
        return ResponseEntity.ok(accountManager.getAccountDetails(accountId));
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
