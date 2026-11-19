package com.tradeexample.imagesync.vo;

/** 一次单条同步的明细结果和旧图清理情况。 */
public record ImageSyncResultVO(
        long taskId,
        long itemId,
        long productId,
        long imageVersion,
        String status,
        String externalImageId,
        String resultMessage,
        String pendingDeleteImageId,
        String cleanupError
) {
}