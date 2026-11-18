package com.tradeexample.wms.controller;

import com.tradeexample.common.api.ApiResponse;
import com.tradeexample.wms.dto.CancelOutboundOrderRequest;
import com.tradeexample.wms.dto.CreateOutboundOrderRequest;
import com.tradeexample.wms.dto.RegenerateOutboundOrderRequest;
import com.tradeexample.wms.dto.SubmitOutboundOrderRequest;
import com.tradeexample.wms.service.OutboundOrderService;
import com.tradeexample.wms.vo.OutboundOrderDetailVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 出库草稿创建和当前修订查询接口。 */
@RestController
@RequestMapping("/api/v1/outbound-orders")
@RequiredArgsConstructor
public class OutboundOrderController {

    private final OutboundOrderService outboundOrderService;

    @PostMapping
    public ApiResponse<OutboundOrderDetailVO> createDraft(
            @Valid @RequestBody CreateOutboundOrderRequest request) {

        return ApiResponse.success(
                outboundOrderService.createDraft(request)
        );
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<OutboundOrderDetailVO> submit(
            @PathVariable("id")
            @Positive(message = "出库单ID必须大于0")
            Long id,
            @Valid @RequestBody SubmitOutboundOrderRequest request) {

        return ApiResponse.success(outboundOrderService.submit(id, request));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<OutboundOrderDetailVO> cancel(
            @PathVariable("id")
            @Positive(message = "出库单ID必须大于0")
            Long id,
            @Valid @RequestBody CancelOutboundOrderRequest request) {

        return ApiResponse.success(outboundOrderService.cancel(id, request));
    }

    @PostMapping("/{id}/regenerate")
    public ApiResponse<OutboundOrderDetailVO> regenerate(
            @PathVariable("id")
            @Positive(message = "出库单ID必须大于0")
            Long id,
            @Valid @RequestBody RegenerateOutboundOrderRequest request) {

        return ApiResponse.success(outboundOrderService.regenerate(id, request));
    }

    @GetMapping("/{id}")
    public ApiResponse<OutboundOrderDetailVO> getDetail(
            @PathVariable("id")
            @Positive(message = "出库单ID必须大于0")
            Long id) {

        return ApiResponse.success(
                outboundOrderService.getDetail(id)
        );
    }
}