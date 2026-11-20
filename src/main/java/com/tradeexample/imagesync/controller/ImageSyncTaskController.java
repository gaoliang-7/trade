package com.tradeexample.imagesync.controller;

import com.tradeexample.common.api.ApiResponse;
import com.tradeexample.imagesync.service.ImageSyncBatchService;
import com.tradeexample.imagesync.service.ImageSyncBatchService.CreatedTask;
import com.tradeexample.imagesync.vo.ImageSyncResultVO;
import com.tradeexample.imagesync.vo.ImageSyncTaskVO;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 提供批量图片同步的任务创建、进度和明细查询接口。 */
@RestController
@RequestMapping("/api/v1/image-sync-tasks")
@RequiredArgsConstructor
public class ImageSyncTaskController {

    private final ImageSyncBatchService batchService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<List<CreatedTask>> create() {
        return ApiResponse.success(batchService.create());
    }

    @GetMapping("/{taskId}")
    public ApiResponse<ImageSyncTaskVO> progress(
            @PathVariable("taskId") @Positive(message = "任务ID必须大于0") long taskId) {

        return ApiResponse.success(batchService.getProgress(taskId));
    }

    @GetMapping("/{taskId}/items")
    public ApiResponse<List<ImageSyncResultVO>> items(
            @PathVariable("taskId") @Positive(message = "任务ID必须大于0") long taskId,
            @RequestParam(name = "afterProductId", defaultValue = "0")
            @PositiveOrZero(message = "产品ID游标必须大于等于0") long afterProductId,
            @RequestParam(name = "limit", defaultValue = "100")
            @Min(value = 1, message = "每页至少查询1条")
            @Max(value = ImageSyncBatchService.MAX_PAGE_SIZE, message = "每页最多查询500条")
            int limit) {

        return ApiResponse.success(batchService.listItems(taskId, afterProductId, limit));
    }
}