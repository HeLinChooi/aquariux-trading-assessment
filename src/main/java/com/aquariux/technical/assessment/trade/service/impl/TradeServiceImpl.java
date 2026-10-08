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
import com.aquariux.technical.assessment.trade.service.TradeServiceInterface;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Locale;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradeServiceImpl implements TradeServiceInterface {

    /** Matches the DECIMAL(20,8) columns. */
    private static final int AMOUNT_SCALE = 8;

    private final TradeMapper tradeMapper;
    private final UserMapper userMapper;
    private final CryptoPairMapper cryptoPairMapper;
    private final CryptoPriceMapper cryptoPriceMapper;
    private final UserWalletMapper userWalletMapper;

    /** A price older than this is not traded on, because the market may have moved. */
    @Value("${trade.price.max-age-seconds:60}")
    private long maxPriceAgeSeconds;

    /**
     * Executes the whole trade at the latest best price, or nothing at all:
     * the balance changes and the trade record share one transaction.
     */
    @Override
    @Transactional
    public TradeResponse executeTrade(TradeRequest tradeRequest) {
        Long userId = tradeRequest.getUserId();
        lockUser(userId);
        TradingPairDto pair = findActivePair(tradeRequest.getPairName());
        CryptoPrice latestPrice = findRecentPrice(pair);

        // A buyer pays the lowest ask; a seller receives the highest bid.
        boolean isBuy = tradeRequest.getTradeType() == TradeType.BUY;
        BigDecimal price = isBuy ? latestPrice.getAskPrice() : latestPrice.getBidPrice();
        if (price == null || price.signum() <= 0) {
            throw new PriceUnavailableException("No valid price available for " + pair.getPairName());
        }

        BigDecimal quantity = tradeRequest.getQuantity();
        BigDecimal totalAmount = price.multiply(quantity).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);

        if (isBuy) {
            transfer(userId,
                    pair.getQuoteSymbolId(), pair.getQuoteSymbol(), totalAmount,
                    pair.getBaseSymbolId(), quantity);
        } else {
            transfer(userId,
                    pair.getBaseSymbolId(), pair.getBaseSymbol(), quantity,
                    pair.getQuoteSymbolId(), totalAmount);
        }

        Trade trade = new Trade();
        trade.setUserId(userId);
        trade.setCryptoPairId(pair.getId());
        trade.setTradeType(tradeRequest.getTradeType().name());
        trade.setQuantity(quantity);
        trade.setPrice(price);
        trade.setTotalAmount(totalAmount);
        trade.setTradeTime(LocalDateTime.now());
        tradeMapper.insertTrade(trade);

        log.info("Trade {} executed: user {} {} {} {} at {} for {} {}",
                trade.getId(), userId, trade.getTradeType(), quantity, pair.getBaseSymbol(),
                price, totalAmount, pair.getQuoteSymbol());

        return toResponse(trade, pair, tradeRequest.getTradeType());
    }

    /**
     * Locks the user for the rest of the transaction. A second trade for the
     * same user waits here until this one commits or rolls back, so two trades
     * never update the same wallets at the same time.
     */
    private void lockUser(Long userId) {
        if (userMapper.lockById(userId) == null) {
            throw new ResourceNotFoundException("User " + userId + " not found");
        }
    }

    private TradingPairDto findActivePair(String pairName) {
        String normalized = pairName.trim().toUpperCase(Locale.ROOT);
        TradingPairDto pair = cryptoPairMapper.findByPairName(normalized);
        if (pair == null || !Boolean.TRUE.equals(pair.getActive())) {
            throw new InvalidTradeException("Unsupported trading pair: " + normalized);
        }
        return pair;
    }

    private CryptoPrice findRecentPrice(TradingPairDto pair) {
        CryptoPrice price = cryptoPriceMapper.findLatestByPairId(pair.getId());
        if (price == null) {
            throw new PriceUnavailableException("No price available for " + pair.getPairName());
        }
        LocalDateTime oldestAllowed = LocalDateTime.now().minusSeconds(maxPriceAgeSeconds);
        if (price.getCreatedAt() == null || price.getCreatedAt().isBefore(oldestAllowed)) {
            throw new PriceUnavailableException("Latest price for " + pair.getPairName()
                    + " is older than " + maxPriceAgeSeconds + " seconds");
        }
        return price;
    }

    /** Moves value between two of the user's wallets. The user is already locked. */
    private void transfer(Long userId,
                          Long debitSymbolId, String debitSymbol, BigDecimal debitAmount,
                          Long creditSymbolId, BigDecimal creditAmount) {
        debit(userId, debitSymbolId, debitSymbol, debitAmount);
        credit(userId, creditSymbolId, creditAmount);
    }

    /** Fails if the balance does not cover the amount; the transaction then rolls back. */
    private void debit(Long userId, Long symbolId, String symbol, BigDecimal amount) {
        if (userWalletMapper.debit(userId, symbolId, amount) == 0) {
            throw new InsufficientBalanceException("Insufficient " + symbol + " balance");
        }
    }

    /**
     * Creates the wallet row the first time a user acquires a coin. No other
     * trade can create it at the same moment, because the user is locked.
     */
    private void credit(Long userId, Long symbolId, BigDecimal amount) {
        if (userWalletMapper.credit(userId, symbolId, amount) == 0) {
            userWalletMapper.insertWallet(userId, symbolId, amount);
        }
    }

    private TradeResponse toResponse(Trade trade, TradingPairDto pair, TradeType tradeType) {
        TradeResponse response = new TradeResponse();
        response.setTradeId(trade.getId());
        response.setUserId(trade.getUserId());
        response.setPairName(pair.getPairName());
        response.setTradeType(tradeType);
        response.setQuantity(trade.getQuantity());
        response.setPrice(trade.getPrice());
        response.setTotalAmount(trade.getTotalAmount());
        response.setTradeTime(trade.getTradeTime());
        return response;
    }
}
