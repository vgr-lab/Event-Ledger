package com.test.eventgateway.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

/**
 * REST client for communicating with the Account Service.
 *
 * Resilience stack (outermost to innermost):
 *   CircuitBreaker -> Retry -> HTTP call (with timeout)
 *
 * - Timeout: connection 2s, read 5s (via RestClient request factory)
 * - Retry: up to 3 attempts with exponential backoff (500ms, 1s)
 * - Circuit Breaker: opens after 50% failure rate over 10 calls,
 *   stays open 30s, then probes with 3 half-open calls
 */
@Component
@Slf4j
public class AccountServiceClient {

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public AccountServiceClient(
            RestClient.Builder builder,
            @Value("${account-service.url}") String baseUrl,
            @Value("${account-service.connect-timeout}") Duration connectTimeout,
            @Value("${account-service.read-timeout}") Duration readTimeout,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry) {

        var requestFactory = ClientHttpRequestFactories.get(
                ClientHttpRequestFactorySettings.DEFAULTS
                        .withConnectTimeout(connectTimeout)
                        .withReadTimeout(readTimeout));

        this.restClient = builder
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();

        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("accountService");
        this.retry = retryRegistry.retry("accountService");

        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn(
                        "Circuit breaker [accountService]: {} -> {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
    }

    /**
     * Applies a transaction to an account via the Account Service.
     *
     * @return true if the transaction was applied successfully
     */
    public boolean applyTransaction(String accountId, String transactionId,
                                     String type, BigDecimal amount,
                                     String currency, String eventTimestamp) {
        Supplier<Boolean> call = () -> executeCall(
                accountId, transactionId, type, amount, currency, eventTimestamp);

        // Decoration order: CircuitBreaker( Retry( call ) )
        Supplier<Boolean> resilientCall = CircuitBreaker.decorateSupplier(
                circuitBreaker,
                Retry.decorateSupplier(retry, call));

        try {
            return resilientCall.get();
        } catch (CallNotPermittedException e) {
            log.warn("Circuit OPEN -- account service call rejected for transaction {}", transactionId);
            return false;
        } catch (Exception e) {
            log.error("All retries exhausted for transaction {} to account {}: {}",
                    transactionId, accountId, e.getMessage());
            return false;
        }
    }

    /**
     * Raw HTTP call -- no resilience wrappers.
     * Exceptions propagate to Retry/CircuitBreaker for proper handling.
     */
    private boolean executeCall(String accountId, String transactionId,
                                String type, BigDecimal amount,
                                String currency, String eventTimestamp) {
        var body = Map.of(
                "transactionId", transactionId,
                "type", type,
                "amount", amount,
                "currency", currency,
                "eventTimestamp", eventTimestamp);

        restClient.post()
                .uri("/accounts/{accountId}/transactions", accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity();

        log.info("Transaction {} applied to account {}", transactionId, accountId);
        return true;
    }
}
