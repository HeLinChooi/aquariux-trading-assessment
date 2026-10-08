package com.aquariux.technical.assessment.trade.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    /**
     * Locks the user's row until the transaction ends, so the same user's
     * trades run one at a time.
     *
     * @return the user id, or null if the user does not exist
     */
    @Select("""
            SELECT id FROM users WHERE id = #{userId} FOR UPDATE
            """)
    Long lockById(Long userId);
}
