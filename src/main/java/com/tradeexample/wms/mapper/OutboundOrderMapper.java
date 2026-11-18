package com.tradeexample.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradeexample.wms.entity.OutboundOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 读写出库单主表。 */
public interface OutboundOrderMapper extends BaseMapper<OutboundOrder> {

    /** 在事务内锁住这张出库单，让同一张单的提交请求依次判断状态。 */
    @Select("""
            SELECT *
            FROM wms_outbound_order
            WHERE id = #{id}
            FOR UPDATE
            """)
    OutboundOrder selectByIdForUpdate(@Param("id") Long id);

    /** 只有状态、修订号和版本都匹配的草稿才能转为已提交。 */
    @Update("""
            UPDATE wms_outbound_order
            SET status = 'SUBMITTED',
                version = version + 1
            WHERE id = #{id}
              AND status = 'DRAFT'
              AND revision = #{revision}
              AND version = #{expectedVersion}
            """)
    int markSubmitted(
            @Param("id") Long id,
            @Param("revision") Integer revision,
            @Param("expectedVersion") Integer expectedVersion
    );
}