package com.test.eventgateway.exception;

/**
 * Thrown when the Account Service is unreachable -- circuit open,
 * retries exhausted, or connection timeout.
 */
public class AccountServiceUnavailableException extends RuntimeException {
    public AccountServiceUnavailableException(String message) {
        super(message);
    }

    public AccountServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
