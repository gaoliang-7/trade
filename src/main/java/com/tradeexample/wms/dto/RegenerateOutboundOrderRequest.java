package com.tradeexample.wms.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

/** 带上当前修订、主表版本和新的出库需求，生成下一版草稿。 */
public record RegenerateOutboundOrderRequest(

        @NotNull(message = "修订号不能为空")
        @Positive(message = "修订号必须大于0")
        Integer revision,

        @NotNull(message = "版本号不能为空")
        @PositiveOrZero(message = "版本号不能小于0")
        Integer version,

        @NotEmpty(message = "出库需求不能为空")
        List<@NotNull(message = "需求项不能为空") @Valid CreateOutboundOrderRequest.Demand> demands
) {
}