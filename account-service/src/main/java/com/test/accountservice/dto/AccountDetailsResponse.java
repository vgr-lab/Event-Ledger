package com.test.accountservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountDetailsResponse {

    private String accountId;
    private BigDecimal balance;
    private String currency;
    private String createdAt;
    private List<TransactionResponse> recentTransactions;
}
