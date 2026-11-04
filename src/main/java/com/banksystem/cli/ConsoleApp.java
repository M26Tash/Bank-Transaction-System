package com.banksystem.cli;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.banksystem.backend.BankException;
import com.banksystem.model.Account;
import com.banksystem.model.Money;
import com.banksystem.model.Transaction;
import com.banksystem.service.BankService;
import com.banksystem.service.Operation;

public final class ConsoleApp {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final BankService bank;
    private final BufferedReader in;
    private final PrintStream out;
    private final Console console;

    public ConsoleApp(BankService bank, InputStream input, PrintStream output) {
        this.bank = bank;
        this.in = new BufferedReader(new InputStreamReader(input, Charset.defaultCharset()));
        this.out = output;
        this.console = input == System.in ? System.console() : null;
    }

    private static final class EndOfInput extends RuntimeException {
        private static final long serialVersionUID = 1L;

        EndOfInput() {
            super(null, null, false, false);
        }
    }

    public void run() {
        out.println("Bank Transaction System");
        try {
            boolean running = true;
            while (running) {
                running = bank.isLoggedIn() ? mainMenu() : loginMenu();
            }
        } catch (EndOfInput e) {
            out.println();
        }
        out.println("Goodbye!");
    }

    private boolean loginMenu() {
        out.println();
        out.println("=== Welcome ===");
        out.println("1. Log in");
        out.println("2. Register");
        out.println("0. Exit");
        switch (readLine("Choose an option: ")) {
            case "1" -> attempt(this::login);
            case "2" -> attempt(this::register);
            case "0" -> {
                return false;
            }
            default -> out.println("Unknown option, please try again.");
        }
        return true;
    }

    private boolean mainMenu() {
        String header;
        try {
            Optional<Account> me = bank.currentAccount();
            if (me.isEmpty()) {
                bank.logout();
                out.println("Your account no longer exists. You have been logged out.");
                return true;
            }
            header = me.get().username() + " | balance: " + Money.format(me.get().balanceCents());
        } catch (RuntimeException e) {
            header = "balance unavailable: " + e.getMessage();
        }
        out.println();
        out.println("=== " + header + " ===");
        out.println("1. Deposit");
        out.println("2. Withdraw");
        out.println("3. Transfer");
        out.println("4. Transaction history");
        out.println("5. Search transactions");
        out.println("6. Undo last operation" + (bank.canUndo() ? "" : " (nothing to undo)"));
        out.println("7. Redo" + (bank.canRedo() ? "" : " (nothing to redo)"));
        out.println("8. Log out");
        out.println("0. Exit");
        switch (readLine("Choose an option: ")) {
            case "1" -> attempt(this::deposit);
            case "2" -> attempt(this::withdraw);
            case "3" -> attempt(this::transfer);
            case "4" -> attempt(() -> printTransactions(bank.history(), "No transactions yet."));
            case "5" -> attempt(this::search);
            case "6" -> attempt(this::undo);
            case "7" -> attempt(this::redo);
            case "8" -> {
                bank.logout();
                out.println("Logged out.");
            }
            case "0" -> {
                return false;
            }
            default -> out.println("Unknown option, please try again.");
        }
        return true;
    }

    private void login() {
        String email = readLine("Email: ");
        String password = readPassword("Password: ");
        Account account = bank.login(email, password);
        out.println("Welcome back, " + account.username() + "!");
    }

    private void register() {
        String email = readLine("Email: ");
        String username = readLine("Username: ");
        String password = readPassword("Password (min " + BankService.MIN_PASSWORD_LENGTH + " characters): ");
        String repeat = readPassword("Repeat password: ");
        if (!password.equals(repeat)) {
            throw new BankException("Passwords do not match");
        }
        bank.register(email, username, password);
        out.println("Account created. You can log in now.");
    }

    private void deposit() {
        long amount = Money.parse(readLine("Amount to deposit: "));
        String title = readLine("Title (optional): ");
        bank.deposit(amount, title);
        out.println("Deposited " + Money.format(amount) + ".");
    }

