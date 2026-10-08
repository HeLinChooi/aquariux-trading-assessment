-- Finding the latest price of a pair sorts by created_at. The scheduler adds
-- two rows every 10 seconds, so without this index every trade would scan a
-- table that grows by about 17,000 rows a day.
CREATE INDEX idx_crypto_prices_pair_created ON crypto_prices (crypto_pair_id, created_at);
