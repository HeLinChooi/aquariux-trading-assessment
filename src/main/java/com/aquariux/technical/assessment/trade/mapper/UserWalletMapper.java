package com.aquariux.technical.assessment.trade.mapper;

import com.aquariux.technical.assessment.trade.dto.internal.UserWalletDto;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface UserWalletMapper {
    
    @Select("""
            SELECT s.symbol, s.name, uw.balance 
            FROM symbols s 
            INNER JOIN user_wallets uw ON s.id = uw.symbol_id AND uw.user_id = #{userId} 
            ORDER BY s.symbol
            """)
    List<UserWalletDto> findByUserId(Long userId);

    /**
     * Subtracts the amount only if the balance covers it. The check and the
     * update are one statement, so two trades at the same time cannot both
     * spend the same balance.
     *
     * @return 1 if debited, 0 if the balance is insufficient or the wallet does not exist
     */
    @Update("""
            UPDATE user_wallets
            SET balance = balance - #{amount}, updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId} AND symbol_id = #{symbolId} AND balance >= #{amount}
            """)
    int debit(@Param("userId") Long userId, @Param("symbolId") Long symbolId, @Param("amount") BigDecimal amount);

    /** @return 1 if credited, 0 if the user has no wallet for this coin yet */
    @Update("""
            UPDATE user_wallets
            SET balance = balance + #{amount}, updated_at = CURRENT_TIMESTAMP
            WHERE user_id = #{userId} AND symbol_id = #{symbolId}
            """)
    int credit(@Param("userId") Long userId, @Param("symbolId") Long symbolId, @Param("amount") BigDecimal amount);

    @Insert("""
            INSERT INTO user_wallets (user_id, symbol_id, balance, updated_at)
            VALUES (#{userId}, #{symbolId}, #{balance}, CURRENT_TIMESTAMP)
            """)
    void insertWallet(@Param("userId") Long userId, @Param("symbolId") Long symbolId, @Param("balance") BigDecimal balance);
}
