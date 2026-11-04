package com.banksystem.backend.firebase;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.banksystem.backend.BankException;
import com.banksystem.backend.BankRepository;
import com.banksystem.backend.DuplicateTransactionException;
import com.banksystem.backend.InsufficientFundsException;
import com.banksystem.model.Account;
import com.banksystem.model.Transaction;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;

public final class FirestoreRepository implements BankRepository {

    private static final String ACCOUNTS = "accounts";
    private static final String TRANSACTIONS = "transactions";
    private static final long TIMEOUT_SECONDS = 30;

    private final Firestore db;
    private final ZoneId zone;

    public FirestoreRepository(Firestore db) {
        this(db, ZoneId.systemDefault());
    }

    public FirestoreRepository(Firestore db, ZoneId zone) {
        this.db = Objects.requireNonNull(db, "db");
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    @Override
    public void createAccount(Account account) {
        Map<String, Object> data = new HashMap<>();
        data.put("username", account.username());
        data.put("email", account.email());
        data.put("balanceCents", account.balanceCents());
        data.put("createdAt", FieldValue.serverTimestamp());
        await(accountRef(account.id()).create(data));
    }

    @Override
    public Optional<Account> findAccount(String accountId) {
        DocumentSnapshot snapshot = await(accountRef(accountId).get());
        return snapshot.exists() ? Optional.of(toAccount(snapshot)) : Optional.empty();
    }

    @Override
    public Optional<Account> findAccountByEmail(String email) {
        QuerySnapshot result = await(db.collection(ACCOUNTS).whereEqualTo("email", email).limit(1).get());
        return result.isEmpty() ? Optional.empty() : Optional.of(toAccount(result.getDocuments().get(0)));
    }

    @Override
    public List<Account> listAccounts() {
        List<Account> accounts = new ArrayList<>();
        for (QueryDocumentSnapshot doc : await(db.collection(ACCOUNTS).get()).getDocuments()) {
            accounts.add(toAccount(doc));
        }
        return accounts;
    }

    @Override
    public List<Transaction> transactionsOf(String accountId) {
        List<Transaction> result = new ArrayList<>();
        for (QueryDocumentSnapshot doc : await(accountRef(accountId).collection(TRANSACTIONS).get()).getDocuments()) {
            result.add(toTransaction(accountId, doc));
        }
        return result;
    }

    @Override
    public void record(List<Transaction> transactions) {
        Map<String, Long> deltas = new LinkedHashMap<>();
        for (Transaction t : transactions) {
            deltas.merge(t.accountId(), t.signedAmountCents(), Long::sum);
        }

        await(db.runTransaction(tx -> {
            Map<String, Long> newBalances = new LinkedHashMap<>();
            for (Map.Entry<String, Long> entry : deltas.entrySet()) {
                DocumentSnapshot account = tx.get(accountRef(entry.getKey())).get();
                if (!account.exists()) {
                    throw new BankException("Account " + entry.getKey() + " does not exist");
                }
                newBalances.put(entry.getKey(), Math.addExact(longField(account, "balanceCents"), entry.getValue()));
            }
            for (Transaction t : transactions) {
                if (tx.get(transactionRef(t)).get().exists()) {
                    throw new DuplicateTransactionException(t.id());
                }
            }

            for (Map.Entry<String, Long> entry : newBalances.entrySet()) {
                if (entry.getValue() < 0) {
                    throw new InsufficientFundsException(entry.getKey());
                }
            }

            for (Map.Entry<String, Long> entry : newBalances.entrySet()) {
                tx.update(accountRef(entry.getKey()), "balanceCents", entry.getValue());
            }
            for (Transaction t : transactions) {
                tx.create(transactionRef(t), toMap(t));
            }
            return null;
        }));
    }

    private DocumentReference accountRef(String accountId) {
        return db.collection(ACCOUNTS).document(accountId);
    }

    private DocumentReference transactionRef(Transaction t) {
        return accountRef(t.accountId()).collection(TRANSACTIONS).document(t.id());
    }

    private static Account toAccount(DocumentSnapshot doc) {
        return new Account(doc.getId(), stringField(doc, "username"), stringField(doc, "email"),
                longField(doc, "balanceCents"));
    }

    private Map<String, Object> toMap(Transaction t) {
        Instant instant = t.timestamp().atZone(zone).toInstant();
        Map<String, Object> data = new HashMap<>();
        data.put("type", t.type().name());
        data.put("amountCents", t.amountCents());
        data.put("title", t.title());
        data.put("timestamp", Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano()));
        data.put("counterpartyId", t.counterpartyId());
        data.put("linkedTransactionId", t.linkedTransactionId());
        data.put("reversalOf", t.reversalOf());
        return data;
    }

    private Transaction toTransaction(String accountId, DocumentSnapshot doc) {
        Timestamp ts = doc.getTimestamp("timestamp");
        if (ts == null) {
            throw new BankException("Transaction " + doc.getId() + " has no timestamp");
        }
        LocalDateTime time = LocalDateTime.ofInstant(Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos()), zone);
        return new Transaction(doc.getId(), accountId, Transaction.Type.valueOf(stringField(doc, "type")),
                longField(doc, "amountCents"), stringField(doc, "title"), time,
                doc.getString("counterpartyId"), doc.getString("linkedTransactionId"),
                doc.getString("reversalOf"));
    }

    private static String stringField(DocumentSnapshot doc, String field) {
        String value = doc.getString(field);
        if (value == null) {
            throw new BankException("Document " + doc.getId() + " is missing field '" + field + "'");
        }
        return value;
    }

    private static long longField(DocumentSnapshot doc, String field) {
        Long value = doc.getLong(field);
        if (value == null) {
            throw new BankException("Document " + doc.getId() + " is missing field '" + field + "'");
        }
        return value;
    }

    private static <T> T await(Future<T> future) {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                if (cause instanceof BankException bank) {
                    throw bank;
                }
            }
            Throwable root = e.getCause() != null ? e.getCause() : e;
            throw new BankException("Firestore error: " + root.getMessage());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new BankException("Firestore did not respond in " + TIMEOUT_SECONDS + " seconds");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BankException("Request was interrupted");
        }
    }
}
