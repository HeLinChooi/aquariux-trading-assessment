-- A client sends the same Idempotency-Key when it retries a trade, for example
-- after a timeout, when it cannot tell whether the first request was executed.
-- The key is unique per user, so a retry can never create a second trade.
-- Trades sent without a key keep NULL, and NULLs do not clash in a unique index.
ALTER TABLE trades ADD COLUMN idempotency_key VARCHAR(64);
CREATE UNIQUE INDEX uq_trades_user_idempotency_key ON trades (user_id, idempotency_key);
