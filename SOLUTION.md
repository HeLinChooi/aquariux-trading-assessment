# Solution

The trade execution API, `POST /api/trades/execute`, plus fixes to the price
scheduler that the trades depend on. The API specification is in
[API_DESIGN.md](API_DESIGN.md).

## How to run and try it

```bash
./mvnw spring-boot:run
./mvnw test
```

The API can be tried in Swagger UI at
<http://localhost:8080/swagger-ui/index.html>. The first prices arrive about
10 seconds after start-up. Some example requests:

| Scenario | Request body | Result |
|---|---|---|
| First purchase of a coin | `{"userId": 1, "pairName": "BTCUSDT", "tradeType": "BUY", "quantity": 0.01}` | `201`. User 1 had only USDT; a BTC wallet is created |
| Sell | `{"userId": 7, "pairName": "BTCUSDT", "tradeType": "SELL", "quantity": 0.1}` | `201`. User 7's BTC balance changes from 0.5 to 0.4 |
| Not enough to sell | `{"userId": 3, "pairName": "BTCUSDT", "tradeType": "SELL", "quantity": 0.1}` | `400` Insufficient BTC balance |
| Unknown user | `{"userId": 999, "pairName": "BTCUSDT", "tradeType": "BUY", "quantity": 1}` | `404` |
| Unsupported pair | `{"userId": 1, "pairName": "DOGEUSDT", "tradeType": "BUY", "quantity": 1}` | `400` |

You may check the balances afterwards with `GET /api/wallets/user/{userId}`.

## Assumptions

- The platform is the counterparty to every trade: when a user buys BTC, the
  platform is the seller, and when a user sells, the platform is the buyer. It
  reads prices from Binance and Huobi but does not place orders on them.
- A trade fills in full at the latest stored best price, or not at all.
- `quantity` is in the base coin, for example BTC in BTCUSDT.
- No fees, as stated in the business requirements.
- Authentication and authorization happen before requests reach this
  service, for example at an API gateway. The business requirements ask for
  endpoints "protected against unauthorized access". They also assume that
  requests are "pre-authenticated and secured". So this service trusts
  `userId`, and `/api/**` has no protection, as in the provided
  `SecurityConfig`. Without such a gateway, the service should not be
  exposed, because anyone could trade for any `userId`. A possible next step
  is under "Authentication" in With more time.
- The quantity available at the best price is not checked: the price table
  stores no quantities.

## Design decisions

- **One transaction per trade.** The debit, the credit and the trade record
  are saved together or not at all.
- **Balance checked and debited in one SQL statement:**
  `UPDATE ... SET balance = balance - ? WHERE ... AND balance >= ?`. If no row
  changes, the balance is insufficient. Two trades at the same time cannot both
  spend the same balance, without a separate read and lock.
- **The user's row is locked first** (`SELECT ... FOR UPDATE`), so one user's
  trades run one at a time while different users still trade in parallel.
  Without it, a BUY (which changes USDT, then BTC) and a SELL (BTC, then USDT)
  for the same user could each lock one wallet row and wait for the other: a
  deadlock. In a test of 300 simultaneous mixed trades on one user without any
  locking order, 40 failed with "Deadlock detected"; with the user lock, none
  did.
- **A wallet row is created on the first acquisition of a coin**, as the
  business rules require. Because the user is locked, no other trade can create
  the same wallet at the same moment.
- **BUY executes at the best ask, SELL at the best bid.**
- **Stale prices are refused.** A trade needs a price younger than
  `trade.price.max-age-seconds` (default 60), and returns `503` otherwise. The
  sample prices from January 2025 are therefore never traded on.
- **Amounts use `BigDecimal`**, rounded to 8 decimal places, half up, to match
  the `DECIMAL(20,8)` columns.
- **One error format** for every failure: `timestamp`, `status`, `error`,
  `message`. Unexpected errors are logged with details and return a generic
  message.
- **Trades are insert-only**, so the audit trail cannot change.
- **Index for the latest price.** `V5__Index_latest_price_lookup.sql` indexes
  `crypto_prices (crypto_pair_id, created_at)`, so finding the latest price
  stays fast as the price history grows.

## Problems found in the provided code, and fixed

| Problem | Effect | Fix |
|---|---|---|
| `.gitignore` excluded the whole `.mvn/` folder | `./mvnw` failed: "cannot read distributionUrl property" | Only the wrapper jar is ignored now; the wrapper configuration is committed |
| `@EnableScheduling` was imported but never applied | The price scheduler never ran | Applied in `SchedulerConfig` |
| `storePriceHistory` swapped bid and ask, and their sources | Every stored price had bid above ask | Stored in the right columns |
| `getCryptoPairId` swapped BTCUSDT and ETHUSDT | Bitcoin prices were saved as Ethereum prices, and the reverse | Finds the id of the requested pair |
| No validation starter in `pom.xml` | `@Valid` had no effect | Added `spring-boot-starter-validation` |
| `spring-cloud-starter-openfeign` was a dependency but was never used; the scheduler uses `RestTemplate` | A larger build and more libraries to keep updated | Removed, with the Spring Cloud version management that only it needed |
| The exchange HTTP client had no timeouts | One call that never answers would block the scheduler thread and stop all later price updates | 3-second connect and 5-second read timeouts |

