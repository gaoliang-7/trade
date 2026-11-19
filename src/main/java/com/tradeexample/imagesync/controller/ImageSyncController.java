package com.tradeexample.imagesync.controller;

import com.tradeexample.common.api.ApiResponse;
import com.tradeexample.imagesync.service.ImageSyncService;
import com.tradeexample.imagesync.vo.ImageSyncResultVO;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 接收指定产品的单条图片同步请求。 */
@RestController
@RequestMapping("/api/v1/image-products")
@RequiredArgsConstructor
public class ImageSyncController {

    private final ImageSyncService imageSyncService;

    @PostMapping("/{productId}/sync")
    public ApiResponse<ImageSyncResultVO> sync(
            @PathVariable("productId")
            @Positive(message = "产品ID必须大于0")
            long productId) {

        return ApiResponse.success(imageSyncService.sync(productId));
    }
}