CREATE TABLE accounts (id text PRIMARY KEY, cash_cents bigint NOT NULL CHECK (cash_cents >= 0));
CREATE TABLE instruments (symbol text PRIMARY KEY, mark_cents bigint NOT NULL CHECK (mark_cents > 0));
CREATE TABLE positions (account_id text NOT NULL REFERENCES accounts, symbol text NOT NULL REFERENCES instruments, quantity bigint NOT NULL DEFAULT 0, entry_cents bigint NOT NULL DEFAULT 0, PRIMARY KEY(account_id, symbol));
CREATE TABLE orders (sequence bigserial PRIMARY KEY, client_id text NOT NULL UNIQUE, account_id text NOT NULL REFERENCES accounts, symbol text NOT NULL REFERENCES instruments, side text NOT NULL CHECK(side IN ('BUY','SELL')), price_cents bigint NOT NULL CHECK(price_cents > 0), quantity bigint NOT NULL CHECK(quantity > 0), remaining bigint NOT NULL CHECK(remaining >= 0), status text NOT NULL, reserved_cents bigint NOT NULL DEFAULT 0, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE fills (id bigserial PRIMARY KEY, buy_order bigint NOT NULL REFERENCES orders(sequence), sell_order bigint NOT NULL REFERENCES orders(sequence), quantity bigint NOT NULL, price_cents bigint NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE events (id bigserial PRIMARY KEY, description text NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
INSERT INTO accounts VALUES ('alice',10000), ('maker',100000000);
INSERT INTO instruments VALUES ('BTC-PERP',5000), ('ETH-PERP',5000);