In the sample data, `V2__Data_prep_for_price.sql`, the Jan 15 rows use the
right pairs. The Jan 16 rows, which are the latest, are stored under swapped
pairs, as the file's own comment says: `GET /api/prices/latest` therefore
showed BTCUSDT at about 3,177. Every Jan 16 row, and the first three Jan 15
BTCUSDT rows, also have the bid above the ask. The file is not modified,
because Flyway checksums applied migrations and editing one breaks existing
databases. New prices from the fixed scheduler become the latest prices within
10 seconds, and the price age check stops trades on the old rows.

`PriceUpdateSchedulerTest` covers the scheduler fixes, and fails on the
original code.

### Other observations, not changed

- `README.md` offers a Docker option, but the repository has no `Dockerfile`.
- In the sample data, users 1, 2 and 3 have bought and sold BTC and ETH but have
  no wallet rows for those coins, although wallet rows are created on first
  acquisition. A SELL for them is rejected as an insufficient balance, which is
  the correct result.
- The sample data stores whole seconds, such as `2025-01-15 10:00:00`, while
  new prices and trades store microseconds, such as `2026-10-08 15:51:41.866228`.
  Both are `TIMESTAMP` columns. The new values are stored with full precision,
  because the business requirements ask for a precise execution time on every
  trade. Rounding, if needed, belongs where times are displayed.

## Tests

- `TradeServiceImplTest`: unit tests for every trading rule, with mocked mappers.
- `TradeControllerIntegrationTest`: the endpoint against the real H2 database
  and sample data, including the wallet changes.
- `PriceUpdateSchedulerTest`: best bid and ask stored under the right pair.

The scheduler is turned off in tests (`scheduler.price-update.enabled=false`),
so tests never call the real exchanges.

## Performance check

Measured on 8 Oct 2026 on a laptop, with the in-memory H2 database and live
prices, through the HTTP endpoint:

| Test | Result | Response time |
|---|---|---|
| 200 trades, one after another | 200 succeeded | median 1.9 ms, slowest 6.7 ms |
| 100 trades at the same moment, all buying with one user's 45,000 USDT | 54 succeeded; 46 rejected as insufficient balance | median 2.9 ms, slowest 10.5 ms |

After the concurrent test, the USDT balance equalled the starting balance
minus the totals of the 54 successful trades, and the BTC balance rose by
exactly 54 × 0.01. No trade overspent. Trades are fast because they read the
stored price and never call the exchanges. A production setup would add
network time and a real database. So these figures show that the times are
far below the 200 ms target, but they do not guarantee it.

## With more time

- **Idempotency key.** A client that retries after a timeout could execute the
  same trade twice. A client-supplied key with a unique constraint would make
  retries safe.
- **Crossed prices across exchanges.** The best bid on one exchange can be
  higher than the best ask on the other. This was seen repeatedly while
  testing, with the BTCUSDT bid from Binance about 60 USDT above the ask from
  Huobi. A user could then buy and sell at once for a guaranteed profit
  against the platform. Such prices could be rejected,
  or a spread applied.
- **Available quantity.** We shall store the bid and ask quantities in
  `crypto_prices`, and reject trades larger than the market offers at that
  price.
- **A runtime switch for the price feed.** `scheduler.price-update.enabled` is
  read only at start-up. So stopping the price feed during an incident needs a
  restart. Examples of an incident: an exchange sends wrong prices, or limits
  how often this server may call it. A flag checked on every run, stored in the
  database or a feature-flag service, would need no restart. Stopping the feed
  also stops trading after 60 seconds, through the price age check.
- **One scheduler across several instances.** Each running copy of the
  application would run its own scheduler, doubling the exchange calls and the
  stored prices. A shared lock, such as ShedLock, would let only one run.
- **Price history retention.** `crypto_prices` grows by about 17,000 rows a
  day. Older rows could be archived or deleted after a set period.
- **Time zones.** The price age check compares the application clock with the
  database's `CURRENT_TIMESTAMP`. That is safe with the embedded H2 database;
  with a separate database server, we shall store and compare times in UTC.
- **Order size limits.** Each pair could have a minimum and maximum order
  quantity and a step size, as exchanges do.
- **Trade history endpoint** with paging, using the existing `trades` table.
- **Authentication.** We shall take the user from the security context
  instead of the request body.
