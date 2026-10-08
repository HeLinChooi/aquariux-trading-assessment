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
| `400` | Insufficient balance: the quote coin (USDT) for a BUY, the base coin for a SELL |
| `404` | The user does not exist |
| `503` | No price for the pair, or the latest price is older than the allowed age |

Every error has the same body:

```json
{"timestamp": "2026-10-08T15:02:11.123", "status": 400, "error": "Bad Request", "message": "Insufficient USDT balance"}
```

## Business rules (data read)

1. `users`: the user must exist. Their row is locked until the transaction ends, so the
   same user's trades run one at a time.
2. `crypto_pairs` and `symbols`: the pair must exist, and the pair and both of its coins
   must be active. It gives the base and the quote coin.
3. `crypto_prices`: the latest row for the pair. It must be newer than
   `trade.price.max-age-seconds` (default 60). A BUY uses the ask price;
   a SELL uses the bid price.
4. `totalAmount = price × quantity`, rounded to 8 decimal places, half up.
5. A BUY needs a quote balance ≥ `totalAmount`. A SELL needs a base balance ≥ `quantity`.

## Side effects (data written)

All in one transaction. If any step fails, nothing is saved.

1. **Debit** with one conditional update:
   `balance = balance - amount WHERE user_id = ? AND symbol_id = ? AND balance >= amount`.
   No row changed means the balance is insufficient, and the transaction rolls back.
   Checking and updating in one statement means two trades running at the same
   time cannot both spend the same balance.
2. **Credit** the other coin. If the user has never held it, insert a wallet row
   (wallet rows are created when a user first acquires a coin).
3. **Insert** one row into `trades`. Trade rows are never updated.

| Trade | Debit | Credit |
|---|---|---|
| BUY 0.01 BTCUSDT at 62,000.50 | USDT − 620.005 | BTC + 0.01 |
| SELL 0.01 BTCUSDT at 61,999.50 | BTC − 0.01 | USDT + 619.995 |
