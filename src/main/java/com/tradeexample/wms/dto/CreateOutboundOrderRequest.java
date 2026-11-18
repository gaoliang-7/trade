package com.tradeexample.wms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

/** 创建一个仓库的出库草稿，一次可以提交多项采购单和产品需求。 */
public record CreateOutboundOrderRequest(

        @NotBlank(message = "仓库编码不能为空")
        @Size(max = 32, message = "仓库编码不能超过32个字符")
        String warehouseCode,

        @NotNull(message = "出库日期不能为空")
        LocalDate outboundDate,

        @NotEmpty(message = "出库需求不能为空")
        List<@NotNull(message = "需求项不能为空") @Valid Demand> demands
) {

    /** 同一请求内，采购单号和产品编号相同的需求只能出现一次。 */
    public record Demand(

            @NotBlank(message = "采购单号不能为空")
            @Size(max = 64, message = "采购单号不能超过64个字符")
            String purchaseOrderNo,

            @NotBlank(message = "产品编号不能为空")
            @Size(max = 64, message = "产品编号不能超过64个字符")
            String productNo,

            @NotNull(message = "需求箱数不能为空")
            @Positive(message = "需求箱数必须大于0")
            Integer boxCount
    ) {
    }
}