package com.aquariux.technical.assessment.trade.service.impl;

import com.aquariux.technical.assessment.trade.dto.internal.TradingPairDto;
import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;
import com.aquariux.technical.assessment.trade.entity.CryptoPrice;
import com.aquariux.technical.assessment.trade.entity.Trade;
import com.aquariux.technical.assessment.trade.enums.TradeType;
import com.aquariux.technical.assessment.trade.exception.IdempotencyKeyReusedException;
import com.aquariux.technical.assessment.trade.exception.InsufficientBalanceException;
import com.aquariux.technical.assessment.trade.exception.InvalidTradeException;
import com.aquariux.technical.assessment.trade.exception.PriceUnavailableException;
import com.aquariux.technical.assessment.trade.exception.ResourceNotFoundException;
import com.aquariux.technical.assessment.trade.exception.WalletNotFoundException;
import com.aquariux.technical.assessment.trade.mapper.CryptoPairMapper;
import com.aquariux.technical.assessment.trade.mapper.CryptoPriceMapper;
import com.aquariux.technical.assessment.trade.mapper.TradeMapper;
import com.aquariux.technical.assessment.trade.mapper.UserMapper;
import com.aquariux.technical.assessment.trade.mapper.UserWalletMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradeServiceImplTest {

    private static final Long USER_ID = 1L;
    private static final Long BTC_ID = 1L;
    private static final Long USDT_ID = 3L;
    private static final String KEY = "key-0";

    @Mock
    private TradeMapper tradeMapper;

    @Mock
    private UserMapper userMapper;

    @Mock
    private CryptoPairMapper cryptoPairMapper;

    @Mock
    private CryptoPriceMapper cryptoPriceMapper;

    @Mock
    private UserWalletMapper userWalletMapper;

    @InjectMocks
    private TradeServiceImpl tradeService;

    private TradingPairDto btcUsdt;
    private CryptoPrice btcPrice;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(tradeService, "maxPriceAgeSeconds", 60L);

        btcUsdt = new TradingPairDto();
        btcUsdt.setId(1L);
        btcUsdt.setPairName("BTCUSDT");
        btcUsdt.setBaseSymbolId(BTC_ID);
        btcUsdt.setBaseSymbol("BTC");
        btcUsdt.setQuoteSymbolId(USDT_ID);
        btcUsdt.setQuoteSymbol("USDT");
        btcUsdt.setActive(true);

        btcPrice = new CryptoPrice();
        btcPrice.setCryptoPairId(1L);
        btcPrice.setBidPrice(new BigDecimal("60000.00"));
        btcPrice.setAskPrice(new BigDecimal("60010.00"));
        btcPrice.setCreatedAt(LocalDateTime.now().minusSeconds(5));
    }

    private TradeRequest request(TradeType type, String quantity) {
        TradeRequest request = new TradeRequest();
        request.setUserId(USER_ID);
        request.setPairName("BTCUSDT");
        request.setTradeType(type);
        request.setQuantity(new BigDecimal(quantity));
        return request;
    }

    private void givenPairAndPrice() {
        when(cryptoPairMapper.findByPairName("BTCUSDT")).thenReturn(btcUsdt);
        when(cryptoPriceMapper.findLatestByPairId(1L)).thenReturn(btcPrice);
    }

    private void givenUserPairAndPrice() {
        givenPairAndPrice();
        when(userMapper.lockById(USER_ID)).thenReturn(USER_ID);
    }

    @Test
    void executeTrade_Buy_ShouldPayAskDebitUsdtCreditBtcAndRecordTrade() {
        // Given
        givenUserPairAndPrice();
        when(userWalletMapper.debit(USER_ID, USDT_ID, new BigDecimal("600.10000000"))).thenReturn(1);
        when(userWalletMapper.credit(USER_ID, BTC_ID, new BigDecimal("0.01"))).thenReturn(1);

        // When
        TradeResponse response = tradeService.executeTrade(request(TradeType.BUY, "0.01"), KEY);

        // Then
        assertThat(response.getPrice()).isEqualByComparingTo("60010.00");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("600.10");
        assertThat(response.getTradeType()).isEqualTo(TradeType.BUY);
        assertThat(response.getPairName()).isEqualTo("BTCUSDT");

        ArgumentCaptor<Trade> trade = ArgumentCaptor.forClass(Trade.class);
        verify(tradeMapper).insertTrade(trade.capture());
        assertThat(trade.getValue().getTradeType()).isEqualTo("BUY");
        assertThat(trade.getValue().getTotalAmount()).isEqualByComparingTo("600.10");
    }

    @Test
    void executeTrade_Sell_ShouldReceiveBidDebitBtcAndCreditUsdt() {
        // Given
        givenUserPairAndPrice();
        when(userWalletMapper.debit(USER_ID, BTC_ID, new BigDecimal("0.5"))).thenReturn(1);
        when(userWalletMapper.credit(USER_ID, USDT_ID, new BigDecimal("30000.00000000"))).thenReturn(1);

        // When
        TradeResponse response = tradeService.executeTrade(request(TradeType.SELL, "0.5"), KEY);

        // Then
        assertThat(response.getPrice()).isEqualByComparingTo("60000.00");
        assertThat(response.getTotalAmount()).isEqualByComparingTo("30000");
        verify(tradeMapper).insertTrade(any(Trade.class));
    }

    @Test
    void executeTrade_FirstPurchaseOfCoin_ShouldCreateWallet() {
        // Given
        givenUserPairAndPrice();
        when(userWalletMapper.debit(eq(USER_ID), eq(USDT_ID), any())).thenReturn(1);
        when(userWalletMapper.credit(USER_ID, BTC_ID, new BigDecimal("0.01"))).thenReturn(0);

        // When
        tradeService.executeTrade(request(TradeType.BUY, "0.01"), KEY);

        // Then
        verify(userWalletMapper).insertWallet(USER_ID, BTC_ID, new BigDecimal("0.01"));
    }

    @Test
    void executeTrade_WhenUsdtBalanceTooLow_ShouldRejectAndNotRecordTrade() {
        // Given
        givenUserPairAndPrice();
        when(userWalletMapper.debit(eq(USER_ID), eq(USDT_ID), any())).thenReturn(0);
        when(userWalletMapper.findBalance(USER_ID, USDT_ID)).thenReturn(new BigDecimal("50"));

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request(TradeType.BUY, "100"), KEY))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessage("Insufficient USDT balance");
        verify(tradeMapper, never()).insertTrade(any());
    }

    @Test
    void executeTrade_WhenBtcBalanceTooLow_ShouldReject() {
        // Given
        givenUserPairAndPrice();
        when(userWalletMapper.debit(eq(USER_ID), eq(BTC_ID), any())).thenReturn(0);
        when(userWalletMapper.findBalance(USER_ID, BTC_ID)).thenReturn(new BigDecimal("1"));

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request(TradeType.SELL, "5"), KEY))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessage("Insufficient BTC balance");
        verify(tradeMapper, never()).insertTrade(any());
    }

    @Test
    void executeTrade_WhenSellingCoinWithNoWallet_ShouldSayWalletNotFound() {
        // Given: the user has never held BTC
        givenUserPairAndPrice();
        when(userWalletMapper.debit(eq(USER_ID), eq(BTC_ID), any())).thenReturn(0);
        when(userWalletMapper.findBalance(USER_ID, BTC_ID)).thenReturn(null);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request(TradeType.SELL, "0.1"), KEY))
                .isInstanceOf(WalletNotFoundException.class)
                .hasMessage("No BTC wallet");
        verify(tradeMapper, never()).insertTrade(any());
    }

    @Test
    void executeTrade_WhenUserDoesNotExist_ShouldThrowNotFound() {
        // Given
        givenPairAndPrice();
        when(userMapper.lockById(USER_ID)).thenReturn(null);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request(TradeType.BUY, "1"), KEY))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(userWalletMapper, never()).debit(anyLong(), anyLong(), any());
    }

    @Test
    void executeTrade_WhenPairUnsupported_ShouldReject() {
        // Given
        TradeRequest request = request(TradeType.BUY, "1");
        request.setPairName("dogeusdt");

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request, KEY))
                .isInstanceOf(InvalidTradeException.class)
                .hasMessage("Unsupported trading pair: DOGEUSDT");
    }

    @Test
    void executeTrade_WhenPairInactive_ShouldReject() {
        // Given
        btcUsdt.setActive(false);
        when(cryptoPairMapper.findByPairName("BTCUSDT")).thenReturn(btcUsdt);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request(TradeType.BUY, "1"), KEY))
                .isInstanceOf(InvalidTradeException.class);
    }

    @Test
    void executeTrade_WhenNoPrice_ShouldBeUnavailable() {
        // Given
        when(cryptoPairMapper.findByPairName("BTCUSDT")).thenReturn(btcUsdt);
        when(cryptoPriceMapper.findLatestByPairId(1L)).thenReturn(null);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request(TradeType.BUY, "1"), KEY))
                .isInstanceOf(PriceUnavailableException.class);
    }

    @Test
    void executeTrade_WhenPriceIsStale_ShouldBeUnavailableWithoutLockingTheUser() {
        // Given
        givenPairAndPrice();
        btcPrice.setCreatedAt(LocalDateTime.now().minusMinutes(5));

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request(TradeType.BUY, "1"), KEY))
                .isInstanceOf(PriceUnavailableException.class)
                .hasMessageContaining("older than 60 seconds");
        verify(userMapper, never()).lockById(anyLong());
        verify(userWalletMapper, never()).debit(anyLong(), anyLong(), any());
    }

    private TradeResponse earlierBuy(String quantity) {
        TradeResponse earlier = new TradeResponse();
        earlier.setTradeId(42L);
        earlier.setUserId(USER_ID);
        earlier.setPairName("BTCUSDT");
        earlier.setTradeType(TradeType.BUY);
        earlier.setQuantity(new BigDecimal(quantity));
        earlier.setPrice(new BigDecimal("60010.00"));
        earlier.setTotalAmount(new BigDecimal("600.10"));
        return earlier;
    }

    @Test
    void executeTrade_WithNewKey_ShouldStoreTheKeyWithTheTrade() {
        // Given
        givenUserPairAndPrice();
        when(tradeMapper.findByIdempotencyKey(USER_ID, "key-1")).thenReturn(null);
        when(userWalletMapper.debit(eq(USER_ID), eq(USDT_ID), any())).thenReturn(1);
        when(userWalletMapper.credit(eq(USER_ID), eq(BTC_ID), any())).thenReturn(1);

        // When
        tradeService.executeTrade(request(TradeType.BUY, "0.01"), "key-1");

        // Then
        ArgumentCaptor<Trade> trade = ArgumentCaptor.forClass(Trade.class);
        verify(tradeMapper).insertTrade(trade.capture());
        assertThat(trade.getValue().getIdempotencyKey()).isEqualTo("key-1");
    }

    @Test
    void executeTrade_RepeatWithSameKey_ShouldReturnFirstTradeWithoutTradingAgain() {
        // Given: the first request with this key already traded
        when(tradeMapper.findByIdempotencyKey(USER_ID, "key-1")).thenReturn(earlierBuy("0.01"));

        // When
        TradeResponse response = tradeService.executeTrade(request(TradeType.BUY, "0.01"), "key-1");

        // Then
        assertThat(response.getTradeId()).isEqualTo(42L);
        verify(userMapper, never()).lockById(anyLong());
        verify(userWalletMapper, never()).debit(anyLong(), anyLong(), any());
        verify(tradeMapper, never()).insertTrade(any());
    }

    @Test
    void executeTrade_SameKeyCommittedWhileWaitingForLock_ShouldReturnThatTrade() {
        // Given: not found before the lock; found after it, committed by a request with the same key
        givenUserPairAndPrice();
        when(tradeMapper.findByIdempotencyKey(USER_ID, "key-1")).thenReturn(null, earlierBuy("0.01"));

        // When
        TradeResponse response = tradeService.executeTrade(request(TradeType.BUY, "0.01"), "key-1");

        // Then
        assertThat(response.getTradeId()).isEqualTo(42L);
        verify(userWalletMapper, never()).debit(anyLong(), anyLong(), any());
        verify(tradeMapper, never()).insertTrade(any());
    }

    @Test
    void executeTrade_SameKeyDifferentQuantity_ShouldBeRefused() {
        // Given
        when(tradeMapper.findByIdempotencyKey(USER_ID, "key-1")).thenReturn(earlierBuy("0.01"));

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request(TradeType.BUY, "0.02"), "key-1"))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        verify(tradeMapper, never()).insertTrade(any());
    }

    @Test
    void executeTrade_KeyLongerThan64Characters_ShouldBeRejected() {
        assertThatThrownBy(() -> tradeService.executeTrade(request(TradeType.BUY, "0.01"), "k".repeat(65)))
                .isInstanceOf(InvalidTradeException.class)
                .hasMessage("Idempotency-Key must be 1 to 64 characters");
    }

    @Test
    void executeTrade_WithoutKey_ShouldBeRejectedBeforeAnyWork() {
        assertThatThrownBy(() -> tradeService.executeTrade(request(TradeType.BUY, "0.01"), null))
                .isInstanceOf(InvalidTradeException.class)
                .hasMessage("Idempotency-Key is required");
        verify(tradeMapper, never()).findByIdempotencyKey(anyLong(), any());
        verify(userMapper, never()).lockById(anyLong());
    }
}
