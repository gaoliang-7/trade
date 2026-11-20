package com.tradeexample.imagesync.vo;

/** 一类产品的同步任务，以及各状态的明细数量。 */
public record ImageSyncTaskVO(
        long taskId,
        String productType,
        String status,
        String resultMessage,
        long totalCount,
        long pendingCount,
        long runningCount,
        long successCount,
        long skippedCount,
        long failedCount,
        long unknownCount
) {
}