package com.banksystem.backend;

import java.util.List;
import java.util.Optional;

import com.banksystem.model.Account;
import com.banksystem.model.Transaction;

public interface BankRepository {

    void createAccount(Account account);

    Optional<Account> findAccount(String accountId);

    Optional<Account> findAccountByEmail(String email);

    List<Account> listAccounts();

    List<Transaction> transactionsOf(String accountId);

    void record(List<Transaction> transactions);
}
