package com.test.accountservice.service;

import com.test.accountservice.dto.AccountDetailsResponse;
import com.test.accountservice.dto.BalanceResponse;
import com.test.accountservice.dto.TransactionRequest;
import com.test.accountservice.dto.TransactionResponse;
import com.test.accountservice.exception.AccountNotFoundException;
import com.test.accountservice.exception.DuplicateTransactionException;
import com.test.accountservice.model.Account;
import com.test.accountservice.model.Transaction;
import com.test.accountservice.model.TransactionType;
import com.test.accountservice.repository.AccountRepository;
import com.test.accountservice.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

@Service
@RequiredArgsConstructor
@Slf4j
public class AccountManager {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final MeterRegistry meterRegistry;

    /**
     * Applies a transaction to an account with idempotency.
     * Auto-creates the account if it doesn't exist yet.
     */
    @Transactional
    public TransactionResult applyTransaction(String accountId, TransactionRequest request) {
        Timer.Sample timer = Timer.start(meterRegistry);

        // Idempotency check
        var existing = transactionRepository.findByTransactionId(request.getTransactionId());
        if (existing.isPresent()) {
            log.info("Duplicate transaction detected: {}", request.getTransactionId());
            meterRegistry.counter("transactions.processed",
                    "type", existing.get().getType().name(),
                    "status", "DUPLICATE").increment();
            timer.stop(Timer.builder("transactions.processing.time")
                    .tag("outcome", "duplicate")
                    .register(meterRegistry));
            return new TransactionResult(toResponse(existing.get()), true);
        }

        // Auto-create account on first transaction
        accountRepository.findById(accountId).orElseGet(() -> {
            log.info("Creating new account: {}", accountId);
            return accountRepository.save(Account.builder()
                    .accountId(accountId)
                    .currency(request.getCurrency())
                    .build());
        });

        Transaction transaction = Transaction.builder()
                .transactionId(request.getTransactionId())
                .accountId(accountId)
                .type(TransactionType.valueOf(request.getType()))
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .eventTimestamp(Instant.parse(request.getEventTimestamp()))
                .build();

        transactionRepository.save(transaction);

        // Update account timestamp
        accountRepository.findById(accountId).ifPresent(acct -> {
            acct.setUpdatedAt(Instant.now());
            accountRepository.save(acct);
        });

        log.info("Transaction {} applied to account {}: {} {}",
                request.getTransactionId(), accountId,
                request.getType(), request.getAmount());

        meterRegistry.counter("transactions.processed",
                "type", request.getType(),
                "status", "APPLIED").increment();
        timer.stop(Timer.builder("transactions.processing.time")
                .tag("outcome", "applied")
                .register(meterRegistry));

        return new TransactionResult(toResponse(transaction), false);
    }

    public BalanceResponse getBalance(String accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(
                        "Account not found: " + accountId));

        BigDecimal balance = transactionRepository.computeBalance(accountId);

        return BalanceResponse.builder()
                .accountId(accountId)
                .balance(balance)
                .currency(account.getCurrency())
                .build();
    }

    public AccountDetailsResponse getAccountDetails(String accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(
                        "Account not found: " + accountId));

        BigDecimal balance = transactionRepository.computeBalance(accountId);

        var recentTransactions = transactionRepository
                .findByAccountIdOrderByEventTimestampDesc(accountId)
                .stream()
                .limit(20)
                .map(this::toResponse)
                .toList();

        return AccountDetailsResponse.builder()
                .accountId(accountId)
                .balance(balance)
                .currency(account.getCurrency())
                .createdAt(account.getCreatedAt().toString())
                .recentTransactions(recentTransactions)
                .build();
    }

    private TransactionResponse toResponse(Transaction txn) {
        return TransactionResponse.builder()
                .transactionId(txn.getTransactionId())
                .accountId(txn.getAccountId())
                .type(txn.getType().name())
                .amount(txn.getAmount())
                .currency(txn.getCurrency())
                .eventTimestamp(txn.getEventTimestamp().toString())
                .build();
    }

    public record TransactionResult(TransactionResponse response, boolean duplicate) {}
}
