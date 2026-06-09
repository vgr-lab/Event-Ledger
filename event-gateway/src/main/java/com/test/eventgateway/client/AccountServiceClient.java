package com.test.eventgateway.client;

import com.test.eventgateway.exception.AccountServiceUnavailableException;
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
 * Throws {@link AccountServiceUnavailableException} when the service
 * is unreachable (circuit open, retries exhausted, or timeout).
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
     * @throws AccountServiceUnavailableException if the service is unreachable
     */
    public void applyTransaction(String accountId, String transactionId,
                                  String type, BigDecimal amount,
                                  String currency, String eventTimestamp) {
        Supplier<Void> call = () -> {
            executeTransaction(accountId, transactionId, type, amount, currency, eventTimestamp);
            return null;
        };

        Supplier<Void> resilientCall = CircuitBreaker.decorateSupplier(
                circuitBreaker,
                Retry.decorateSupplier(retry, call));

        try {
            resilientCall.get();
        } catch (CallNotPermittedException e) {
            throw new AccountServiceUnavailableException(
                    "Circuit breaker OPEN -- Account Service is unavailable", e);
        } catch (AccountServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new AccountServiceUnavailableException(
                    "Account Service unreachable after retries: " + e.getMessage(), e);
        }
    }

    /**
     * Fetches the balance for an account from the Account Service.
     *
     * @throws AccountServiceUnavailableException if the service is unreachable
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getBalance(String accountId) {
        Supplier<Map<String, Object>> call = () -> restClient.get()
                .uri("/accounts/{accountId}/balance", accountId)
                .retrieve()
                .body(Map.class);

        Supplier<Map<String, Object>> resilientCall = CircuitBreaker.decorateSupplier(
                circuitBreaker,
                Retry.decorateSupplier(retry, call));

        try {
            return resilientCall.get();
        } catch (CallNotPermittedException e) {
            throw new AccountServiceUnavailableException(
                    "Circuit breaker OPEN -- Account Service is unavailable", e);
        } catch (Exception e) {
            throw new AccountServiceUnavailableException(
                    "Account Service unreachable: " + e.getMessage(), e);
        }
    }

    private void executeTransaction(String accountId, String transactionId,
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
    }
}
