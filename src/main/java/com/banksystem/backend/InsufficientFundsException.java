package com.banksystem.backend;

public class InsufficientFundsException extends BankException {

    private static final long serialVersionUID = 1L;

    private final String accountId;

    public InsufficientFundsException(String accountId) {
        super("Insufficient funds");
        this.accountId = accountId;
    }

    public String accountId() {
        return accountId;
    }
}
