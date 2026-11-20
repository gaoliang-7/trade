package com.tradeexample.imagesync.controller;

import com.tradeexample.common.api.ApiResponse;
import com.tradeexample.imagesync.service.ImageSyncBatchService;
import com.tradeexample.imagesync.service.ImageSyncBatchService.CreatedTask;
import com.tradeexample.imagesync.service.ImageSyncService;
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

/** 提供图片同步任务的创建、查询、明细重试和旧图清理接口。 */
@RestController
@RequestMapping("/api/v1/image-sync-tasks")
@RequiredArgsConstructor
public class ImageSyncTaskController {

    private final ImageSyncBatchService batchService;
    private final ImageSyncService imageSyncService;

    /** 提交三类产品的批量同步任务。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<List<CreatedTask>> create() {
        return ApiResponse.success(batchService.create());
    }

    /** 查看一个任务的累计同步结果。 */
    @GetMapping("/{taskId}")
    public ApiResponse<ImageSyncTaskVO> progress(
            @PathVariable("taskId") @Positive(message = "任务ID必须大于0") long taskId) {

        return ApiResponse.success(batchService.getProgress(taskId));
    }

    /** 按产品 ID 分页查看固定清单里的明细结果。 */
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

    /** 重试当前序号的失败或未知明细，返回最新结果。 */
    @PostMapping("/{taskId}/items/{itemId}/retry")
    public ApiResponse<ImageSyncResultVO> retryItem(
            @PathVariable("taskId") @Positive long taskId,
            @PathVariable("itemId") @Positive long itemId,
            @RequestParam("expectedExecutionNo") @Positive int expectedExecutionNo) {

        return ApiResponse.success(imageSyncService.retryItem(taskId, itemId, expectedExecutionNo));
    }

    /** 重试删除明细上的待清理图片，返回最新清理状态。 */
    @PostMapping("/{taskId}/items/{itemId}/cleanup-retry")
    public ApiResponse<ImageSyncResultVO> retryCleanup(
            @PathVariable("taskId") @Positive long taskId,
            @PathVariable("itemId") @Positive long itemId,
            @RequestParam("expectedRetryCount") @PositiveOrZero int expectedRetryCount) {

        return ApiResponse.success(imageSyncService.retryCleanup(taskId, itemId, expectedRetryCount));
    }
}
