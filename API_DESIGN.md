# Trade Execution API Design

## Purpose

Buy or sell a supported crypto pair for a user, at the latest best price.

## Method and URL

`POST /api/trades/execute`

## Request

| Field | Type | Rules |
|---|---|---|
| `userId` | number | Required. The user must exist |
| `pairName` | string | Required. `BTCUSDT` or `ETHUSDT`; must be an active pair |
| `tradeType` | string | Required. `BUY` or `SELL` |
| `quantity` | number | Required. Amount of the base coin (BTC or ETH). Greater than 0, at most 8 decimal places |

```json
{"userId": 1, "pairName": "BTCUSDT", "tradeType": "BUY", "quantity": 0.01}
```

**Required header `Idempotency-Key`**, 1 to 64 characters, for example a UUID.
Creating a trade is not idempotent, so every request carries a key.
A client that gets no response, after a timeout for example, cannot tell
whether the trade was executed. It retries with the same key, and gets the
first trade back, with `201`, instead of a second trade. The key belongs to
the user; a key reused for a different pair, side or quantity gets `422`.

## Response: `201 Created`

```json
{
  "tradeId": 31,
  "userId": 1,
  "pairName": "BTCUSDT",
  "tradeType": "BUY",
  "quantity": 0.01,
  "price": 62000.50,
  "totalAmount": 620.005,
  "tradeTime": "2026-10-08T15:02:11.123"
}
```

## Errors

| Status | When |
|---|---|
| `400` | A field is missing or invalid; quantity ≤ 0 or more than 8 decimal places; unknown trade type |
| `400` | The pair is not supported or not active |
| `400` | No wallet for the coin the trade pays with (USDT for a BUY, the base coin for a SELL). `code`: `WALLET_NOT_FOUND` |
| `400` | The wallet exists, but its balance cannot cover the trade. `code`: `INSUFFICIENT_BALANCE` |
| `400` | `Idempotency-Key` is missing, blank, or longer than 64 characters |
| `404` | The user does not exist |
| `409` | Another trade for the same user held its lock longer than the database waits (2 seconds on H2). Nothing changed; retry with the same `Idempotency-Key` |
| `422` | The `Idempotency-Key` was already used for a different trade |
| `503` | No price for the pair, or the latest price is older than the allowed age |

Every error has the same body. `code` is present only where a client must
tell two causes of the same status apart:

```json
{"timestamp": "2026-10-08T15:02:11.123", "status": 400, "error": "Bad Request", "message": "Insufficient USDT balance", "code": "INSUFFICIENT_BALANCE"}
```

## Business rules (data read)

1. `users`: the user must exist. Their row is locked from just before the balances
   change until the transaction ends, so the same user's trades change balances one
   at a time. The pair and price checks below run before the lock.
2. `crypto_pairs` and `symbols`: the pair must exist, and the pair and both of its coins
   must be active. It gives the base and the quote coin.
3. `crypto_prices`: the latest row for the pair. It must be newer than
   `trade.price.max-age-seconds` (default 60). A BUY uses the ask price;
   a SELL uses the bid price.
4. `totalAmount = price × quantity`, rounded to 8 decimal places, half up.
5. A BUY needs a quote balance ≥ `totalAmount`. A SELL needs a base balance ≥ `quantity`.

## Side effects (data written)

All in one transaction. If any step fails, nothing is saved. The user's trade
with the request's `Idempotency-Key` is looked up first, and again after the
user lock; if found, it is returned and nothing below runs.

1. **Debit** with one conditional update:
   `balance = balance - amount WHERE user_id = ? AND symbol_id = ? AND balance >= amount`.
   No row changed means the wallet is missing or the balance is insufficient; the
   wallet is looked up to say which, and the transaction rolls back.
   Checking and updating in one statement means two trades running at the same
   time cannot both spend the same balance.
2. **Credit** the other coin. If the user has never held it, insert a wallet row
   (wallet rows are created when a user first acquires a coin).
3. **Insert** one row into `trades`. Trade rows are never updated.

| Trade | Debit | Credit |
|---|---|---|
| BUY 0.01 BTCUSDT at 62,000.50 | USDT − 620.005 | BTC + 0.01 |
| SELL 0.01 BTCUSDT at 61,999.50 | BTC − 0.01 | USDT + 619.995 |
