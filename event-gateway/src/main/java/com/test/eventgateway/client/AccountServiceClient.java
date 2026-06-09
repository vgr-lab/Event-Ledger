package com.test.eventgateway.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * REST client for communicating with the Account Service.
 * Handles failures gracefully — the gateway still stores events
 * even if the account service is unreachable.
 */
@Component
@Slf4j
public class AccountServiceClient {

    private final RestClient restClient;

    public AccountServiceClient(
            RestClient.Builder builder,
            @Value("${account-service.url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    /**
     * Applies a transaction to an account via the Account Service.
     *
     * @return true if the transaction was applied successfully
     */
    public boolean applyTransaction(String accountId, String transactionId,
                                     String type, BigDecimal amount,
                                     String currency, String eventTimestamp) {
        try {
            var body = Map.of(
                    "transactionId", transactionId,
                    "type", type,
                    "amount", amount,
                    "currency", currency,
                    "eventTimestamp", eventTimestamp
            );

            restClient.post()
                    .uri("/accounts/{accountId}/transactions", accountId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();

            log.info("Transaction {} applied to account {}", transactionId, accountId);
            return true;

        } catch (Exception e) {
            log.error("Failed to apply transaction {} to account {}: {}",
                    transactionId, accountId, e.getMessage());
            return false;
        }
    }
}
