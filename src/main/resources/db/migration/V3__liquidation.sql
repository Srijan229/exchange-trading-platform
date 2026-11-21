CREATE TABLE liquidation_episodes (
    id bigserial PRIMARY KEY,
    account_id text NOT NULL REFERENCES accounts(id),
    status text NOT NULL CHECK (status IN ('ACTIVE', 'COMPLETE')),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);
CREATE UNIQUE INDEX one_active_liquidation_per_account ON liquidation_episodes(account_id) WHERE status = 'ACTIVE';
ALTER TABLE orders ADD COLUMN reduce_only boolean NOT NULL DEFAULT false;
ALTER TABLE orders ADD COLUMN liquidation_episode_id bigint REFERENCES liquidation_episodes(id);
CREATE UNIQUE INDEX one_open_liquidation_per_position ON orders(liquidation_episode_id, symbol) WHERE status = 'OPEN' AND reduce_only;
