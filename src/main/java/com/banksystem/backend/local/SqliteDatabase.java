package com.banksystem.backend.local;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.banksystem.backend.BankException;
import com.banksystem.backend.BankRepository;
import com.banksystem.backend.DuplicateTransactionException;
import com.banksystem.backend.InsufficientFundsException;
import com.banksystem.model.Account;
import com.banksystem.model.Transaction;

public final class SqliteDatabase implements BankRepository, CredentialStore, AutoCloseable {

    private static final String SCHEMA_RESOURCE = "/schema.sql";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final String TRANSACTION_COLUMNS = "id, account_id, type, amount_cents, title, created_at, "
            + "counterparty_id, linked_transaction_id, reversal_of";

    private final Connection connection;

    private SqliteDatabase(Connection connection) {
        this.connection = connection;
    }

    public static SqliteDatabase open(Path file) {
        Path absolute = file.toAbsolutePath();
        try {
            Files.createDirectories(absolute.getParent());
        } catch (IOException e) {
            throw new BankException("Cannot create folder " + absolute.getParent() + ": " + e.getMessage());
        }
        return connect("jdbc:sqlite:" + absolute);
    }

    public static SqliteDatabase inMemory() {
        return connect("jdbc:sqlite::memory:");
    }

    private static SqliteDatabase connect(String url) {
        try {
            SqliteDatabase db = new SqliteDatabase(DriverManager.getConnection(url));
            db.initialize();
            return db;
        } catch (SQLException e) {
            throw new BankException("Cannot open database " + url + ": " + e.getMessage());
        }
    }

    private void initialize() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("PRAGMA busy_timeout = 5000");
            st.execute("PRAGMA journal_mode = WAL");
            for (String sql : loadSchema()) {
                st.execute(sql);
            }
        }
    }

    private static List<String> loadSchema() {
        try (InputStream in = SqliteDatabase.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new BankException("Resource " + SCHEMA_RESOURCE + " is missing");
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String withoutComments = text.lines()
                    .filter(line -> !line.strip().startsWith("--"))
                    .collect(Collectors.joining("\n"));
            return Arrays.stream(withoutComments.split(";"))
                    .map(String::strip)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new BankException("Cannot read " + SCHEMA_RESOURCE + ": " + e.getMessage());
        }
    }

    @Override
    public synchronized Optional<Credential> findCredential(String email) {
        return query("SELECT email, user_id, password_hash FROM credentials WHERE email = ?",
                rs -> new Credential(rs.getString(1), rs.getString(2), rs.getString(3)), email)
                .stream().findFirst();
    }

    @Override
    public synchronized void saveCredential(Credential c) {
        if (findCredential(c.email()).isPresent()) {
            throw new BankException("An account with this email already exists");
        }
        update("INSERT INTO credentials (email, user_id, password_hash) VALUES (?, ?, ?)",
                c.email(), c.userId(), c.passwordHash());
    }

    @Override
    public synchronized void createAccount(Account a) {
        if (findAccountByEmail(a.email()).isPresent()) {
            throw new BankException("An account with this email already exists");
        }
        update("INSERT INTO accounts (id, username, email, balance_cents) VALUES (?, ?, ?, ?)",
                a.id(), a.username(), a.email(), a.balanceCents());
    }

    @Override
    public synchronized Optional<Account> findAccount(String accountId) {
        return query("SELECT id, username, email, balance_cents FROM accounts WHERE id = ?",
                SqliteDatabase::toAccount, accountId).stream().findFirst();
    }

    @Override
    public synchronized Optional<Account> findAccountByEmail(String email) {
        return query("SELECT id, username, email, balance_cents FROM accounts WHERE email = ?",
                SqliteDatabase::toAccount, email).stream().findFirst();
    }

    @Override
    public synchronized List<Account> listAccounts() {
        return query("SELECT id, username, email, balance_cents FROM accounts ORDER BY username",
                SqliteDatabase::toAccount);
    }

    @Override
    public synchronized List<Transaction> transactionsOf(String accountId) {
        return query("SELECT " + TRANSACTION_COLUMNS + " FROM transactions WHERE account_id = ?",
                SqliteDatabase::toTransaction, accountId);
    }

    @Override
    public synchronized void record(List<Transaction> transactions) {
        Map<String, Long> deltas = new LinkedHashMap<>();
        for (Transaction t : transactions) {
            deltas.merge(t.accountId(), t.signedAmountCents(), Long::sum);
        }

        inTransaction(() -> {
            for (Transaction t : transactions) {
                if (!query("SELECT 1 FROM transactions WHERE id = ?", rs -> 1, t.id()).isEmpty()) {
                    throw new DuplicateTransactionException(t.id());
                }
            }

            Map<String, Long> newBalances = new LinkedHashMap<>();
            for (Map.Entry<String, Long> entry : deltas.entrySet()) {
                long balance = findAccount(entry.getKey())
                        .orElseThrow(() -> new BankException("Account " + entry.getKey() + " does not exist"))
                        .balanceCents();
                long newBalance;
                try {
                    newBalance = Math.addExact(balance, entry.getValue());
                } catch (ArithmeticException e) {
                    throw new BankException("Balance limit exceeded");
                }
                if (newBalance < 0) {
                    throw new InsufficientFundsException(entry.getKey());
                }
                newBalances.put(entry.getKey(), newBalance);
            }

            for (Map.Entry<String, Long> entry : newBalances.entrySet()) {
                update("UPDATE accounts SET balance_cents = ? WHERE id = ?", entry.getValue(), entry.getKey());
            }
            for (Transaction t : transactions) {
                update("INSERT INTO transactions (" + TRANSACTION_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        t.id(), t.accountId(), t.type().name(), t.amountCents(), t.title(),
                        TIME.format(t.timestamp()), t.counterpartyId(), t.linkedTransactionId(), t.reversalOf());
            }
        });
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {

        }
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (PreparedStatement ps = prepare(sql, params); ResultSet rs = ps.executeQuery()) {
            List<T> rows = new ArrayList<>();
            while (rs.next()) {
                rows.add(mapper.map(rs));
            }
            return rows;
        } catch (SQLException e) {
            throw new BankException("Database error: " + e.getMessage());
        }
    }

    private void update(String sql, Object... params) {
        try (PreparedStatement ps = prepare(sql, params)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new BankException("Database error: " + e.getMessage());
        }
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < params.length; i++) {
                Object p = params[i];
                if (p instanceof Long value) {
                    ps.setLong(i + 1, value);
                } else {
                    ps.setString(i + 1, (String) p);
                }
            }
            return ps;
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
    }

    private void inTransaction(Runnable work) {
        execute("BEGIN IMMEDIATE");
        try {
            work.run();
            execute("COMMIT");
        } catch (RuntimeException e) {
            try {
                execute("ROLLBACK");
            } catch (RuntimeException rollbackFailure) {
                e.addSuppressed(rollbackFailure);
            }
            throw e;
        }
    }

    private void execute(String sql) {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new BankException("Database error: " + e.getMessage());
        }
    }

    private static Account toAccount(ResultSet rs) throws SQLException {
        return new Account(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4));
    }

    private static Transaction toTransaction(ResultSet rs) throws SQLException {
        return new Transaction(rs.getString(1), rs.getString(2), Transaction.Type.valueOf(rs.getString(3)),
                rs.getLong(4), rs.getString(5), LocalDateTime.parse(rs.getString(6)),
                rs.getString(7), rs.getString(8), rs.getString(9));
    }
}
