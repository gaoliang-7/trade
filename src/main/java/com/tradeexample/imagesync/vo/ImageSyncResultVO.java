package com.tradeexample.imagesync.vo;

/**
 * 一条图片明细的同步结果和清理结果。
 * imageVersion 对应源图片，executionNo 对应这条明细的第几次执行。
 * cleanupRetryCount 只统计人工清理，cleanupStatus 为空表示没有已记录的清理状态。
 */
public record ImageSyncResultVO(
        long taskId,
        long itemId,
        long productId,
        long imageVersion,
        String status,
        String externalImageId,
        String resultMessage,
        String pendingDeleteImageId,
        String cleanupError,
        int executionNo,
        int cleanupRetryCount,
        String cleanupStatus
) {
}