    private void withdraw() {
        long amount = Money.parse(readLine("Amount to withdraw: "));
        String title = readLine("Title (optional): ");
        bank.withdraw(amount, title);
        out.println("Withdrew " + Money.format(amount) + ".");
    }

    private void transfer() {
        List<Account> others = bank.otherAccounts();
        if (others.isEmpty()) {
            out.println("There are no other accounts to send money to yet.");
            return;
        }
        out.println("Recipients:");
        for (Account a : others) {
            out.println("  " + a.username() + " <" + a.email() + ">");
        }
        String email = readLine("Recipient email: ");
        long amount = Money.parse(readLine("Amount to transfer: "));
        String title = readLine("Title (optional): ");
        Transaction t = bank.transfer(email, amount, title);
        out.println("Sent " + Money.format(amount) + " to " + bank.usernameOf(t.counterpartyId()) + ".");
    }

    private void search() {
        out.println("1. On a specific date");
        out.println("2. In a date range");
        out.println("3. By title");
        switch (readLine("Search by: ")) {
            case "1" -> printTransactions(bank.transactionsOn(readDate("Date (yyyy-MM-dd): ")),
                    "No transactions on that date.");
            case "2" -> {
                LocalDate from = readDate("From (yyyy-MM-dd): ");
                LocalDate to = readDate("To (yyyy-MM-dd): ");
                printTransactions(bank.transactionsBetween(from, to), "No transactions in that range.");
            }
            case "3" -> printTransactions(bank.transactionsWithTitle(readLine("Title contains: ")),
                    "No transactions match.");
            default -> out.println("Unknown option.");
        }
    }

    private void undo() {
        Operation op = bank.undo();
        out.println("Undone: " + op.description() + ". The money has been moved back;");
        out.println("the original entry stays in the history, marked as [reversed].");
    }

    private void redo() {
        Operation op = bank.redo();
        out.println("Redone: " + op.description() + ".");
    }

    private void printTransactions(List<Transaction> transactions, String emptyMessage) {
        if (transactions.isEmpty()) {
            out.println(emptyMessage);
            return;
        }
        Set<String> reversed = bank.reversedTransactionIds();
        out.printf("%-16s  %-12s  %16s  %s%n", "Date", "Type", "Amount", "Title");
        for (Transaction t : transactions) {
            boolean incoming = t.type().sign() > 0;
            String amount = (incoming ? "+" : "-") + Money.format(t.amountCents());
            String details = t.counterpartyId() == null ? ""
                    : (incoming ? " (from " : " (to ") + bank.usernameOf(t.counterpartyId()) + ")";
            String mark = reversed.contains(t.id()) ? "  [reversed]" : "";
            out.printf("%-16s  %-12s  %16s  %s%s%s%n",
                    TIME.format(t.timestamp()), t.type().label(), amount, t.title(), details, mark);
        }
    }

    private void attempt(Runnable action) {
        try {
            action.run();
        } catch (BankException | IllegalArgumentException e) {
            out.println("Error: " + e.getMessage());
        } catch (UncheckedIOException e) {
            out.println("Error: could not save data (" + e.getMessage() + ")");
        } catch (EndOfInput e) {
            throw e;
        } catch (RuntimeException e) {
            out.println("Unexpected error: " + e);
        }
    }

    private String readLine(String prompt) {
        out.print(prompt);
        out.flush();
        try {
            String line = in.readLine();
            if (line == null) {
                throw new EndOfInput();
            }
            return line.strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String readPassword(String prompt) {
        if (console != null) {
            char[] chars = console.readPassword(prompt);
            if (chars == null) {
                throw new EndOfInput();
            }
            return new String(chars);
        }
        return readLine(prompt);
    }

    private LocalDate readDate(String prompt) {
        String text = readLine(prompt);
        try {
            return LocalDate.parse(text);
        } catch (DateTimeException e) {
            throw new BankException("Invalid date '" + text + "', expected format yyyy-MM-dd");
        }
    }
}
