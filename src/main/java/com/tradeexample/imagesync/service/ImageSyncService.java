package com.tradeexample.imagesync.service;

import com.tradeexample.common.exception.BusinessException;
import com.tradeexample.common.exception.ErrorCode;
import com.tradeexample.imagesync.client.ExternalImageClient;
import com.tradeexample.imagesync.mapper.ImageSyncMapper;
import com.tradeexample.imagesync.mapper.ImageSyncMapper.ProductSnapshot;
import com.tradeexample.imagesync.mapper.ImageSyncMapper.TaskInsert;
import com.tradeexample.imagesync.mapper.ImageSyncMapper.TaskItemInsert;
import com.tradeexample.imagesync.vo.ImageSyncResultVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Objects;

/** 同步一条产品图片，在短事务之间调用外部图库。 */
@Slf4j
@Service
public class ImageSyncService {

    private static final String SOURCE_CHANGED = "源图片版本已变化";
    private static final String ALREADY_SYNCED = "当前版本已有生效映射";

    private final ImageSyncMapper mapper;
    private final ExternalImageClient externalClient;
    private final TransactionTemplate transactions;

    public ImageSyncService(
            ImageSyncMapper mapper,
            ExternalImageClient externalClient,
            PlatformTransactionManager transactionManager) {

        this.mapper = mapper;
        this.externalClient = externalClient;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 同步指定产品的图片，返回这次的结果。 */
    public ImageSyncResultVO sync(long productId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("请在事务外调用图片同步");
        }

        Attempt attempt = transactions.execute(tx -> {
            ProductSnapshot source = requireProduct(productId);
            TaskInsert task = new TaskInsert(source.productType(), "RUNNING");
            requireOne(mapper.insertTask(task));

            TaskItemInsert item = new TaskItemInsert(task.getId(), productId, source.imageVersion());
            requireOne(mapper.insertItem(item));
            return new Attempt(task.getId(), item.getId(), productId, source.imageVersion(), true);
        });

        syncAttempt(attempt);
        return mapper.selectResult(attempt.itemId());
    }

    /** 处理一条已保存的明细，出错就把原因记在这条明细上。 */
    void syncBatchItem(ImageSyncResultVO item) {
        try {
            if (mapper.startItem(item.taskId(), item.itemId()) != 1) {
                return;
            }

            Attempt attempt = new Attempt(
                    item.taskId(), item.itemId(), item.productId(), item.imageVersion(), false);
            syncAttempt(attempt);
        } catch (RuntimeException exception) {
            log.error("图片同步明细异常，itemId={}", item.itemId(), exception);
            mapper.recordItemError(item.itemId(), "明细执行中断");
        }
    }

    /** 上传前先核对源图版本，映射提交后再清理旧图。 */
    private void syncAttempt(Attempt attempt) {
        ProductSnapshot source = requireProduct(attempt.productId());

        if (source.imageVersion() != attempt.imageVersion()) {
            transactions.executeWithoutResult(tx ->
                    finish(attempt, ResultStatus.SKIPPED, null, SOURCE_CHANGED, null));
            return;
        }

        if (source.hasCurrentMapping()) {
            transactions.executeWithoutResult(tx ->
                    finish(attempt, ResultStatus.SKIPPED, source.mappedExternalImageId(),
                            ALREADY_SYNCED, null));
            return;
        }

        String idempotencyKey =
                "tradeexample:image:" + attempt.productId() + ":v" + attempt.imageVersion();

        String uploadedId;
        try {
            uploadedId = externalClient.upload(source.imageUrl(), idempotencyKey);
        } catch (RestClientException exception) {
            boolean rejected = exception instanceof RestClientResponseException responseException
                    && responseException.getStatusCode().is4xxClientError();

            ResultStatus status = rejected ? ResultStatus.FAILED : ResultStatus.UNKNOWN;
            String message = rejected ? "外部图库拒绝上传" : "上传结果未确认";
            transactions.executeWithoutResult(tx -> finish(attempt, status, null, message, null));
            return;
        }

        String pendingDeleteId = transactions.execute(tx -> publish(attempt, uploadedId));
        if (pendingDeleteId != null) {
            cleanup(attempt.itemId(), pendingDeleteId);
        }
    }

    /** 锁住产品后再核对版本，将映射和本次结果一起提交。 */
    private String publish(Attempt attempt, String uploadedId) {
        long productId = attempt.productId();
        long expectedVersion = attempt.imageVersion();

        Long lockedVersion = mapper.lockImageVersion(productId);
        ProductSnapshot current = requireProduct(productId);
        String activeId = current.mappedExternalImageId();

        // 并发请求可能拿到同一个外部 ID，当前生效的图片不能删。
        String unusedUploadId = Objects.equals(activeId, uploadedId) ? null : uploadedId;

        if (!Objects.equals(lockedVersion, expectedVersion)) {
            return finish(attempt, ResultStatus.SKIPPED, null, SOURCE_CHANGED, unusedUploadId);
        }

        if (current.hasCurrentMapping()) {
            return finish(attempt, ResultStatus.SKIPPED, activeId, ALREADY_SYNCED, unusedUploadId);
        }

        if (current.mappedVersion() == null) {
            requireOne(mapper.insertMapping(productId, expectedVersion, uploadedId));
        } else {
            requireOne(mapper.replaceMapping(
                    productId, current.mappedVersion(), expectedVersion, uploadedId));
        }

        String oldImageId = Objects.equals(activeId, uploadedId) ? null : activeId;
        return finish(attempt, ResultStatus.SUCCESS, uploadedId, null, oldImageId);
    }

    /** 保存明细结果。单条同步在同一事务里结束自己的任务。 */
    private String finish(
            Attempt attempt,
            ResultStatus status,
            String externalImageId,
            String message,
            String pendingDeleteId) {

        requireOne(mapper.finishItem(
                attempt.itemId(), status.name(), externalImageId, message, pendingDeleteId));

        if (attempt.standaloneTask()) {
            boolean completed = status == ResultStatus.SUCCESS || status == ResultStatus.SKIPPED;
            requireOne(mapper.finishTask(
                    attempt.taskId(), completed ? "SUCCESS" : "FAILED", completed ? null : message));
        }

        return pendingDeleteId;
    }

    /** 删除成功后清掉待处理 ID，删除失败时把原因留在原明细。 */
    private void cleanup(long itemId, String externalImageId) {
        String error = null;
        try {
            externalClient.delete(externalImageId);
        } catch (RestClientException exception) {
            error = "外部图库未确认删除，待清理";
        }

        String remainingId = error == null ? null : externalImageId;
        requireOne(mapper.finishCleanup(itemId, externalImageId, remainingId, error));
    }

    private ProductSnapshot requireProduct(long productId) {
        ProductSnapshot product = mapper.selectProduct(productId);
        if (product == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "图片产品不存在");
        }
        return product;
    }

    private void requireOne(int affectedRows) {
        if (affectedRows != 1) {
            throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "图片同步记录已变化，请重新发起同步");
        }
    }

    private record Attempt(
            long taskId,
            long itemId,
            long productId,
            long imageVersion,
            boolean standaloneTask
    ) {
    }

    private enum ResultStatus {
        SUCCESS, SKIPPED, FAILED, UNKNOWN
    }
}