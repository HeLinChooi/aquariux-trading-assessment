package com.aquariux.technical.assessment.trade.scheduler;

import com.aquariux.technical.assessment.trade.entity.CryptoPrice;
import com.aquariux.technical.assessment.trade.mapper.CryptoPairMapper;
import com.aquariux.technical.assessment.trade.mapper.CryptoPriceMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PriceUpdateSchedulerTest {

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private CryptoPairMapper cryptoPairMapper;

    @Mock
    private CryptoPriceMapper cryptoPriceMapper;

    @InjectMocks
    private PriceUpdateScheduler scheduler;

    @Test
    void updatePrices_ShouldStoreHighestBidAndLowestAskUnderTheirOwnPair() {
        // Given
        List<Map<String, Object>> binance = List.of(
                Map.of("symbol", "BTCUSDT", "bidPrice", "100.00", "askPrice", "101.00"),
                Map.of("symbol", "ETHUSDT", "bidPrice", "10.00", "askPrice", "11.00"));
        Map<String, Object> huobi = Map.of("data", List.of(
                Map.of("symbol", "btcusdt", "bid", 100.50, "ask", 100.80),
                Map.of("symbol", "ethusdt", "bid", 9.00, "ask", 12.00)));
        when(restTemplate.getForObject(anyString(), eq(List.class))).thenReturn(binance);
        when(restTemplate.getForObject(anyString(), eq(Map.class))).thenReturn(huobi);
        when(cryptoPairMapper.findIdByPairName("BTCUSDT")).thenReturn(1L);
        when(cryptoPairMapper.findIdByPairName("ETHUSDT")).thenReturn(2L);

        // When
        scheduler.updatePrices();

        // Then
        ArgumentCaptor<CryptoPrice> captor = ArgumentCaptor.forClass(CryptoPrice.class);
        verify(cryptoPriceMapper, times(2)).insertPrice(captor.capture());

        CryptoPrice btc = captor.getAllValues().get(0);
        assertThat(btc.getCryptoPairId()).isEqualTo(1L);
        assertThat(btc.getBidPrice()).isEqualByComparingTo("100.50");
        assertThat(btc.getBidSource()).isEqualTo("HUOBI");
        assertThat(btc.getAskPrice()).isEqualByComparingTo("100.80");
        assertThat(btc.getAskSource()).isEqualTo("HUOBI");

        CryptoPrice eth = captor.getAllValues().get(1);
        assertThat(eth.getCryptoPairId()).isEqualTo(2L);
        assertThat(eth.getBidPrice()).isEqualByComparingTo("10.00");
        assertThat(eth.getBidSource()).isEqualTo("BINANCE");
        assertThat(eth.getAskPrice()).isEqualByComparingTo("11.00");
        assertThat(eth.getAskSource()).isEqualTo("BINANCE");
    }
}
