package com.banksystem.backend.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.banksystem.backend.BankException;
import com.banksystem.backend.DuplicateTransactionException;
import com.banksystem.backend.InsufficientFundsException;
import com.banksystem.backend.local.CredentialStore.Credential;
import com.banksystem.model.Account;
import com.banksystem.model.Transaction;
import com.banksystem.model.Transaction.Type;

class SqliteDatabaseTest {

    private static final LocalDateTime TIME = LocalDateTime.of(2025, 5, 17, 14, 30, 15, 123_000_000);

    private static Transaction deposit(String id, String account, long cents) {
        return new Transaction(id, account, Type.DEPOSIT, cents, "Deposit", TIME, null, null, null);
    }

    private static SqliteDatabase withAccounts() {
        SqliteDatabase db = SqliteDatabase.inMemory();
        db.createAccount(new Account("a", "alice", "a@x.com", 0));
        db.createAccount(new Account("b", "bob", "b@x.com", 0));
        return db;
    }

    @Test
    void accountsAndCredentials() {
        SqliteDatabase db = withAccounts();
        assertEquals("alice", db.findAccount("a").orElseThrow().username());
        assertEquals("b", db.findAccountByEmail("b@x.com").orElseThrow().id());
        assertTrue(db.findAccount("nobody").isEmpty());
        assertEquals(List.of("alice", "bob"), db.listAccounts().stream().map(Account::username).toList());
        assertThrows(BankException.class, () -> db.createAccount(new Account("c", "carol", "a@x.com", 0)));

        db.saveCredential(new Credential("a@x.com", "a", "hash"));
        assertEquals("a", db.findCredential("a@x.com").orElseThrow().userId());
        assertTrue(db.findCredential("b@x.com").isEmpty());
        assertThrows(BankException.class, () -> db.saveCredential(new Credential("a@x.com", "z", "h")));
    }

    @Test
    void recordUpdatesBalancesAndKeepsEveryEntry() {
        SqliteDatabase db = withAccounts();
        Transaction t = deposit("t1", "a", 500);
        db.record(List.of(t));
        assertEquals(500L, db.findAccount("a").orElseThrow().balanceCents());

        Transaction reversal = new Transaction("t1-reversal", "a", Type.REVERSAL_OUT, 500, "Undo: Deposit",
                TIME, null, null, "t1");
        db.record(List.of(reversal));
        assertEquals(0L, db.findAccount("a").orElseThrow().balanceCents());
        assertEquals(List.of(t, reversal), db.transactionsOf("a"));

        assertThrows(DuplicateTransactionException.class, () -> db.record(List.of(reversal)));
        assertEquals(0L, db.findAccount("a").orElseThrow().balanceCents());
    }

    @Test
    void failedTransferIsRolledBack() {
        SqliteDatabase db = withAccounts();
        db.record(List.of(deposit("t0", "a", 100)));

        Transaction out = new Transaction("t1", "a", Type.TRANSFER_OUT, 150, "x", TIME, "b", "t2", null);
        Transaction in = new Transaction("t2", "b", Type.TRANSFER_IN, 150, "x", TIME, "a", "t1", null);
        InsufficientFundsException e = assertThrows(InsufficientFundsException.class,
                () -> db.record(List.of(in, out)));

        assertEquals("a", e.accountId());
        assertEquals(100L, db.findAccount("a").orElseThrow().balanceCents());
        assertEquals(0L, db.findAccount("b").orElseThrow().balanceCents());
        assertTrue(db.transactionsOf("b").isEmpty());

        db.record(List.of(deposit("t3", "b", 10)));
        assertEquals(10L, db.findAccount("b").orElseThrow().balanceCents());
    }

    @Test
    void unknownAccountIsRejected() {
        SqliteDatabase db = withAccounts();
        assertThrows(BankException.class, () -> db.record(List.of(deposit("t1", "ghost", 100))));
        assertTrue(db.transactionsOf("ghost").isEmpty());
    }

    @Test
    void dataSurvivesReopening() throws IOException {
        Path file = Files.createTempDirectory("bank-test").resolve("sub/dir/bank.db");
        Transaction d = new Transaction("t1", "a", Type.DEPOSIT, 500, "Line1\nLine2 'quoted'", TIME, null, null, null);
        Transaction out = new Transaction("t2", "a", Type.TRANSFER_OUT, 100, "Rent", TIME, "b", "t3", null);
        Transaction in = new Transaction("t3", "b", Type.TRANSFER_IN, 100, "Rent", TIME, "a", "t2", null);

        try (SqliteDatabase db = SqliteDatabase.open(file)) {
            db.createAccount(new Account("a", "Alice", "a@x.com", 0));
            db.createAccount(new Account("b", "Bob", "b@x.com", 0));
            db.saveCredential(new Credential("a@x.com", "a", "hash$1"));
            db.record(List.of(d));
            db.record(List.of(out, in));
        }

        try (SqliteDatabase reopened = SqliteDatabase.open(file)) {
            assertEquals(400L, reopened.findAccount("a").orElseThrow().balanceCents());
            assertEquals(100L, reopened.findAccount("b").orElseThrow().balanceCents());
            assertEquals("a", reopened.findCredential("a@x.com").orElseThrow().userId());
            assertEquals(List.of(d, out), reopened.transactionsOf("a"));
            assertEquals(List.of(in), reopened.transactionsOf("b"));
        }
    }

    @Test
    void twoConnectionsShareOneFile() throws IOException {
        Path file = Files.createTempDirectory("bank-test").resolve("bank.db");
        try (SqliteDatabase first = SqliteDatabase.open(file); SqliteDatabase second = SqliteDatabase.open(file)) {
            first.createAccount(new Account("a", "Alice", "a@x.com", 0));
            first.record(List.of(deposit("t1", "a", 300)));

            second.record(List.of(new Transaction("t2", "a", Type.WITHDRAWAL, 200, "w", TIME, null, null, null)));
            assertThrows(InsufficientFundsException.class, () -> first.record(
                    List.of(new Transaction("t3", "a", Type.WITHDRAWAL, 200, "w", TIME, null, null, null))));
            assertEquals(100L, first.findAccount("a").orElseThrow().balanceCents());
        }
    }
}
