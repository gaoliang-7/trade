package com.tradeexample.wms.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tradeexample.wms.entity.InventoryDetail;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 读写库存批次。 */
public interface InventoryDetailMapper extends BaseMapper<InventoryDetail> {

    /** 版本未变且余量足够时，同时增加净出库箱数、减少在仓箱数。 */
    @Update("""
            UPDATE wms_inventory_detail
            SET output_box_count = output_box_count + #{boxCount},
                stock_box_count = stock_box_count - #{boxCount},
                version = version + 1
            WHERE id = #{id}
              AND version = #{expectedVersion}
              AND stock_box_count >= #{boxCount}
            """)
    int deductStock(
            @Param("id") Long id,
            @Param("boxCount") int boxCount,
            @Param("expectedVersion") Integer expectedVersion
    );
}