# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [2.0.0] - 2026-11-02

A rework of the original course project into an application that can be cloned, run and tested.

### Added

- Online mode (`--online`) with Firebase Authentication and Cloud Firestore.
- Local mode (default) on an embedded SQLite database (`data/bank.db`), so the app runs without any
  setup. Schema in `schema.sql` with foreign keys, `UNIQUE` and `CHECK` constraints.
- Hand-written AVL tree for the transaction history, with `O(log n + k)` date-range queries.
- Redo, using a second stack next to the undo stack. Redo repeats the undone operation as new
  transactions.
- Reversal entries (`REVERSAL_IN` / `REVERSAL_OUT`, with a `reversalOf` reference): undo books a
  correction instead of deleting transactions, and the history marks reversed entries.
- Atomic money operations: balances are checked and updated together with the transaction
  records in a single Firestore transaction (or a `BEGIN IMMEDIATE` SQLite transaction in local mode).
- Configuration via `firebase.properties` or environment variables, with `firebase.properties.example`.
- `firestore.rules` that deny all direct client access.
- Command-line options: `--online`, `--config`, `--data`, `--help`.
- Unit tests (JUnit 5) for the data structures, money parsing, password hashing, storage and
  business logic, including several clients sharing one backend.
- GitHub Actions workflow that runs the tests on every push.
- README with setup guide, architecture and data structure notes; this changelog.

### Changed

- Backend is behind two interfaces, `Authenticator` and `BankRepository`, with Firebase and local
  implementations.
- The ledger is append-only: transactions are never edited or deleted.
- Money is stored as `long` cents instead of `double`.
- Dates use `java.time` instead of the deprecated `java.util.Date` constructors.
- A transfer is stored as two linked entries (`TRANSFER_OUT` / `TRANSFER_IN`); the other party is
  kept in its own field instead of being parsed from the title.
- Balances and history are re-read from the backend, so incoming transfers show up without
  logging in again.

### Fixed

- Login did not check the password.
- Every logout saved all transactions again, creating duplicates.
- Transaction history and the undo stack were not cleared on logout, so the next user could see
  and undo the previous user's operations.
- Undoing a transfer reversed the wrong side and did not restore the recipient's balance.
- Titles and usernames containing spaces broke the history display and the undo of transfers;
  an empty transfer title crashed the app.
- Entering text where a number was expected crashed the app.
- Undo deleted transactions (matched by amount and title, so sometimes the wrong ones), leaving
  no trace in the history that money had been moved back.
- Date-range search skipped transactions exactly on the range boundaries.
- Two transactions with the same timestamp could replace each other in the tree.
- The transfer screen showed other users' balances.

### Security

- Local mode stores passwords as salted PBKDF2 hashes; online mode leaves passwords to
  Firebase Authentication.

## [1.0.0] - 2025-05-30

Original version, built for the Data Structures & Algorithms course.

### Added

- Console banking app: registration, login, deposit, withdraw, transfer.
- Transaction history in a binary search tree and a queue; search by date, date range and title.
- Undo of the last operation using a stack.
- Firebase Authentication and Cloud Firestore through the Firebase Admin SDK.
