package com.test.accountservice.repository;

import com.test.accountservice.model.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByTransactionId(String transactionId);

    List<Transaction> findByAccountIdOrderByEventTimestampDesc(String accountId);

    /**
     * Computes the net balance for an account:
     * SUM(CREDIT amounts) - SUM(DEBIT amounts).
     * Returns 0 if no transactions exist.
     */
    @Query("""
            SELECT COALESCE(
                SUM(CASE WHEN t.type = 'CREDIT' THEN t.amount ELSE 0 END) -
                SUM(CASE WHEN t.type = 'DEBIT'  THEN t.amount ELSE 0 END),
                0)
            FROM Transaction t
            WHERE t.accountId = :accountId
            """)
    BigDecimal computeBalance(@Param("accountId") String accountId);
}
