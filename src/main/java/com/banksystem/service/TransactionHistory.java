package com.banksystem.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import com.banksystem.ds.AvlTree;
import com.banksystem.model.Transaction;

public final class TransactionHistory {

    private record TimeKey(LocalDateTime time, String id) implements Comparable<TimeKey> {
        @Override
        public int compareTo(TimeKey other) {
            int cmp = time.compareTo(other.time);
            return cmp != 0 ? cmp : id.compareTo(other.id);
        }

        static TimeKey at(LocalDateTime time) {
            return new TimeKey(time, "");
        }
    }

    private final AvlTree<TimeKey, Transaction> tree = new AvlTree<>();

    private static TimeKey keyOf(Transaction t) {
        return new TimeKey(t.timestamp(), t.id());
    }

    public void add(Transaction transaction) {
        tree.put(keyOf(transaction), transaction);
    }

    public boolean remove(Transaction transaction) {
        return tree.remove(keyOf(transaction)) != null;
    }

    public int size() {
        return tree.size();
    }

    public List<Transaction> all() {
        return tree.values();
    }

    public List<Transaction> on(LocalDate day) {
        return between(day, day);
    }

    public List<Transaction> between(LocalDate from, LocalDate to) {
        return tree.range(TimeKey.at(from.atStartOfDay()), TimeKey.at(to.plusDays(1).atStartOfDay()));
    }

    public List<Transaction> titleContains(String query) {
        String needle = query.toLowerCase(Locale.ROOT);
        return tree.values().stream()
                .filter(t -> t.title().toLowerCase(Locale.ROOT).contains(needle))
                .toList();
    }
}
