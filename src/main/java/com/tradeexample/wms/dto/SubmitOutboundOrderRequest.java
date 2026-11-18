package com.tradeexample.wms.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/** 提交草稿时携带页面上看到的修订号和版本号。 */
public record SubmitOutboundOrderRequest(

        @NotNull(message = "修订号不能为空")
        @Positive(message = "修订号必须大于0")
        Integer revision,

        @NotNull(message = "版本号不能为空")
        @PositiveOrZero(message = "版本号不能小于0")
        Integer version
) {
}