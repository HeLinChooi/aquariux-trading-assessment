package com.aquariux.technical.assessment.trade.service.impl;

import com.aquariux.technical.assessment.trade.dto.internal.TradingPairDto;
import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;
import com.aquariux.technical.assessment.trade.entity.CryptoPrice;
import com.aquariux.technical.assessment.trade.entity.Trade;
import com.aquariux.technical.assessment.trade.enums.TradeType;
import com.aquariux.technical.assessment.trade.exception.InsufficientBalanceException;
import com.aquariux.technical.assessment.trade.exception.InvalidTradeException;
import com.aquariux.technical.assessment.trade.exception.PriceUnavailableException;
import com.aquariux.technical.assessment.trade.exception.ResourceNotFoundException;
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

    private void givenUserPairAndPrice() {
        when(userMapper.lockById(USER_ID)).thenReturn(USER_ID);
        when(cryptoPairMapper.findByPairName("BTCUSDT")).thenReturn(btcUsdt);
        when(cryptoPriceMapper.findLatestByPairId(1L)).thenReturn(btcPrice);
    }

    @Test
    void executeTrade_Buy_ShouldPayAskDebitUsdtCreditBtcAndRecordTrade() {
        // Given
        givenUserPairAndPrice();
        when(userWalletMapper.debit(USER_ID, USDT_ID, new BigDecimal("600.10000000"))).thenReturn(1);
        when(userWalletMapper.credit(USER_ID, BTC_ID, new BigDecimal("0.01"))).thenReturn(1);

        // When
        TradeResponse response = tradeService.executeTrade(request(TradeType.BUY, "0.01"));

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
        TradeResponse response = tradeService.executeTrade(request(TradeType.SELL, "0.5"));

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
        tradeService.executeTrade(request(TradeType.BUY, "0.01"));

        // Then
        verify(userWalletMapper).insertWallet(USER_ID, BTC_ID, new BigDecimal("0.01"));
    }

    @Test
    void executeTrade_WhenUsdtBalanceTooLow_ShouldRejectAndNotRecordTrade() {
        // Given
        givenUserPairAndPrice();
        when(userWalletMapper.debit(eq(USER_ID), eq(USDT_ID), any())).thenReturn(0);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request(TradeType.BUY, "100")))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessage("Insufficient USDT balance");
        verify(tradeMapper, never()).insertTrade(any());
    }

    @Test
    void executeTrade_WhenBtcBalanceTooLow_ShouldReject() {
        // Given
        givenUserPairAndPrice();
        when(userWalletMapper.debit(eq(USER_ID), eq(BTC_ID), any())).thenReturn(0);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request(TradeType.SELL, "5")))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessage("Insufficient BTC balance");
        verify(tradeMapper, never()).insertTrade(any());
    }

    @Test
    void executeTrade_WhenUserDoesNotExist_ShouldThrowNotFound() {
        // Given
        when(userMapper.lockById(USER_ID)).thenReturn(null);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request(TradeType.BUY, "1")))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(userWalletMapper, never()).debit(anyLong(), anyLong(), any());
    }

    @Test
    void executeTrade_WhenPairUnsupported_ShouldReject() {
        // Given
        when(userMapper.lockById(USER_ID)).thenReturn(USER_ID);
        TradeRequest request = request(TradeType.BUY, "1");
        request.setPairName("dogeusdt");

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request))
                .isInstanceOf(InvalidTradeException.class)
                .hasMessage("Unsupported trading pair: DOGEUSDT");
    }

    @Test
    void executeTrade_WhenPairInactive_ShouldReject() {
        // Given
        when(userMapper.lockById(USER_ID)).thenReturn(USER_ID);
        btcUsdt.setActive(false);
        when(cryptoPairMapper.findByPairName("BTCUSDT")).thenReturn(btcUsdt);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request(TradeType.BUY, "1")))
                .isInstanceOf(InvalidTradeException.class);
    }

    @Test
    void executeTrade_WhenNoPrice_ShouldBeUnavailable() {
        // Given
        when(userMapper.lockById(USER_ID)).thenReturn(USER_ID);
        when(cryptoPairMapper.findByPairName("BTCUSDT")).thenReturn(btcUsdt);
        when(cryptoPriceMapper.findLatestByPairId(1L)).thenReturn(null);

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request(TradeType.BUY, "1")))
                .isInstanceOf(PriceUnavailableException.class);
    }

    @Test
    void executeTrade_WhenPriceIsStale_ShouldBeUnavailable() {
        // Given
        givenUserPairAndPrice();
        btcPrice.setCreatedAt(LocalDateTime.now().minusMinutes(5));

        // When / Then
        assertThatThrownBy(() -> tradeService.executeTrade(request(TradeType.BUY, "1")))
                .isInstanceOf(PriceUnavailableException.class)
                .hasMessageContaining("older than 60 seconds");
        verify(userWalletMapper, never()).debit(anyLong(), anyLong(), any());
    }
}
