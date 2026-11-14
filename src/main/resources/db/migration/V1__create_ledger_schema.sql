-- Nota: "transaction" não é palavra reservada no PostgreSQL; o identificador
-- funciona sem aspas (verificado em postgres:17). Mantido sem aspas por legibilidade.

CREATE TABLE account (
    id       UUID PRIMARY KEY,
    name     TEXT    NOT NULL,
    type     TEXT    NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    currency CHAR(3) NOT NULL
);

CREATE TABLE transaction (
    id              UUID        PRIMARY KEY,
    occurred_at     TIMESTAMPTZ NOT NULL,
    description     TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL UNIQUE
);

CREATE TABLE entry (
    id             UUID   PRIMARY KEY,
    transaction_id UUID   NOT NULL REFERENCES transaction (id),
    account_id     UUID   NOT NULL REFERENCES account (id),
    direction      TEXT   NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_cents   BIGINT NOT NULL CHECK (amount_cents > 0)
);

-- Índice para somar lançamentos por conta ao calcular o saldo (não há coluna de saldo).
CREATE INDEX entry_account_id_idx ON entry (account_id);
CREATE INDEX entry_transaction_id_idx ON entry (transaction_id);
