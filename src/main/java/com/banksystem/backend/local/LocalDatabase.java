package com.banksystem.backend.local;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.banksystem.backend.BankException;
import com.banksystem.backend.BankRepository;
import com.banksystem.backend.DuplicateTransactionException;
import com.banksystem.backend.InsufficientFundsException;
import com.banksystem.model.Account;
import com.banksystem.model.Transaction;

public final class LocalDatabase implements BankRepository {

    private static final String HEADER = "# bank-transaction-system local data, format v3";

    public record Credential(String email, String userId, String passwordHash) {
    }

    private final Path file;
    private final Map<String, Account> accounts = new LinkedHashMap<>();
    private final Map<String, Credential> credentialsByEmail = new LinkedHashMap<>();
    private final Map<String, Transaction> transactions = new LinkedHashMap<>();

    private LocalDatabase(Path file) {
        this.file = file;
    }

    public static LocalDatabase inMemory() {
        return new LocalDatabase(null);
    }

    public static LocalDatabase open(Path file) {
        LocalDatabase db = new LocalDatabase(file);
        db.load();
        return db;
    }

    synchronized Optional<Credential> credential(String email) {
        return Optional.ofNullable(credentialsByEmail.get(email));
    }

    synchronized void saveCredential(Credential credential) {
        if (credentialsByEmail.containsKey(credential.email())) {
            throw new BankException("An account with this email already exists");
        }
        credentialsByEmail.put(credential.email(), credential);
        save();
    }

    @Override
    public synchronized void createAccount(Account account) {
        if (accounts.containsKey(account.id())) {
            throw new BankException("Account already exists");
        }
        accounts.put(account.id(), account);
        save();
    }

    @Override
    public synchronized Optional<Account> findAccount(String accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    @Override
    public synchronized Optional<Account> findAccountByEmail(String email) {
        return accounts.values().stream().filter(a -> a.email().equals(email)).findFirst();
    }

    @Override
    public synchronized List<Account> listAccounts() {
        return List.copyOf(accounts.values());
    }

    @Override
    public synchronized List<Transaction> transactionsOf(String accountId) {
        return transactions.values().stream().filter(t -> t.accountId().equals(accountId)).toList();
    }

    @Override
    public synchronized void record(List<Transaction> batch) {
        for (Transaction t : batch) {
            if (transactions.containsKey(t.id())) {
                throw new DuplicateTransactionException(t.id());
            }
        }
        applyBalances(batch);
        batch.forEach(t -> transactions.put(t.id(), t));
        save();
    }

    private void applyBalances(List<Transaction> batch) {
        Map<String, Long> newBalances = new LinkedHashMap<>();
        for (Transaction t : batch) {
            Account account = accounts.get(t.accountId());
            if (account == null) {
                throw new BankException("Account " + t.accountId() + " does not exist");
            }
            long base = newBalances.getOrDefault(t.accountId(), account.balanceCents());
            try {
                newBalances.put(t.accountId(), Math.addExact(base, t.signedAmountCents()));
            } catch (ArithmeticException e) {
                throw new BankException("Balance limit exceeded");
            }
        }
        newBalances.forEach((id, balance) -> {
            if (balance < 0) {
                throw new InsufficientFundsException(id);
            }
        });
        newBalances.forEach((id, balance) -> accounts.put(id, accounts.get(id).withBalance(balance)));
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                try {
                    switch (f[0]) {
                        case "A" -> {
                            expectFields(f, 5);
                            Account a = new Account(unescape(f[1]), unescape(f[2]), unescape(f[3]),
                                    Long.parseLong(f[4]));
                            accounts.put(a.id(), a);
                        }
                        case "C" -> {
                            expectFields(f, 4);
                            Credential c = new Credential(unescape(f[1]), unescape(f[2]), unescape(f[3]));
                            credentialsByEmail.put(c.email(), c);
                        }
                        case "T" -> {
                            expectFields(f, 10);
                            Transaction t = new Transaction(unescape(f[1]), unescape(f[2]),
                                    Transaction.Type.valueOf(f[3]), Long.parseLong(f[4]), unescape(f[9]),
                                    LocalDateTime.parse(f[5]), emptyToNull(unescape(f[6])),
                                    emptyToNull(unescape(f[7])), emptyToNull(unescape(f[8])));
                            transactions.put(t.id(), t);
                        }
                        default -> throw new IllegalArgumentException("unknown record type '" + f[0] + "'");
                    }
                } catch (RuntimeException e) {
                    throw new IllegalStateException(
                            "Corrupted data file " + file + " at line " + lineNumber + ": " + e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file, e);
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (Account a : accounts.values()) {
            appendRecord(out, "A", a.id(), a.username(), a.email(), Long.toString(a.balanceCents()));
        }
        for (Credential c : credentialsByEmail.values()) {
            appendRecord(out, "C", c.email(), c.userId(), c.passwordHash());
        }
        for (Transaction t : transactions.values()) {
            appendRecord(out, "T", t.id(), t.accountId(), t.type().name(), Long.toString(t.amountCents()),
                    t.timestamp().toString(), nullToEmpty(t.counterpartyId()),
                    nullToEmpty(t.linkedTransactionId()), nullToEmpty(t.reversalOf()), t.title());
        }

        Path target = file.toAbsolutePath();
        Path tmp = null;
        try {
            Files.createDirectories(target.getParent());
            tmp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
            Files.writeString(tmp, out, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {

                }
            }
        }
    }

    private static void expectFields(String[] fields, int expected) {
        if (fields.length != expected) {
            throw new IllegalArgumentException("expected " + expected + " fields, got " + fields.length);
        }
    }

    private static void appendRecord(StringBuilder out, String... fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                out.append('\t');
            }
            out.append(escape(fields[i]));
        }
        out.append('\n');
    }

    static String escape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unescape(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (++i >= value.length()) {
                throw new IllegalArgumentException("dangling escape character");
            }
            switch (value.charAt(i)) {
                case '\\' -> sb.append('\\');
                case 't' -> sb.append('\t');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                default -> throw new IllegalArgumentException("unknown escape \\" + value.charAt(i));
            }
        }
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}
