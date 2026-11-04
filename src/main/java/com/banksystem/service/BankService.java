package com.banksystem.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.banksystem.backend.Authenticator;
import com.banksystem.backend.BankException;
import com.banksystem.backend.BankRepository;
import com.banksystem.backend.DuplicateTransactionException;
import com.banksystem.backend.InsufficientFundsException;
import com.banksystem.ds.UndoManager;
import com.banksystem.model.Account;
import com.banksystem.model.Money;
import com.banksystem.model.Transaction;
import com.banksystem.model.Transaction.Type;

public final class BankService {

    public static final int MIN_PASSWORD_LENGTH = 6;
    public static final int MAX_USERNAME_LENGTH = 30;
    public static final int MAX_TITLE_LENGTH = 100;

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final Authenticator auth;
    private final BankRepository repo;
    private final Clock clock;

    private final UndoManager<Operation> undoManager = new UndoManager<>();
    private final Map<String, String> usernameCache = new HashMap<>();

    private String currentId;

    public BankService(Authenticator auth, BankRepository repo) {
        this(auth, repo, Clock.systemDefaultZone());
    }

    public BankService(Authenticator auth, BankRepository repo, Clock clock) {
        this.auth = Objects.requireNonNull(auth, "auth");
        this.repo = Objects.requireNonNull(repo, "repo");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Account register(String email, String username, String password) {
        String normalizedEmail = normalizeEmail(email);
        String name = username == null ? "" : username.strip();

        if (!EMAIL.matcher(normalizedEmail).matches()) {
            throw new BankException("Please enter a valid email address");
        }
        if (name.isEmpty() || name.length() > MAX_USERNAME_LENGTH) {
            throw new BankException("Username must be 1-" + MAX_USERNAME_LENGTH + " characters long");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new BankException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters long");
        }
        if (repo.findAccountByEmail(normalizedEmail).isPresent()) {
            throw new BankException("An account with this email already exists");
        }

        String userId = auth.register(normalizedEmail, password);
        Account account = new Account(userId, name, normalizedEmail, 0);
        repo.createAccount(account);
        return account;
    }

    public Account login(String email, String password) {
        String userId = auth.login(normalizeEmail(email), password == null ? "" : password);
        Account account = repo.findAccount(userId)
                .orElseThrow(() -> new BankException("Login worked, but no bank account exists for this user"));
        currentId = account.id();
        undoManager.clear();
        return account;
    }

    public void logout() {
        currentId = null;
        undoManager.clear();
    }

    public boolean isLoggedIn() {
        return currentId != null;
    }

    public Optional<Account> currentAccount() {
        return currentId == null ? Optional.empty() : repo.findAccount(currentId);
    }

    public List<Account> otherAccounts() {
        Account me = requireLogin();
        return repo.listAccounts().stream()
                .filter(a -> !a.id().equals(me.id()))
                .sorted(Comparator.comparing(Account::username, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public String usernameOf(String accountId) {
        return usernameCache.computeIfAbsent(accountId,
                id -> repo.findAccount(id).map(Account::username).orElse("unknown"));
    }

    public Transaction deposit(long amountCents, String title) {
        Account me = requireLogin();
        validateAmount(amountCents);
        Transaction t = new Transaction(newId(), me.id(), Type.DEPOSIT, amountCents,
                cleanTitle(title, "Deposit"), now(), null, null, null);
        perform(new Operation("deposit of " + Money.format(amountCents), List.of(t)));
        return t;
    }

    public Transaction withdraw(long amountCents, String title) {
        Account me = requireLogin();
        validateAmount(amountCents);
        Transaction t = new Transaction(newId(), me.id(), Type.WITHDRAWAL, amountCents,
                cleanTitle(title, "Withdrawal"), now(), null, null, null);
        perform(new Operation("withdrawal of " + Money.format(amountCents), List.of(t)));
        return t;
    }

    public Transaction transfer(String recipientEmail, long amountCents, String title) {
        Account me = requireLogin();
        validateAmount(amountCents);
        Account recipient = repo.findAccountByEmail(normalizeEmail(recipientEmail))
                .orElseThrow(() -> new BankException("No account found with email " + recipientEmail));
        if (recipient.id().equals(me.id())) {
            throw new BankException("You cannot transfer money to yourself");
        }

        String outId = newId();
        String inId = newId();
        String cleanTitle = cleanTitle(title, "Transfer");
        LocalDateTime time = now();
        Transaction out = new Transaction(outId, me.id(), Type.TRANSFER_OUT, amountCents,
                cleanTitle, time, recipient.id(), inId, null);
        Transaction in = new Transaction(inId, recipient.id(), Type.TRANSFER_IN, amountCents,
                cleanTitle, time, me.id(), outId, null);

        perform(new Operation("transfer of " + Money.format(amountCents) + " to " + recipient.username(),
                List.of(out, in)));
        return out;
    }

    private void perform(Operation op) {
        try {
            repo.record(op.transactions());
        } catch (InsufficientFundsException e) {
            long balance = currentAccount().map(Account::balanceCents).orElse(0L);
            throw new BankException("Insufficient funds: your balance is " + Money.format(balance));
        }
        undoManager.record(op);
    }

    public boolean canUndo() {
        return undoManager.canUndo();
    }

    public boolean canRedo() {
        return undoManager.canRedo();
    }

    public Operation undo() {
        requireLogin();
        Operation op = undoManager.peekUndo().orElseThrow(() -> new BankException("Nothing to undo"));
        Operation reversal = reversalOf(op);
        try {
            repo.record(reversal.transactions());
        } catch (InsufficientFundsException e) {

            throw new BankException("Cannot undo the " + op.description() + ": "
                    + usernameOf(e.accountId()) + " no longer has enough money");
        } catch (DuplicateTransactionException e) {
            undoManager.commitUndo();
            throw new BankException("The " + op.description() + " has already been undone");
        }
        undoManager.commitUndo();
        return op;
    }

    public Operation redo() {
        requireLogin();
        Operation op = undoManager.peekRedo().orElseThrow(() -> new BankException("Nothing to redo"));
        Operation repeated = repetitionOf(op);
        try {
            repo.record(repeated.transactions());
        } catch (InsufficientFundsException e) {
            throw new BankException("Cannot redo the " + op.description() + ": insufficient funds");
        }
        undoManager.commitRedo(repeated);
        return repeated;
    }

    private Operation reversalOf(Operation op) {
        LocalDateTime time = now();
        List<Transaction> reversals = op.transactions().stream()
                .map(t -> new Transaction(
                        reversalId(t.id()), t.accountId(), t.type().reversalType(), t.amountCents(),
                        "Undo: " + t.title(), time, t.counterpartyId(),
                        t.linkedTransactionId() == null ? null : reversalId(t.linkedTransactionId()),
                        t.id()))
                .toList();
        return new Operation("reversal of " + op.description(), reversals);
    }

    private Operation repetitionOf(Operation op) {
        LocalDateTime time = now();
        Map<String, String> newIds = new HashMap<>();
        op.transactions().forEach(t -> newIds.put(t.id(), newId()));
        List<Transaction> copies = op.transactions().stream()
                .map(t -> new Transaction(
                        newIds.get(t.id()), t.accountId(), t.type(), t.amountCents(), t.title(), time,
                        t.counterpartyId(),
                        t.linkedTransactionId() == null ? null : newIds.get(t.linkedTransactionId()),
                        null))
                .toList();
        return new Operation(op.description(), copies);
    }

    private static String reversalId(String originalId) {
        return originalId + "-reversal";
    }

    public List<Transaction> history() {
        return loadHistory().all();
    }

    public List<Transaction> transactionsOn(LocalDate day) {
        return loadHistory().on(day);
    }

    public List<Transaction> transactionsBetween(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new BankException("Start date must not be after end date");
        }
        return loadHistory().between(from, to);
    }

    public Set<String> reversedTransactionIds() {
        return loadHistory().all().stream()
                .map(Transaction::reversalOf)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    public List<Transaction> transactionsWithTitle(String query) {
        if (query == null || query.isBlank()) {
            throw new BankException("Search text must not be empty");
        }
        return loadHistory().titleContains(query.strip());
    }

    private TransactionHistory loadHistory() {
        TransactionHistory history = new TransactionHistory();
        repo.transactionsOf(requireLogin().id()).forEach(history::add);
        return history;
    }

    private Account requireLogin() {
        if (currentId == null) {
            throw new BankException("Please log in first");
        }
        return repo.findAccount(currentId).orElseThrow(() -> {
            logout();
            return new BankException("Your account no longer exists");
        });
    }

    private static void validateAmount(long amountCents) {
        if (amountCents <= 0) {
            throw new BankException("Amount must be greater than zero");
        }
        if (amountCents > Money.MAX_AMOUNT_CENTS) {
            throw new BankException("Amount is too large");
        }
    }

    private static String cleanTitle(String title, String fallback) {
        String clean = title == null ? "" : title.strip();
        if (clean.isEmpty()) {
            return fallback;
        }
        if (clean.length() > MAX_TITLE_LENGTH) {
            throw new BankException("Title must be at most " + MAX_TITLE_LENGTH + " characters long");
        }
        return clean;
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
