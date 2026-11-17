package com.tradeexample.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 库存批次
 */
@Getter
@Setter
@TableName("wms_inventory_detail")
public class InventoryDetail {

    /** 数据库自增主键；新增时不手动指定。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 该库存批次所属的仓库编码。 */
    private String warehouseCode;

    /** 该批次对应的采购单号，参与库存筛选。 */
    private String purchaseOrderNo;

    /** 该批次的产品编号，参与库存筛选。 */
    private String productNo;

    /** 该库存批次对应的入库单号。 */
    private String inboundOrderNo;

    /** 入库日期；FIFO先按此日期升序，同日再按库存ID升序。 */
    private LocalDate inboundDate;

    /** 该批次入库总箱数，不能为负。 */
    private Integer inputBoxCount;

    /** 当前净出库箱数；提交时增加，作废恢复时减少。 */
    private Integer outputBoxCount;

    /** 当前实际在仓箱数，始终等于入库箱数减去净出库箱数。 */
    private Integer stockBoxCount;

    /** 并发版本；更新时携带数据库读出的旧值，不是业务修订号。 */
    @Version
    private Integer version;

    /** 记录创建时间，由数据库生成；普通插入和更新不写入此字段。 */
    @TableField(
            insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER
    )
    private LocalDateTime createdAt;

    /** 最近修改时间，由数据库维护，避免将查询出的旧时间写回。 */
    @TableField(
            insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER
    )
    private LocalDateTime updatedAt;
}
