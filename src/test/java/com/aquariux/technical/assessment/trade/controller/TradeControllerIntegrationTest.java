package com.aquariux.technical.assessment.trade.controller;

import com.aquariux.technical.assessment.trade.dto.response.WalletBalanceResponse;
import com.aquariux.technical.assessment.trade.entity.CryptoPrice;
import com.aquariux.technical.assessment.trade.mapper.CryptoPriceMapper;
import com.aquariux.technical.assessment.trade.service.WalletServiceInterface;
import org.junit.jupiter.api.BeforeEach;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the endpoint against the real H2 database and the Flyway sample data.
 * The scheduler is off so the prices are fixed; each test is rolled back.
 */
@SpringBootTest(properties = "scheduler.price-update.enabled=false")
@AutoConfigureMockMvc
@Transactional
class TradeControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CryptoPriceMapper cryptoPriceMapper;

    @Autowired
    private WalletServiceInterface walletService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    /** A current BTCUSDT price: bid 60,000, ask 60,010. */
    @BeforeEach
    void givenCurrentBtcPrice() {
        CryptoPrice price = new CryptoPrice();
        price.setCryptoPairId(1L);
        price.setBidPrice(new BigDecimal("60000.00"));
        price.setAskPrice(new BigDecimal("60010.00"));
        price.setBidSource("BINANCE");
        price.setAskSource("HUOBI");
        cryptoPriceMapper.insertPrice(price);
    }

    private BigDecimal balance(Long userId, String symbol) {
        return walletService.getUserWalletBalances(userId).stream()
                .filter(wallet -> wallet.getSymbol().equals(symbol))
                .map(WalletBalanceResponse::getBalance)
                .findFirst()
                .orElse(null);
    }

    @Test
    void executeTrade_Buy_ShouldCreateBtcWalletAndDebitUsdt() throws Exception {
        // Given: user 1 holds 10,000 USDT and no BTC wallet
        assertThat(balance(1L, "BTC")).isNull();

        // When
        mockMvc.perform(post("/api/trades/execute").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 1, "pairName": "BTCUSDT", "tradeType": "BUY", "quantity": 0.01}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tradeId").isNumber())
                .andExpect(jsonPath("$.price").value(60010.00))
                .andExpect(jsonPath("$.totalAmount").value(600.10));

        // Then
        assertThat(balance(1L, "BTC")).isEqualByComparingTo("0.01");
        assertThat(balance(1L, "USDT")).isEqualByComparingTo("9399.90");
    }

    @Test
    void executeTrade_SellMoreThanHeld_ShouldRejectAndKeepBalances() throws Exception {
        // Given: user 7 holds 0.5 BTC and 20,000 USDT

        // When
        mockMvc.perform(post("/api/trades/execute").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 7, "pairName": "BTCUSDT", "tradeType": "SELL", "quantity": 0.6}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Insufficient BTC balance"))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_BALANCE"));

        // Then
        assertThat(balance(7L, "BTC")).isEqualByComparingTo("0.5");
        assertThat(balance(7L, "USDT")).isEqualByComparingTo("20000");
    }

    @Test
    void executeTrade_SellCoinNeverHeld_ShouldSayWalletNotFound() throws Exception {
        // Given: user 1 has no BTC wallet
        assertThat(balance(1L, "BTC")).isNull();

        // When / Then
        mockMvc.perform(post("/api/trades/execute").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 1, "pairName": "BTCUSDT", "tradeType": "SELL", "quantity": 0.01}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No BTC wallet"))
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"));
    }

    @Test
    void executeTrade_RepeatWithSameKey_ShouldTradeOnceAndReturnTheSameTrade() throws Exception {
        String body = """
                {"userId": 1, "pairName": "BTCUSDT", "tradeType": "BUY", "quantity": 0.01}
                """;

        // When: the client retries with the same key
        String first = mockMvc.perform(post("/api/trades/execute").header("Idempotency-Key", "retry-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(post("/api/trades/execute").header("Idempotency-Key", "retry-1")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // Then: one trade, paid once
        assertThat(JsonPath.<Integer>read(second, "$.tradeId")).isEqualTo(JsonPath.<Integer>read(first, "$.tradeId"));
        assertThat(balance(1L, "USDT")).isEqualByComparingTo("9399.90");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trades WHERE idempotency_key = 'retry-1'", Integer.class)).isEqualTo(1);
    }

    @Test
    void executeTrade_SameKeyForDifferentTrade_ShouldReturn422() throws Exception {
        mockMvc.perform(post("/api/trades/execute").header("Idempotency-Key", "retry-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 1, "pairName": "BTCUSDT", "tradeType": "BUY", "quantity": 0.01}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/trades/execute").header("Idempotency-Key", "retry-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 1, "pairName": "BTCUSDT", "tradeType": "BUY", "quantity": 0.02}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void executeTrade_WithoutIdempotencyKey_ShouldReturnBadRequest() throws Exception {
        mockMvc.perform(post("/api/trades/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 1, "pairName": "BTCUSDT", "tradeType": "BUY", "quantity": 0.01}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Idempotency-Key header is required"));
        assertThat(balance(1L, "BTC")).isNull();
    }

    @Test
    void executeTrade_UnknownUser_ShouldReturnNotFound() throws Exception {
        mockMvc.perform(post("/api/trades/execute").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 999, "pairName": "BTCUSDT", "tradeType": "BUY", "quantity": 1}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void executeTrade_MissingFields_ShouldReturnBadRequest() throws Exception {
        mockMvc.perform(post("/api/trades/execute").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void executeTrade_WhenBaseCoinInactive_ShouldReject() throws Exception {
        // Given: BTC is switched off, while the BTCUSDT pair itself stays active
        jdbcTemplate.update("UPDATE symbols SET active = FALSE WHERE symbol = 'BTC'");

        // When / Then
        mockMvc.perform(post("/api/trades/execute").header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": 1, "pairName": "BTCUSDT", "tradeType": "BUY", "quantity": 0.01}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported trading pair: BTCUSDT"));
    }

    @Test
    void executeTrade_WhileAnotherTransactionHoldsTheUserLock_ShouldReturnConflict() throws Exception {
        // Given: another transaction holds the lock on user 1 and does not finish.
        // H2 stops waiting for a lock after about 2 seconds.
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            other.createStatement().executeQuery("SELECT id FROM users WHERE id = 1 FOR UPDATE");

            // When / Then
            mockMvc.perform(post("/api/trades/execute").header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"userId": 1, "pairName": "BTCUSDT", "tradeType": "BUY", "quantity": 0.01}
                                    """))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Another trade for this user is in progress. Try again"));

            other.rollback();
        }
    }
}
