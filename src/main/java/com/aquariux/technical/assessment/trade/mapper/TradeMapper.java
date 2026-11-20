package com.aquariux.technical.assessment.trade.mapper;

import com.aquariux.technical.assessment.trade.dto.response.TradeResponse;
import com.aquariux.technical.assessment.trade.entity.Trade;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TradeMapper {

    /** Trades are only ever inserted, never updated, so the audit trail cannot change. */
    @Insert("""
            INSERT INTO trades (user_id, crypto_pair_id, trade_type, quantity, price, total_amount, trade_time, idempotency_key)
            VALUES (#{userId}, #{cryptoPairId}, #{tradeType}, #{quantity}, #{price}, #{totalAmount}, #{tradeTime}, #{idempotencyKey})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertTrade(Trade trade);

    /**
     * The trade this user already made with this key, as it was returned, or null.
     *
     * <p>flushCache: the service runs this query twice in one transaction, before
     * and after waiting for the user lock. Without it, MyBatis would return the
     * first result from its session cache, and miss a trade that a request with
     * the same key committed while this one waited.
     */
    @Select("""
            SELECT t.id AS trade_id, t.user_id, cp.pair_name, t.trade_type,
                   t.quantity, t.price, t.total_amount, t.trade_time
            FROM trades t
            JOIN crypto_pairs cp ON cp.id = t.crypto_pair_id
            WHERE t.user_id = #{userId} AND t.idempotency_key = #{idempotencyKey}
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    TradeResponse findByIdempotencyKey(@Param("userId") Long userId, @Param("idempotencyKey") String idempotencyKey);
}
