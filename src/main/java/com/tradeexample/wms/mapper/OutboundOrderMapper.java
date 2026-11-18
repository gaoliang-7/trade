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

    /** 只作废刚刚检查过的状态、修订和版本。 */
    @Update("""
            UPDATE wms_outbound_order
            SET status = 'CANCELED',
                version = version + 1
            WHERE id = #{id}
              AND status = #{expectedStatus}
              AND revision = #{revision}
              AND version = #{expectedVersion}
            """)
    int markCanceled(
            @Param("id") Long id,
            @Param("expectedStatus") String expectedStatus,
            @Param("revision") Integer revision,
            @Param("expectedVersion") Integer expectedVersion
    );

    /** 原状态、修订和版本都匹配时，切到下一版草稿。 */
    @Update("""
            UPDATE wms_outbound_order
            SET status = 'DRAFT',
                revision = revision + 1,
                version = version + 1,
                total_box_count = #{totalBoxCount}
            WHERE id = #{id}
              AND status = #{expectedStatus}
              AND revision = #{expectedRevision}
              AND version = #{expectedVersion}
            """)
    int markRegenerated(
            @Param("id") Long id,
            @Param("expectedStatus") String expectedStatus,
            @Param("expectedRevision") Integer expectedRevision,
            @Param("expectedVersion") Integer expectedVersion,
            @Param("totalBoxCount") long totalBoxCount
    );
}