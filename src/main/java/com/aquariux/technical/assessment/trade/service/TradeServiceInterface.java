package com.aquariux.technical.assessment.trade.service;

import com.aquariux.technical.assessment.trade.dto.request.TradeRequest;
import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;

public interface TradeServiceInterface {
    /**
     * @param idempotencyKey the client's key for this trade; required. A repeat with the
     *                       same key returns the first trade instead of trading again.
     */
    TradeResponse executeTrade(TradeRequest tradeRequest, String idempotencyKey);
}