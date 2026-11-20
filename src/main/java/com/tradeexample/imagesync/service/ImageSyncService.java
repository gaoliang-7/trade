package com.tradeexample.imagesync.service;

import com.tradeexample.common.exception.BusinessException;
import com.tradeexample.common.exception.ErrorCode;
import com.tradeexample.imagesync.client.ExternalImageClient;
import com.tradeexample.imagesync.mapper.ImageSyncMapper;
import com.tradeexample.imagesync.mapper.ImageSyncMapper.ProductSnapshot;
import com.tradeexample.imagesync.mapper.ImageSyncMapper.TaskInsert;
import com.tradeexample.imagesync.mapper.ImageSyncMapper.TaskItemInsert;
import com.tradeexample.imagesync.vo.ImageSyncResultVO;
import com.tradeexample.imagesync.vo.ImageSyncTaskVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Objects;

/** 处理单条图片的上传、回执确认、映射发布和旧图清理。 */
@Slf4j
@Service
public class ImageSyncService {

    private static final int INITIAL_EXECUTION_NO = 1;
    private static final int MAX_ITEM_RETRIES = 2;
    private static final int MAX_CLEANUP_RETRIES = 2;
    private static final String SOURCE_CHANGED = "源图片版本已变化";
    private static final String ALREADY_SYNCED = "当前版本已有生效映射";

    private final ImageSyncMapper mapper;
    private final ExternalImageClient externalClient;
    private final TransactionTemplate transactions;

    /** 准备取得执行权、发布映射和写回结果所需的短事务。 */
    public ImageSyncService(
            ImageSyncMapper mapper,
            ExternalImageClient externalClient,
            PlatformTransactionManager transactionManager) {

        this.mapper = mapper;
        this.externalClient = externalClient;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 保存单条任务和明细，提交后再执行图片同步。 */
    public ImageSyncResultVO sync(long productId) {
        requireOutsideTransaction();

        Attempt attempt = transactions.execute(tx -> {
            ProductSnapshot source = requireProduct(productId);
            TaskInsert task = new TaskInsert(source.productType(), "RUNNING");
            requireOne(mapper.insertTask(task));

            TaskItemInsert item = new TaskItemInsert(task.getId(), productId, source.imageVersion());
            requireOne(mapper.insertItem(item));
            return new Attempt(task.getId(), item.getId(), productId, source.imageVersion(),
                    INITIAL_EXECUTION_NO, true, false);
        });

        runAttempt(attempt);
        return mapper.selectResult(attempt.itemId());
    }

    /** 处理分页里的明细，任务已经关闭这条明细时通知分页线程停下。 */
    boolean syncBatchItem(ImageSyncResultVO item) {
        if (mapper.startItem(item.taskId(), item.itemId(), item.executionNo()) != 1) {
            return false;
        }

        runAttempt(new Attempt(item.taskId(), item.itemId(), item.productId(), item.imageVersion(),
                item.executionNo(), false, false));
        return true;
    }

    /** 人工重试一条失败或未知明细，未知结果只查询原上传回执。 */
    public ImageSyncResultVO retryItem(long taskId, long itemId, int expectedExecutionNo) {
        requireOutsideTransaction();

        Attempt attempt = transactions.execute(tx -> {
            ImageSyncResultVO item = requireItem(taskId, itemId);
            boolean confirmOnly = ResultStatus.UNKNOWN.name().equals(item.status());

            if (item.executionNo() != expectedExecutionNo
                    || (!confirmOnly && !ResultStatus.FAILED.name().equals(item.status()))) {
                throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "明细状态或执行序号已变化");
            }
            if (item.executionNo() - INITIAL_EXECUTION_NO >= MAX_ITEM_RETRIES) {
                throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "明细重试次数已用完");
            }

            // 任务和明细一起取得执行权，任一条件不满足就回滚。
            requireOne(mapper.reopenForRetry(taskId));
            requireOne(mapper.claimRetry(taskId, itemId, expectedExecutionNo, item.status()));
            return new Attempt(taskId, itemId, item.productId(), item.imageVersion(),
                    expectedExecutionNo + 1, true, confirmOnly);
        });

