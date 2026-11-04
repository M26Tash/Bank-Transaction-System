CREATE TABLE IF NOT EXISTS accounts (
    id            TEXT    PRIMARY KEY,
    username      TEXT    NOT NULL,
    email         TEXT    NOT NULL UNIQUE,
    balance_cents INTEGER NOT NULL DEFAULT 0 CHECK (balance_cents >= 0),
    created_at    TEXT    NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE TABLE IF NOT EXISTS credentials (
    email         TEXT PRIMARY KEY,
    user_id       TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS transactions (
    id                    TEXT    PRIMARY KEY,
    account_id            TEXT    NOT NULL REFERENCES accounts (id),
    type                  TEXT    NOT NULL CHECK (type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER_IN',
                                                           'TRANSFER_OUT', 'REVERSAL_IN', 'REVERSAL_OUT')),
    amount_cents          INTEGER NOT NULL CHECK (amount_cents > 0),
    title                 TEXT    NOT NULL,
    created_at            TEXT    NOT NULL,
    counterparty_id       TEXT    REFERENCES accounts (id),
    linked_transaction_id TEXT,
    reversal_of           TEXT    REFERENCES transactions (id)
);

CREATE INDEX IF NOT EXISTS idx_transactions_account ON transactions (account_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_transactions_reversal_of ON transactions (reversal_of);
