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
 * 出库分配明细
 */
@Getter
@Setter
@TableName("wms_outbound_order_item")
public class OutboundOrderItem {

    /** 数据库自增主键；新增时不手动指定。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属出库单ID，关联wms_outbound_order。 */
    private Long outboundOrderId;

    /** 本行所属的业务修订号；查询当前明细时与出库单的当前修订号匹配。 */
    private Integer revision;

    /** 当前修订内的行号，从1开始；与出库单ID、修订号一起唯一。 */
    private Integer lineNo;

    /** 原库存批次ID，关联wms_inventory_detail；扣减和恢复都定位这一批次。 */
    private Long inventoryDetailId;

    /** 生成明细时的采购单号快照。 */
    private String purchaseOrderNo;

    /** 生成明细时的产品编号快照。 */
    private String productNo;

    /** 本行从该批次分配的箱数，必须大于0。 */
    private Integer outboundBoxCount;

    /** 生成明细时的余量快照，不代表提交时的实时库存。 */
    private Integer stockSnapshotBoxCount;

    /** 记录创建时间，由数据库生成；普通插入和更新不写入此字段。 */
    @TableField(
            insertStrategy = FieldStrategy.NEVER,
            updateStrategy = FieldStrategy.NEVER
    )
    private LocalDateTime createdAt;
}
