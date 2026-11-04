package com.banksystem.model;

import java.time.LocalDateTime;
import java.util.Objects;

public record Transaction(
        String id,
        String accountId,
        Type type,
        long amountCents,
        String title,
        LocalDateTime timestamp,
        String counterpartyId,
        String linkedTransactionId,
        String reversalOf) {

    public enum Type {
        DEPOSIT(+1, "Deposit"),
        WITHDRAWAL(-1, "Withdrawal"),
        TRANSFER_IN(+1, "Transfer in"),
        TRANSFER_OUT(-1, "Transfer out"),
        REVERSAL_IN(+1, "Reversal"),
        REVERSAL_OUT(-1, "Reversal");

        private final int sign;
        private final String label;

        Type(int sign, String label) {
            this.sign = sign;
            this.label = label;
        }

        public int sign() {
            return sign;
        }

        public String label() {
            return label;
        }

        public boolean isTransfer() {
            return this == TRANSFER_IN || this == TRANSFER_OUT;
        }

        public boolean isReversal() {
            return this == REVERSAL_IN || this == REVERSAL_OUT;
        }

        public Type reversalType() {
            return sign > 0 ? REVERSAL_OUT : REVERSAL_IN;
        }
    }

    public Transaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(timestamp, "timestamp");
        if (amountCents <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (type.isTransfer() && (counterpartyId == null || linkedTransactionId == null)) {
            throw new IllegalArgumentException("A transfer needs a counterparty and a linked transaction");
        }
        if (type.isReversal() != (reversalOf != null)) {
            throw new IllegalArgumentException(
                    "Only reversal entries (and all of them) must reference the cancelled entry");
        }
    }

    public long signedAmountCents() {
        return type.sign() * amountCents;
    }
}
