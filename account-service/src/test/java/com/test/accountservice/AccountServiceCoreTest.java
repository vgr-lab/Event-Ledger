package com.test.accountservice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Account Service.
 * Exercises transactions, idempotency, balance computation, and validation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountServiceCoreTest {

    @Autowired
    private TestRestTemplate rest;

    private Map<String, Object> txnBody(String txnId, String type,
                                         double amount, String timestamp) {
        return Map.of(
                "transactionId", txnId,
                "type", type,
                "amount", BigDecimal.valueOf(amount),
                "currency", "USD",
                "eventTimestamp", timestamp);
    }

    @Nested
    @DisplayName("Transaction processing")
    class Transactions {

        @Test
        @DisplayName("Apply CREDIT transaction and check balance")
        void creditAndBalance() {
            var resp = rest.postForEntity(
                    "/accounts/acct-t1/transactions",
                    txnBody("txn-1", "CREDIT", 500.00, "2026-01-01T10:00:00Z"),
                    Map.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(resp.getBody()).containsEntry("transactionId", "txn-1");

            var balance = rest.getForEntity("/accounts/acct-t1/balance", Map.class);
            assertThat(balance.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(((Number) balance.getBody().get("balance")).doubleValue())
                    .isEqualTo(500.0);
        }

        @Test
        @DisplayName("CREDIT then DEBIT computes correct balance")
        void creditThenDebit() {
            rest.postForEntity("/accounts/acct-t2/transactions",
                    txnBody("txn-cd-1", "CREDIT", 1000.00, "2026-01-01T10:00:00Z"),
                    Map.class);
            rest.postForEntity("/accounts/acct-t2/transactions",
                    txnBody("txn-cd-2", "DEBIT", 350.00, "2026-01-01T11:00:00Z"),
                    Map.class);

            var balance = rest.getForEntity("/accounts/acct-t2/balance", Map.class);
            assertThat(((Number) balance.getBody().get("balance")).doubleValue())
                    .isEqualTo(650.0);
        }

        @Test
        @DisplayName("Auto-creates account on first transaction")
        void autoCreateAccount() {
            rest.postForEntity("/accounts/acct-new/transactions",
                    txnBody("txn-new-1", "CREDIT", 100.00, "2026-02-01T10:00:00Z"),
                    Map.class);

            var details = rest.getForEntity("/accounts/acct-new", Map.class);
            assertThat(details.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(details.getBody()).containsEntry("accountId", "acct-new");
        }
    }

    @Nested
    @DisplayName("Idempotency")
    class Idempotency {

        @Test
        @DisplayName("Duplicate transactionId returns 200 OK with original data")
        void duplicateTransaction() {
            var body = txnBody("txn-dup", "CREDIT", 250.00, "2026-03-01T10:00:00Z");

            var first = rest.postForEntity("/accounts/acct-dup/transactions",
                    body, Map.class);
            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            var second = rest.postForEntity("/accounts/acct-dup/transactions",
                    body, Map.class);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(second.getBody()).containsEntry("transactionId", "txn-dup");
        }

        @Test
        @DisplayName("Duplicate does not double-count in balance")
        void duplicateDoesNotDoubleCount() {
            var body = txnBody("txn-dup2", "CREDIT", 100.00, "2026-03-02T10:00:00Z");

            rest.postForEntity("/accounts/acct-dup2/transactions", body, Map.class);
            rest.postForEntity("/accounts/acct-dup2/transactions", body, Map.class);

            var balance = rest.getForEntity("/accounts/acct-dup2/balance", Map.class);
            assertThat(((Number) balance.getBody().get("balance")).doubleValue())
                    .isEqualTo(100.0);
        }
    }

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("Missing required fields returns 400")
        void missingFields() {
            var resp = rest.postForEntity("/accounts/acct-v/transactions",
                    Map.of(), Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Invalid type returns 400")
        void invalidType() {
            var resp = rest.postForEntity("/accounts/acct-v/transactions",
                    txnBody("txn-v1", "INVALID", 100.00, "2026-04-01T10:00:00Z"),
                    Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("Non-existent account returns 404 on balance query")
        void accountNotFound() {
            var resp = rest.getForEntity("/accounts/acct-nonexistent/balance", Map.class);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
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
            assertThat(resp.getBody()).containsEntry("status", "UP");
            assertThat(resp.getBody()).containsEntry("service", "account-service");
            assertThat(resp.getBody()).containsKey("database");
        }
    }
}
