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
 * 出库单主表
 */
@Getter
@Setter
@TableName("wms_outbound_order")
public class OutboundOrder {

    /** 数据库自增主键；新增时不手动指定。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 出库单业务编号，由数据库唯一约束防止重复。 */
    private String orderNo;

    /** 本次出库所属的仓库编码。 */
    private String warehouseCode;

    /** 本单的业务出库日期。 */
    private LocalDate outboundDate;

    /** DRAFT为草稿，SUBMITTED为已提交，CANCELED为已作废。 */
    private String status;

    /** 业务修订号，从1开始；重新生成时增加，历史明细和流水仍保留原修订号。 */
    private Integer revision;

    /** 当前修订的合计出库箱数；使用Long承接多批次汇总。 */
    private Long totalBoxCount;

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
