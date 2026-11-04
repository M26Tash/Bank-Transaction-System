package com.banksystem.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.banksystem.backend.BankException;
import com.banksystem.backend.local.LocalAuthenticator;
import com.banksystem.backend.local.SqliteDatabase;
import com.banksystem.model.Transaction;
import com.banksystem.model.Transaction.Type;
import com.banksystem.security.PasswordHasher;

class BankServiceTest {

    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2025-03-10T09:00:00Z");

        void advance(long amount, ChronoUnit unit) {
            now = now.plus(amount, unit);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private SqliteDatabase db;
    private TestClock clock;
    private BankService bank;

    @BeforeEach
    void setUp() {
        db = SqliteDatabase.inMemory();
        clock = new TestClock();
        bank = newService();
        bank.register("alice@example.com", "alice", "alice-pass");
        bank.register("bob@example.com", "bob", "bob-pass");
    }

    private BankService newService() {
        return new BankService(new LocalAuthenticator(db, new PasswordHasher(1_000)), db, clock);
    }

    private static long balance(BankService service) {
        return service.currentAccount().orElseThrow().balanceCents();
    }

    private long balance() {
        return balance(bank);
    }

    @Test
    void loginRequiresCorrectPassword() {
        assertThrows(BankException.class, () -> bank.login("alice@example.com", "wrong-pass"));
        assertThrows(BankException.class, () -> bank.login("nobody@example.com", "alice-pass"));
        assertFalse(bank.currentAccount().isPresent());

        bank.login("  ALICE@example.com ", "alice-pass");
        assertEquals("alice", bank.currentAccount().orElseThrow().username());
    }

    @Test
    void registrationIsValidated() {
        assertThrows(BankException.class, () -> bank.register("Alice@Example.com", "dup", "password"));
        assertThrows(BankException.class, () -> bank.register("not-an-email", "x", "password"));
        assertThrows(BankException.class, () -> bank.register("c@example.com", "  ", "password"));
        assertThrows(BankException.class, () -> bank.register("c@example.com", "carol", "123"));
    }

    @Test
    void operationsRequireLogin() {
        assertThrows(BankException.class, () -> bank.deposit(100, "x"));
        assertThrows(BankException.class, () -> bank.history());
    }

    @Test
    void depositAndWithdraw() {
        bank.login("alice@example.com", "alice-pass");
        bank.deposit(10_000, "Salary");
        clock.advance(1, ChronoUnit.MINUTES);
        bank.withdraw(2_550, "  ");

        assertEquals(7_450L, balance());
        List<Transaction> history = bank.history();
        assertEquals(2, history.size());
        assertEquals("Salary", history.get(0).title());
        assertEquals("Withdrawal", history.get(1).title());
    }

    @Test
    void cannotOverdraw() {
        bank.login("alice@example.com", "alice-pass");
        bank.deposit(1_000, "x");
        assertThrows(BankException.class, () -> bank.withdraw(1_001, "too much"));
        assertThrows(BankException.class, () -> bank.transfer("bob@example.com", 1_001, "too much"));
        assertEquals(1_000L, balance());
    }

    @Test
    void transferMovesMoneyAndRecordsBothSides() {
        bank.login("alice@example.com", "alice-pass");
        bank.deposit(5_000, "x");
        Transaction out = bank.transfer("bob@example.com", 1_500, "Dinner with friends");

        assertEquals(3_500L, balance());
        assertEquals(Type.TRANSFER_OUT, out.type());

        bank.logout();
        bank.login("bob@example.com", "bob-pass");
        assertEquals(1_500L, balance());
        List<Transaction> bobHistory = bank.history();
        assertEquals(1, bobHistory.size());
        Transaction in = bobHistory.get(0);
        assertEquals(Type.TRANSFER_IN, in.type());
        assertEquals("Dinner with friends", in.title());
        assertEquals("alice", bank.usernameOf(in.counterpartyId()));
        assertEquals(out.id(), in.linkedTransactionId());
    }

    @Test
    void cannotTransferToSelfOrUnknown() {
        bank.login("alice@example.com", "alice-pass");
        bank.deposit(5_000, "x");
        assertThrows(BankException.class, () -> bank.transfer("alice@example.com", 100, "me"));
        assertThrows(BankException.class, () -> bank.transfer("ghost@example.com", 100, "?"));
    }

    @Test
    void undoTransferRestoresBothAccounts() {
        bank.login("alice@example.com", "alice-pass");
        bank.deposit(5_000, "x");
        bank.transfer("bob@example.com", 2_000, "loan");

        Operation undone = bank.undo();
        assertEquals(2, undone.transactions().size());
        assertEquals(5_000L, balance());

        List<Transaction> aliceHistory = bank.history();
        assertEquals(3, aliceHistory.size());
        Transaction transferOut = aliceHistory.stream().filter(t -> t.type() == Type.TRANSFER_OUT).findFirst()
                .orElseThrow();
        Transaction reversal = aliceHistory.stream().filter(t -> t.type() == Type.REVERSAL_IN).findFirst()
                .orElseThrow();
        assertEquals(transferOut.id(), reversal.reversalOf());
        assertEquals(2_000L, reversal.amountCents());
        assertTrue(bank.reversedTransactionIds().contains(transferOut.id()));

        bank.logout();
        bank.login("bob@example.com", "bob-pass");
        assertEquals(0L, balance());
        assertEquals(2, bank.history().size());
    }

    @Test
    void undoAfterRedoReversesTheRepeatedOperation() {
        bank.login("alice@example.com", "alice-pass");
        bank.deposit(5_000, "x");
        bank.undo();
        bank.redo();
        bank.undo();
        assertEquals(0L, balance());
        assertEquals(4, bank.history().size());
        assertEquals(2, bank.reversedTransactionIds().size());
    }

    @Test
    void redoReappliesOperation() {
        bank.login("alice@example.com", "alice-pass");
        bank.deposit(5_000, "x");
        bank.undo();
        assertEquals(0L, balance());
        assertTrue(bank.canRedo());

        bank.redo();
        assertEquals(5_000L, balance());
        assertEquals(3, bank.history().size());
        assertFalse(bank.canRedo());
        assertTrue(bank.canUndo());
    }

    @Test
    void undoesInReverseOrderUntilEmpty() {
        bank.login("alice@example.com", "alice-pass");
        bank.deposit(5_000, "x");
        bank.withdraw(4_000, "y");
        bank.undo();
        assertEquals(5_000L, balance());
        bank.undo();
        assertEquals(0L, balance());
        assertThrows(BankException.class, () -> bank.undo());
    }

    @Test
    void undoHistoryDoesNotLeakBetweenSessions() {
        bank.login("alice@example.com", "alice-pass");
        bank.deposit(5_000, "x");
        bank.logout();

        bank.login("bob@example.com", "bob-pass");
        assertFalse(bank.canUndo());
        assertThrows(BankException.class, () -> bank.undo());
    }

    @Test
    void historyIsSeparatePerUser() {
        bank.login("alice@example.com", "alice-pass");
        bank.deposit(5_000, "alice's money");
        bank.logout();

        bank.login("bob@example.com", "bob-pass");
        assertTrue(bank.history().isEmpty());
    }

    @Test
    void searchByDateRangeAndTitle() {
        bank.login("alice@example.com", "alice-pass");
        bank.deposit(100, "Groceries March");
        clock.advance(1, ChronoUnit.DAYS);
        bank.deposit(200, "Rent");
        clock.advance(1, ChronoUnit.DAYS);
        bank.deposit(300, "groceries again");

        assertEquals(1, bank.transactionsOn(LocalDate.of(2025, 3, 11)).size());
        assertEquals(2, bank.transactionsBetween(LocalDate.of(2025, 3, 11), LocalDate.of(2025, 3, 12)).size());
        assertEquals(3, bank.transactionsBetween(LocalDate.of(2025, 3, 10), LocalDate.of(2025, 3, 12)).size());
        assertEquals(2, bank.transactionsWithTitle("GROCERIES").size());
        assertThrows(BankException.class,
                () -> bank.transactionsBetween(LocalDate.of(2025, 3, 12), LocalDate.of(2025, 3, 10)));
    }

    @Test
    void transactionsAtSameInstantAreAllKept() {
        bank.login("alice@example.com", "alice-pass");
        for (int i = 0; i < 5; i++) {
            bank.deposit(100, "same time " + i);
        }
        assertEquals(5, bank.history().size());
        bank.undo();
        assertEquals(6, bank.history().size());
        assertEquals(1, bank.reversedTransactionIds().size());
    }

    @Test
    void dataSurvivesRestart() {
        bank.login("alice@example.com", "alice-pass");
        bank.deposit(5_000, "x");
        bank.transfer("bob@example.com", 1_000, "y");
        bank.logout();

        BankService restarted = newService();
        restarted.login("bob@example.com", "bob-pass");
        assertEquals(1_000L, balance(restarted));
        assertEquals(1, restarted.history().size());
    }

    @Test
    void incomingTransferIsVisibleWithoutRelogin() {
        BankService bobClient = newService();
        bobClient.login("bob@example.com", "bob-pass");

        bank.login("alice@example.com", "alice-pass");
        bank.deposit(5_000, "x");
        bank.transfer("bob@example.com", 1_200, "for you");

        assertEquals(1_200L, balance(bobClient));
        assertEquals(1, bobClient.history().size());
    }

    @Test
    void undoFailsIfRecipientAlreadySpentTheMoney() {
        BankService bobClient = newService();
        bobClient.login("bob@example.com", "bob-pass");

        bank.login("alice@example.com", "alice-pass");
        bank.deposit(5_000, "x");
        bank.transfer("bob@example.com", 2_000, "oops");
        bobClient.withdraw(1_500, "spent it");

        BankException e = assertThrows(BankException.class, () -> bank.undo());
        assertTrue(e.getMessage().contains("bob"), e.getMessage());
        assertEquals(3_000L, balance());
        assertEquals(500L, balance(bobClient));
        assertTrue(bank.canUndo());
    }

    @Test
    void balanceCheckUsesLatestBalance() {
        bank.login("alice@example.com", "alice-pass");
        bank.deposit(1_000, "x");
        BankService secondClient = newService();
        secondClient.login("alice@example.com", "alice-pass");

        bank.withdraw(800, "first");
        assertThrows(BankException.class, () -> secondClient.withdraw(800, "second"));
        assertEquals(200L, balance(secondClient));
    }
}