        runAttempt(attempt);
        return mapper.selectResult(itemId);
    }

    /** 人工清理一张待删图片，先提交计数再调用幂等删除。 */
    public ImageSyncResultVO retryCleanup(long taskId, long itemId, int expectedRetryCount) {
        requireOutsideTransaction();

        ImageSyncResultVO item = requireItem(taskId, itemId);
        String deleteId = item.pendingDeleteImageId();
        if (deleteId == null) {
            return item;
        }
        if (item.cleanupRetryCount() != expectedRetryCount) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "清理重试次数已变化");
        }
        if (item.cleanupRetryCount() >= MAX_CLEANUP_RETRIES) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "清理重试次数已用完");
        }

        requireOne(mapper.claimCleanupRetry(itemId, deleteId, expectedRetryCount));
        cleanup(itemId, deleteId, expectedRetryCount + 1);
        return mapper.selectResult(itemId);
    }

    /** 执行一次上传或回执确认，映射提交后再处理待删除图片。 */
    private void runAttempt(Attempt attempt) {
        String key = "tradeexample:image:" + attempt.productId() + ":v" + attempt.imageVersion();
        boolean uploadPossible = attempt.confirmOnly();

        ResultStatus status = null;
        String message = null;
        String resultId = null;
        String cleanupId = null;
        try {
            String externalId = null;
            if (attempt.confirmOnly()) {
                // 查不到回执时，原上传可能仍在执行，也可能已经失去回执。
                externalId = externalClient.findUploadedImage(key);
                if (externalId == null) {
                    status = ResultStatus.UNKNOWN;
                    message = "暂未查到原上传回执";
                }
            } else {
                ProductSnapshot source = requireProduct(attempt.productId());
                if (source.imageVersion() != attempt.imageVersion()) {
                    status = ResultStatus.SKIPPED;
                    message = SOURCE_CHANGED;
                } else if (source.hasCurrentMapping()) {
                    status = ResultStatus.SKIPPED;
                    message = ALREADY_SYNCED;
                    resultId = source.mappedExternalImageId();
                } else {
                    uploadPossible = true;
                    externalId = externalClient.upload(source.imageUrl(), key);
                }
            }

            if (externalId != null) {
                String uploadedId = externalId;
                cleanupId = transactions.execute(tx -> publish(attempt, uploadedId));
            }
        } catch (RestClientException exception) {
            // 模拟图库的 400 和 409 明确拒绝上传，其余未确认响应保留为未知。
            boolean rejected = !attempt.confirmOnly()
                    && exception instanceof RestClientResponseException response
                    && (response.getStatusCode().value() == 400
                        || response.getStatusCode().value() == 409);
            status = rejected ? ResultStatus.FAILED : ResultStatus.UNKNOWN;
            message = rejected ? "外部图库拒绝上传" : "外部图库结果未确认";
        } catch (RuntimeException exception) {
            log.error("图片同步明细执行异常，itemId={}", attempt.itemId(), exception);
            status = uploadPossible ? ResultStatus.UNKNOWN : ResultStatus.FAILED;
            message = uploadPossible ? "本地执行中断，上传结果待确认" : "上传前执行中断";
        }

        if (status != null) {
            // 未发布映射时，只写同步结果以及需要同步关闭的任务。
            ResultStatus finalStatus = status;
            String finalMessage = message;
            String finalResultId = resultId;
            transactions.executeWithoutResult(tx ->
                    finish(attempt, finalStatus, finalResultId, finalMessage, null));
        }
        if (cleanupId != null) {
            cleanup(attempt.itemId(), cleanupId, 0);
        }
    }

    /** 锁住产品并复查版本，确认明细仍能写入后才修改生效映射。 */
    private String publish(Attempt attempt, String uploadedId) {
        long productId = attempt.productId();
        long expectedVersion = attempt.imageVersion();

        Long lockedVersion = mapper.lockImageVersion(productId);
        ProductSnapshot current = requireProduct(productId);
        String activeId = current.mappedExternalImageId();

        // 同一幂等键可能返回当前生效的图片 ID，不能把它记成待清理。
        String unusedId = Objects.equals(activeId, uploadedId) ? null : uploadedId;

        if (!Objects.equals(lockedVersion, expectedVersion)) {
            return finish(attempt, ResultStatus.SKIPPED, null, SOURCE_CHANGED, unusedId) ? unusedId : null;
        }
        if (current.hasCurrentMapping()) {
            return finish(attempt, ResultStatus.SKIPPED, activeId, ALREADY_SYNCED, unusedId) ? unusedId : null;
        }

        String oldId = Objects.equals(activeId, uploadedId) ? null : activeId;
        // 超时或重试已经接管时，旧执行到这里停下，映射也不会更新。
        if (!finish(attempt, ResultStatus.SUCCESS, uploadedId, null, oldId)) {
            return null;
        }
        if (current.mappedVersion() == null) {
            requireOne(mapper.insertMapping(productId, expectedVersion, uploadedId));
        } else {
            requireOne(mapper.replaceMapping(
                    productId, current.mappedVersion(), expectedVersion, uploadedId));
        }
        return oldId;
    }

    /** 保存当前序号的结果。单条同步和人工重试也在同一事务里汇总任务。 */
    private boolean finish(
            Attempt attempt,
            ResultStatus status,
            String externalImageId,
            String message,
            String pendingDeleteId) {

        int updated = mapper.finishItem(attempt.itemId(), attempt.executionNo(),
                status.name(), externalImageId, message, pendingDeleteId);
        if (updated == 0) {
            return false;
        }
        if (attempt.closesTask()) {
            boolean unresolved = status == ResultStatus.FAILED || status == ResultStatus.UNKNOWN;
            finishTask(attempt.taskId(), unresolved ? message : null);
        }
        return true;
    }

    /** 按明细当前结果结束任务，调用方在同一事务里写入明细。 */
    void finishTask(long taskId, String stopReason) {
        ImageSyncTaskVO progress = mapper.selectTaskProgress(taskId);
        long completed = progress.successCount() + progress.skippedCount();

        String status = "SUCCESS";
        String message = null;
        if (completed < progress.totalCount()) {
            status = completed == 0 ? "FAILED" : "PARTIAL_FAILED";
            message = stopReason == null ? "有失败、结果未知或未处理的明细" : stopReason;
        }
        requireOne(mapper.finishTask(taskId, status, message));
    }

    /** 调用删除接口，只允许当前清理次数保存结果。 */
    private void cleanup(long itemId, String externalImageId, int retryCount) {
        String status = "SUCCESS";
        String error = null;
        try {
            externalClient.delete(externalImageId);
        } catch (RestClientException exception) {
            boolean rejected = exception instanceof RestClientResponseException response
                    && response.getStatusCode().value() == 400;
            status = rejected ? "FAILED" : "UNKNOWN";
            error = rejected ? "外部图库拒绝删除" : "外部图库未确认删除";
        }

        int updated = mapper.finishCleanup(itemId, externalImageId, retryCount, status, error);
        if (updated == 0) {
            log.debug("待删图片或清理次数已变化，itemId={}", itemId);
        }
    }

    /** 读取产品和当前映射，产品不存在时返回业务错误。 */
    private ProductSnapshot requireProduct(long productId) {
        ProductSnapshot product = mapper.selectProduct(productId);
        if (product == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "图片产品不存在");
        }
        return product;
    }

    /** 读取明细并确认它属于请求中的任务。 */
    private ImageSyncResultVO requireItem(long taskId, long itemId) {
        ImageSyncResultVO item = mapper.selectResult(itemId);
        if (item == null || item.taskId() != taskId) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "图片同步明细不存在");
        }
        return item;
    }

    /** 条件写入失去执行权时抛出冲突，事务内已完成的修改一起回滚。 */
    private void requireOne(int affectedRows) {
        if (affectedRows != 1) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "图片同步记录已变化，请先查询");
        }
    }

    /** 保证外部 HTTP 调用前，取得执行权的数据库写入已经提交。 */
    private void requireOutsideTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("请在事务外调用图片同步");
        }
    }

    /** 记录本次执行的源图版本、执行序号和处理方式。 */
    private record Attempt(
            long taskId,
            long itemId,
            long productId,
            long imageVersion,
            int executionNo,
            boolean closesTask,
            boolean confirmOnly
    ) {
    }

    /** 区分明细已经确认的结果和仍需查询回执的结果。 */
    private enum ResultStatus {
        SUCCESS, SKIPPED, FAILED, UNKNOWN
    }
}
