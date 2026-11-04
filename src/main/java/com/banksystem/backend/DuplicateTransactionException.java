package com.banksystem.backend;

public class DuplicateTransactionException extends BankException {

    private static final long serialVersionUID = 1L;

    public DuplicateTransactionException(String transactionId) {
        super("Transaction " + transactionId + " has already been recorded");
    }
}
