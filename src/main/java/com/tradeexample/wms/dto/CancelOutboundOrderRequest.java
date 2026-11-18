package com.tradeexample.wms.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** 作废时携带用户确认过的修订号和主表版本号。 */
public record CancelOutboundOrderRequest(

        @NotNull(message = "修订号不能为空")
        @Positive(message = "修订号必须大于0")
        Integer revision,

        @NotNull(message = "版本号不能为空")
        @PositiveOrZero(message = "版本号不能小于0")
        Integer version
) {
}