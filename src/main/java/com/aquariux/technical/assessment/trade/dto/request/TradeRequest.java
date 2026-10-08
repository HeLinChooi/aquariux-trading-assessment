package com.aquariux.technical.assessment.trade.dto.request;

import com.aquariux.technical.assessment.trade.enums.TradeType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class TradeRequest {

    @NotNull
    @Positive
    private Long userId;

    /** Trading pair such as BTCUSDT or ETHUSDT. */
    @NotBlank
    private String pairName;

    @NotNull
    private TradeType tradeType;

    /** Amount of the base coin, for example BTC in BTCUSDT. Matches DECIMAL(20,8). */
    @NotNull
    @DecimalMin(value = "0", inclusive = false)
    @Digits(integer = 12, fraction = 8)
    private BigDecimal quantity;
}
