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

import com.banksystem.backend.DuplicateTransactionException;
import com.banksystem.backend.InsufficientFundsException;
import com.banksystem.model.Account;
import com.banksystem.model.Transaction;
import com.banksystem.model.Transaction.Type;

class LocalDatabaseTest {

    private static final LocalDateTime TIME = LocalDateTime.of(2025, 5, 17, 14, 30, 15, 123_000_000);

    private static Transaction deposit(String id, String account, long cents) {
        return new Transaction(id, account, Type.DEPOSIT, cents, "Deposit", TIME, null, null, null);
    }

    @Test
    void recordUpdatesBalancesAndKeepsEveryEntry() {
        LocalDatabase db = LocalDatabase.inMemory();
        db.createAccount(new Account("a", "alice", "a@x.com", 0));
        Transaction t = deposit("t1", "a", 500);
        db.record(List.of(t));
        assertEquals(500L, db.findAccount("a").orElseThrow().balanceCents());

        Transaction reversal = new Transaction("t1-reversal", "a", Type.REVERSAL_OUT, 500, "Undo: Deposit",
                TIME, null, null, "t1");
        db.record(List.of(reversal));
        assertEquals(0L, db.findAccount("a").orElseThrow().balanceCents());
        assertEquals(List.of(t, reversal), db.transactionsOf("a"));

        // The same reversal can't be booked twice.
        assertThrows(DuplicateTransactionException.class, () -> db.record(List.of(reversal)));
        assertEquals(0L, db.findAccount("a").orElseThrow().balanceCents());
    }

    @Test
    void failedTransferChangesNothing() {
        LocalDatabase db = LocalDatabase.inMemory();
        db.createAccount(new Account("a", "alice", "a@x.com", 0));
        db.createAccount(new Account("b", "bob", "b@x.com", 0));
        db.record(List.of(deposit("t0", "a", 100)));

        Transaction out = new Transaction("t1", "a", Type.TRANSFER_OUT, 150, "x", TIME, "b", "t2", null);
        Transaction in = new Transaction("t2", "b", Type.TRANSFER_IN, 150, "x", TIME, "a", "t1", null);
        InsufficientFundsException e = assertThrows(InsufficientFundsException.class,
                () -> db.record(List.of(out, in)));

        assertEquals("a", e.accountId());
        assertEquals(100L, db.findAccount("a").orElseThrow().balanceCents());
        assertEquals(0L, db.findAccount("b").orElseThrow().balanceCents());
        assertTrue(db.transactionsOf("b").isEmpty());
    }

    @Test
    void fileRoundTripPreservesEverything() throws IOException {
        Path file = Files.createTempDirectory("bank-test").resolve("sub/dir/data.txt");
        LocalDatabase db = LocalDatabase.open(file);
        db.createAccount(new Account("a1", "Alice\tTab", "alice@example.com", 0));
        db.createAccount(new Account("b2", "Bob \\ Backslash", "bob@example.com", 0));
        db.saveCredential(new LocalDatabase.Credential("alice@example.com", "a1", "hash$1"));
        Transaction d = new Transaction("t1", "a1", Type.DEPOSIT, 500, "Line1\nLine2", TIME, null, null, null);
        Transaction out = new Transaction("t2", "a1", Type.TRANSFER_OUT, 100, "Rent", TIME, "b2", "t3", null);
        Transaction in = new Transaction("t3", "b2", Type.TRANSFER_IN, 100, "Rent", TIME, "a1", "t2", null);
        Transaction rev = new Transaction("t1-reversal", "a1", Type.REVERSAL_OUT, 50, "Undo", TIME, null, null, "t1");
        db.record(List.of(d));
        db.record(List.of(out, in));

        LocalDatabase reopened = LocalDatabase.open(file);
        assertEquals("Alice\tTab", reopened.findAccount("a1").orElseThrow().username());
        assertEquals(400L, reopened.findAccount("a1").orElseThrow().balanceCents());
        db.record(List.of(rev));
        assertEquals(List.of(d, out, rev), LocalDatabase.open(file).transactionsOf("a1"));
        assertEquals(100L, reopened.findAccount("b2").orElseThrow().balanceCents());
        assertEquals("Bob \\ Backslash", reopened.findAccountByEmail("bob@example.com").orElseThrow().username());
        assertEquals("a1", reopened.credential("alice@example.com").orElseThrow().userId());
        assertEquals(List.of(d, out), reopened.transactionsOf("a1"));
        assertEquals(List.of(in), reopened.transactionsOf("b2"));
    }

    @Test
    void corruptedFileReportsLineNumber() throws IOException {
        Path file = Files.createTempDirectory("bank-test").resolve("data.txt");
        Files.writeString(file, "# header\nA\tonly-two-fields\n");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> LocalDatabase.open(file));
        assertTrue(e.getMessage().contains("line 2"), e.getMessage());
    }

    @Test
    void escapeIsReversible() {
        String tricky = "a\tb\nc\rd\\e\\tf";
        assertEquals(tricky, LocalDatabase.unescape(LocalDatabase.escape(tricky)));
    }
}
