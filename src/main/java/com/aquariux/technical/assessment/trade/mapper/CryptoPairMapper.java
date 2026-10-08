package com.aquariux.technical.assessment.trade.mapper;

import com.aquariux.technical.assessment.trade.dto.internal.TradingPairDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CryptoPairMapper {
    
    @Select("""
            SELECT id FROM crypto_pairs WHERE pair_name = #{pairName}
            """)
    Long findIdByPairName(String pairName);

    /** The pair counts as active only when the pair and both of its coins are active. */
    @Select("""
            SELECT cp.id, cp.pair_name,
                   (cp.active AND base.active AND quote.active) AS active,
                   cp.base_symbol_id, base.symbol AS base_symbol,
                   cp.quote_symbol_id, quote.symbol AS quote_symbol
            FROM crypto_pairs cp
            INNER JOIN symbols base ON cp.base_symbol_id = base.id
            INNER JOIN symbols quote ON cp.quote_symbol_id = quote.id
            WHERE cp.pair_name = #{pairName}
            """)
    TradingPairDto findByPairName(String pairName);
}
