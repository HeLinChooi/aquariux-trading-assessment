package com.aquariux.technical.assessment.trade.service.impl;

import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;
import com.aquariux.technical.assessment.trade.enums.TradeType;
import com.aquariux.technical.assessment.trade.service.TradeServiceInterface;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Several requests with the same Idempotency-Key at the same moment, each in its
 * own committed transaction. Only the user lock and the second key lookup stop
 * them all from trading, so this cannot run inside one rolled-back test
 * transaction; the data is restored after the test instead.
 */
@SpringBootTest(properties = "scheduler.price-update.enabled=false")
class IdempotencyConcurrencyTest {

    private static final long USER_ID = 7L;
    private static final long BTC_ID = 1L;
    private static final long USDT_ID = 3L;
    private static final String KEY = "concurrent-1";
    private static final int REQUESTS = 8;

    @Autowired
    private TradeServiceInterface tradeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long lastPriceIdBefore;
    private BigDecimal usdtBefore;
    private BigDecimal btcBefore;

    @BeforeEach
    void givenCurrentPriceAndBalances() {
        lastPriceIdBefore = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM crypto_prices", Long.class);
        jdbcTemplate.update("""
                INSERT INTO crypto_prices (crypto_pair_id, bid_price, ask_price, bid_source, ask_source)
                VALUES (1, 60000.00, 60010.00, 'BINANCE', 'HUOBI')
                """);
        usdtBefore = balance(USDT_ID);
        btcBefore = balance(BTC_ID);
    }

    @AfterEach
    void restoreData() {
        jdbcTemplate.update("DELETE FROM trades WHERE idempotency_key = ?", KEY);
        jdbcTemplate.update("UPDATE user_wallets SET balance = ? WHERE user_id = ? AND symbol_id = ?", usdtBefore, USER_ID, USDT_ID);
        jdbcTemplate.update("UPDATE user_wallets SET balance = ? WHERE user_id = ? AND symbol_id = ?", btcBefore, USER_ID, BTC_ID);
        jdbcTemplate.update("DELETE FROM crypto_prices WHERE id > ?", lastPriceIdBefore);
    }

    private BigDecimal balance(long symbolId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM user_wallets WHERE user_id = ? AND symbol_id = ?", BigDecimal.class, USER_ID, symbolId);
    }

    @Test
    void sameKeyAtTheSameMoment_ShouldTradeOnceAndReturnOneTradeToEveryRequest() throws Exception {
        TradeRequest request = new TradeRequest();
        request.setUserId(USER_ID);
        request.setPairName("BTCUSDT");
        request.setTradeType(TradeType.BUY);
        request.setQuantity(new BigDecimal("0.01"));

        // When: all requests start together
        CountDownLatch start = new CountDownLatch(1);
        Callable<TradeResponse> call = () -> {
            start.await();
            return tradeService.executeTrade(request, KEY);
        };
        ExecutorService pool = Executors.newFixedThreadPool(REQUESTS);
        List<Future<TradeResponse>> results = IntStream.range(0, REQUESTS)
                .mapToObj(i -> pool.submit(call))
                .toList();
        start.countDown();
        Set<Long> tradeIds = new HashSet<>();
        for (Future<TradeResponse> result : results) {
            tradeIds.add(result.get().getTradeId());
        }
        pool.shutdown();

        // Then: one trade, returned to every request, paid for once
        assertThat(tradeIds).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trades WHERE idempotency_key = ?", Integer.class, KEY)).isEqualTo(1);
        assertThat(balance(USDT_ID)).isEqualByComparingTo(usdtBefore.subtract(new BigDecimal("600.10")));
        assertThat(balance(BTC_ID)).isEqualByComparingTo(btcBefore.add(new BigDecimal("0.01")));
    }
}
