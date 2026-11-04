# Bank Transaction System

A console online bank in Java 17, built on **Firebase Authentication** and **Cloud Firestore**.
It started as a project for a **Data Structures & Algorithms** course.

Users can register, log in, deposit, withdraw, send money to each other, browse and search their
transaction history, and undo/redo operations. Several people can use it at the same time
from different computers: transfers are atomic Firestore transactions, so money can't be spent twice.

Under the hood, the transaction history is indexed in a hand-written **AVL tree**, and undo/redo
runs on **two stacks**.

## Features

- **Online mode:** Firebase Authentication (email + password) and Cloud Firestore
- **Local mode:** the same app on an embedded **SQLite** database, so you can try it with no setup
- Deposit, withdraw and transfer between accounts
- Balance checks and transfers run **atomically on the backend**, safe with many clients at once
- Transaction history and search by date, date range (`O(log n + k)` range query on the tree) or title
- **Undo / redo**, including transfers. Like a real bank, undo never deletes anything: it books
  **reversal entries** that move the money back, and the original entry is marked as reversed.
  If the recipient has already spent the money, the undo is refused and nothing changes.
- Bad input never crashes the app

## Quick start (local mode, no setup)

Requirements: **JDK 17+** and **Maven 3.8+**.

```bash
git clone https://github.com/<your-username>/bank-transaction-system.git
cd bank-transaction-system

mvn compile exec:java     # run the app (data is stored in the SQLite file data/bank.db)
mvn test                  # run the unit tests
```

## Online mode with Firebase

### 1. Create a Firebase project

