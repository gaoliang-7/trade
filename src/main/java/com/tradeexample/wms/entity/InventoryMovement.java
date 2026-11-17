package com.tradeexample.wms.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 库存流水
 */
@Getter
@Setter
@TableName("wms_inventory_movement")
public class InventoryMovement {

    /** 数据库自增主键；新增时不手动指定。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属出库单ID，关联wms_outbound_order。 */
    private Long outboundOrderId;

    /** 产生本次流水的出库修订号，后续重新生成不会改写此值。 */
    private Integer revision;

    /** 产生本次变动的出库明细ID，关联wms_outbound_order_item。 */
    private Long outboundOrderItemId;

    /** 原库存批次ID，关联wms_inventory_detail；扣减和恢复都定位这一批次。 */
    private Long inventoryDetailId;

    /** DEDUCT表示扣减，RESTORE表示恢复。 */
    private String movementType;

    /** 在仓箱数变化量：扣减为负数，恢复为正数。 */
    private Integer boxCountChange;

    /** 本次变动发生前的实际在仓箱数。 */
    private Integer beforeStockBoxCount;

    /** 变动后的在仓箱数，等于变动前箱数加变化量。 */
    private Integer afterStockBoxCount;

    /** 恢复时关联原扣减流水ID；扣减时为空。同一扣减流水最多恢复一次。 */
    private Long originalMovementId;

    /** 记录创建时间，由数据库生成；普通插入和更新不写入此字段。 */
    @TableField(
            insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER
    )
    private LocalDateTime createdAt;
}
