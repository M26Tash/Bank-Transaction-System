package com.banksystem.model;

import java.util.Objects;

public record Account(String id, String username, String email, long balanceCents) {

    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(email, "email");
        if (balanceCents < 0) {
            throw new IllegalArgumentException("Balance cannot be negative");
        }
    }

    public Account withBalance(long newBalanceCents) {
        return new Account(id, username, email, newBalanceCents);
    }
}