1. Go to the [Firebase console](https://console.firebase.google.com/) and create a project.
2. **Build → Authentication → Get started**, then enable the **Email/Password** sign-in provider.
3. **Build → Firestore Database → Create database** (any location, production mode).
4. Optional but recommended: replace the Firestore rules with the contents of
   [`firestore.rules`](firestore.rules). This blocks all direct client access; the app works through
   the Admin SDK, which is not affected by the rules.

### 2. Get the two settings

- **Web API key:** Project settings → General → *Web API Key*.
- **Service account key:** Project settings → Service accounts → *Generate new private key*.
  This downloads a JSON file. **Store it outside the repository and never commit it.**

### 3. Configure and run

```bash
cp firebase.properties.example firebase.properties   # git-ignored
# edit firebase.properties: set apiKey and the path to the JSON file

mvn compile exec:java -Dexec.args="--online"
```

Instead of the file you can use environment variables `FIREBASE_API_KEY` and `FIREBASE_CREDENTIALS`.

Run the app in two terminals, log in as two different users, and send money between them:
the recipient's balance updates in the other window.

### Other options

```bash
mvn compile exec:java -Dexec.args="--help"
mvn compile exec:java -Dexec.args="--data /tmp/other.db"
mvn compile exec:java -Dexec.args="--online --config my-firebase.properties"
```

## Example session

```text
Mode: Online

=== Alice | balance: 1500.00 USD ===
1. Deposit
2. Withdraw
3. Transfer
4. Transaction history
5. Search transactions
6. Undo last operation
7. Redo (nothing to redo)
8. Log out
0. Exit
Choose an option: 3
Recipients:
  Bob <bob@example.com>
Recipient email: bob@example.com
Amount to transfer: 250.50
Title (optional): Dinner at Kebab House
Sent 250.50 USD to Bob.

Choose an option: 4
Date              Type                    Amount  Title
2026-11-02 10:07  Deposit           +1500.00 USD  Salary
2026-11-02 10:07  Transfer out       -250.50 USD  Dinner at Kebab House (to Bob)

Choose an option: 6
Undone: transfer of 250.50 USD to Bob. The money has been moved back;
the original entry stays in the history, marked as [reversed].

Choose an option: 4
Date              Type                    Amount  Title
2026-11-02 10:07  Deposit           +1500.00 USD  Salary
2026-11-02 10:07  Transfer out       -250.50 USD  Dinner at Kebab House (to Bob)  [reversed]
2026-11-02 10:08  Reversal           +250.50 USD  Undo: Dinner at Kebab House (from Bob)
```

## Architecture

```text
ConsoleApp (menus, input)
      │
BankService (business rules, undo/redo, history)
      │
      ├── Authenticator ─────┬── FirebaseAuthenticator   (Firebase Auth REST API)
      │                      └── LocalAuthenticator      (PBKDF2 hashes in SQLite)
      │
      └── BankRepository ────┬── FirestoreRepository     (Cloud Firestore, atomic transactions)
                             └── SqliteDatabase          (embedded SQLite, SQL transactions)
```

`BankService` only knows the two interfaces, so it runs the same way online and locally, and the
unit tests run without network access. The tests also simulate several clients sharing one backend.

### Firestore data model

```text
accounts/{userId}                        username, email, balanceCents, createdAt
accounts/{userId}/transactions/{txId}    type, amountCents, title, timestamp,
                                         counterpartyId, linkedTransactionId, reversalOf
```

The ledger is **append-only**: transaction documents are only ever created, never updated or deleted.

### Local database (SQLite)

The schema is in [`schema.sql`](src/main/resources/schema.sql):

```text
accounts      id PK, username, email UNIQUE, balance_cents CHECK (>= 0), created_at
credentials   email PK, user_id UNIQUE, password_hash
transactions  id PK, account_id FK, type CHECK (...), amount_cents CHECK (> 0), title, created_at,
              counterparty_id FK, linked_transaction_id, reversal_of FK UNIQUE
```

Every money operation runs in a `BEGIN IMMEDIATE ... COMMIT` transaction: it takes the write lock,
reads the balances, checks them and writes the new balances and ledger rows, or rolls everything
back. Constraints in the schema (`CHECK (balance_cents >= 0)`, the unique index on `reversal_of`)
are a second line of defence. Several app instances can share one database file at the same time.

A transfer creates two documents: `TRANSFER_OUT` under the sender and `TRANSFER_IN` under the
recipient, linked to each other. Both balances and both documents are written in **one Firestore
transaction**: it reads both balances, checks that none goes negative, then writes everything.
If two clients touch the same account at once, Firestore retries one of them on fresh data.

### Security model

- Passwords are stored and checked by **Firebase Authentication**. The app never stores them.
- Data access uses the **Admin SDK** with a service account, so the app behaves like a trusted
  backend. Firestore rules deny all direct client access, so the public Web API key alone can't
  read or change anything.
- In a production bank, the Admin SDK part would run on a server behind an API, and users would
  only get a thin client. Here the console app plays both roles to keep the project small.

## Data structures

| Structure | Where | What it is used for | Complexity |
|---|---|---|---|
| **AVL tree** (own implementation) | [`ds/AvlTree`](src/main/java/com/banksystem/ds/AvlTree.java) | Transaction history, keyed by `(timestamp, id)` | insert / delete `O(log n)`, range query `O(log n + k)`, in-order traversal `O(n)` |
| **Two stacks** (`ArrayDeque`) | [`ds/UndoManager`](src/main/java/com/banksystem/ds/UndoManager.java) | Undo and redo history of the current session | push / pop `O(1)` |
| **B-tree indexes** (SQLite) | [`schema.sql`](src/main/resources/schema.sql) | Primary keys, `email`, `account_id`, `reversal_of` | lookup `O(log n)` |
| **Hash map** | [`service/BankService`](src/main/java/com/banksystem/service/BankService.java) | Cache of usernames by account id | lookup `O(1)` average |

### Why an AVL tree and not a plain BST?

Transactions are created in chronological order, so their keys arrive **already sorted**.
A plain binary search tree turns into a linked list in that case: every new node goes to the
right, the height becomes `n`, and every operation becomes `O(n)`.

The AVL tree rebalances itself with rotations after every insert and delete, so its height stays
below about `1.44 · log2(n)`. The test
[`sortedInsertsStayBalanced`](src/test/java/com/banksystem/ds/AvlTreeTest.java) inserts 10,000
sorted keys and checks that the height stays around 14 instead of 10,000.

The key is `(timestamp, id)` rather than just the timestamp, so two transactions made in the same
millisecond never collide.

### How undo works

Every user action is recorded as an `Operation`: one transaction (deposit, withdrawal) or two
linked transactions (a transfer), and pushed onto the undo stack.

Undo doesn't delete anything. Like a correction in a real bank, it books **reversal entries**: for
each original transaction, a new `REVERSAL_IN` / `REVERSAL_OUT` entry with the opposite effect and
a `reversalOf` reference to the original. The history keeps the full story, and the original entry
is shown as `[reversed]`. Reversal ids are derived from the original ids, so the backend refuses to
reverse the same entry twice, even from two clients.

The app peeks at the top of the undo stack and books the reversal atomically. Only if that succeeds
does the operation move to the redo stack, so a refused undo (for example, the recipient already
spent the money) leaves everything as it was. Redo repeats the operation as new transactions and
pushes them onto the undo stack, so they can be undone again. A new operation clears the redo
stack, and both stacks are cleared on logout, so one user can never undo another user's actions.

## Project structure

```text
src/main/java/com/banksystem/
├── Main.java                   entry point, command-line options
├── cli/ConsoleApp.java         text menus and input handling
├── service/                    BankService, TransactionHistory, Operation
├── ds/                         AvlTree, UndoManager
├── model/                      Account, Transaction, Money
├── security/PasswordHasher     PBKDF2 hashing for local mode
src/main/resources/schema.sql   SQLite schema
└── backend/
    ├── Authenticator, BankRepository, Backend    interfaces
    ├── firebase/                Firebase Auth + Firestore implementation
    └── local/                   SQLite implementation
```

## Design notes

- **Money is stored as `long` cents.** Floating-point types can't represent amounts like 0.10 exactly.
- **Balances are never written by the client directly.** The backend applies the transactions and
  checks the balance in the same atomic step.
- **Append-only ledger.** Transactions are never edited or deleted; corrections are new entries.
- **Fresh data:** the balance and history are re-read from the backend, because another client may
  have sent you money in the meantime.

## History

The first version was written in 2025 as a course project for Data Structures & Algorithms. It
already had the core idea: a console bank on Firebase, with the transaction history in a binary
search tree and undo on a stack.

A year later I came back to it to turn it into something that can be cloned, run and read by
others. Version 2 moves the backend behind interfaces, makes every money operation atomic,
switches login to Firebase Authentication, replaces the plain BST with an AVL tree, adds redo, a
local mode and tests, and fixes a number of bugs from the original. See [CHANGELOG.md](CHANGELOG.md)
for the full list.

## A note on AI assistance

The original version was written by me as a course project. The version 2 rework was done with the
help of AI tools; I directed the changes, reviewed the code and tested it.
