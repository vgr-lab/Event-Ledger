package com.test.eventgateway;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pact Consumer test — defines the contract the Event Gateway
 * expects from the Account Service.
 *
 * Generates: target/pacts/event-gateway-account-service.json
 *
 * Two interactions:
 *   1. POST /accounts/{id}/transactions → 201 + transaction body
 *   2. GET  /accounts/{id}/balance      → 200 + balance body
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "account-service")
class EventGatewayContractTest {

    // ── Pact definitions ──────────────────────────────────────────

    @Pact(consumer = "event-gateway")
    V4Pact applyTransactionPact(PactDslWithProvider builder) {
        return builder
                .given("account acct-pact-001 may or may not exist")
                .uponReceiving("a CREDIT transaction request")
                .path("/accounts/acct-pact-001/transactions")
                .method("POST")
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .stringType("transactionId", "txn-pact-001")
                        .stringMatcher("type", "CREDIT|DEBIT", "CREDIT")
                        .decimalType("amount", 250.00)
                        .stringType("currency", "USD")
                        .stringType("eventTimestamp", "2026-06-01T10:00:00Z"))
                .willRespondWith()
                .status(201)
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .stringType("transactionId", "txn-pact-001")
                        .stringType("accountId", "acct-pact-001")
                        .stringMatcher("type", "CREDIT|DEBIT", "CREDIT")
                        .decimalType("amount", 250.00)
                        .stringType("currency", "USD")
                        .stringType("eventTimestamp", "2026-06-01T10:00:00Z"))
                .toPact(V4Pact.class);
    }

    @Pact(consumer = "event-gateway")
    V4Pact getBalancePact(PactDslWithProvider builder) {
        return builder
                .given("account acct-pact-002 exists with balance")
                .uponReceiving("a balance query")
                .path("/accounts/acct-pact-002/balance")
                .method("GET")
                .willRespondWith()
                .status(200)
                .headers(Map.of("Content-Type", "application/json"))
                .body(new PactDslJsonBody()
                        .stringType("accountId", "acct-pact-002")
                        .decimalType("balance", 1000.00)
                        .stringType("currency", "USD"))
                .toPact(V4Pact.class);
    }

    // ── Verification tests ────────────────────────────────────────

    @Test
    @DisplayName("Contract: POST transaction returns 201 with expected shape")
    @PactTestFor(pactMethod = "applyTransactionPact")
    void verifyApplyTransaction(MockServer mockServer) {
        var client = RestClient.create(mockServer.getUrl());

        var response = client.post()
                .uri("/accounts/acct-pact-001/transactions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "transactionId", "txn-pact-001",
                        "type", "CREDIT",
                        "amount", 250.00,
                        "currency", "USD",
                        "eventTimestamp", "2026-06-01T10:00:00Z"))
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody())
                .containsKey("transactionId")
                .containsKey("accountId")
                .containsKey("type")
                .containsKey("amount")
                .containsKey("currency");
    }

    @Test
    @DisplayName("Contract: GET balance returns 200 with expected shape")
    @PactTestFor(pactMethod = "getBalancePact")
    void verifyGetBalance(MockServer mockServer) {
        var client = RestClient.create(mockServer.getUrl());

        var response = client.get()
                .uri("/accounts/acct-pact-002/balance")
                .retrieve()
                .toEntity(Map.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .containsKey("accountId")
                .containsKey("balance")
                .containsKey("currency");
    }
}
