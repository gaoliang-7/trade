package com.tradeexample.wms.vo;

import com.tradeexample.wms.entity.OutboundOrder;
import com.tradeexample.wms.entity.OutboundOrderItem;

import java.util.List;

/** 出库单主表及当前修订的分配明细，不包含历史修订。 */
public record OutboundOrderDetailVO(
        OutboundOrder order,
        List<OutboundOrderItem> items
) {
}