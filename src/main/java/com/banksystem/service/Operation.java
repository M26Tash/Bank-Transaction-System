package com.banksystem.service;

import java.util.List;

import com.banksystem.model.Transaction;

public record Operation(String description, List<Transaction> transactions) {

    public Operation {
        transactions = List.copyOf(transactions);
        if (transactions.isEmpty()) {
            throw new IllegalArgumentException("An operation needs at least one transaction");
        }
    }
}
