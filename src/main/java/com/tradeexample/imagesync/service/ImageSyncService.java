package com.tradeexample.imagesync.service;

import com.tradeexample.common.exception.BusinessException;
import com.tradeexample.common.exception.ErrorCode;
import com.tradeexample.imagesync.client.ExternalImageClient;
import com.tradeexample.imagesync.mapper.ImageSyncMapper;
import com.tradeexample.imagesync.mapper.ImageSyncMapper.ProductSnapshot;
import com.tradeexample.imagesync.mapper.ImageSyncMapper.TaskInsert;
import com.tradeexample.imagesync.mapper.ImageSyncMapper.TaskItemInsert;
import com.tradeexample.imagesync.vo.ImageSyncResultVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Objects;

/** 同步一条产品图片，在短事务之间调用外部图库。 */
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

    /** 保存本次尝试，同步图片，然后处理明细里已记录的待删除图片。 */
    public ImageSyncResultVO sync(long productId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("请在数据库事务提交后调用图片同步");
        }

        Attempt attempt = transactions.execute(status -> createAttempt(productId));
        String pendingDeleteId = uploadAndPublish(attempt);
        if (pendingDeleteId != null) {
            cleanup(attempt.itemId(), pendingDeleteId);
        }
        return mapper.selectResult(attempt.itemId());
    }

    private Attempt createAttempt(long productId) {
        ProductSnapshot source = requireProduct(productId);

        TaskInsert task = new TaskInsert(source.productType());
        requireOne(mapper.insertTask(task));

        TaskItemInsert item = new TaskItemInsert(task.getId(), productId, source.imageVersion());
        requireOne(mapper.insertItem(item));

        return new Attempt(task.getId(), item.getId(), source);
    }

    /** 发出上传请求前重读源图版本和映射，过期或已同步时直接保存跳过结果。 */
    private String uploadAndPublish(Attempt attempt) {
        ProductSnapshot source = attempt.source();
        ProductSnapshot beforeUpload = requireProduct(source.productId());

        if (beforeUpload.imageVersion() != source.imageVersion()) {
            return transactions.execute(tx ->
                    finish(attempt, ResultStatus.SKIPPED, null, SOURCE_CHANGED, null));
        }

        if (beforeUpload.hasCurrentMapping()) {
            return transactions.execute(tx -> finish(
                    attempt, ResultStatus.SKIPPED, beforeUpload.mappedExternalImageId(),
                    ALREADY_SYNCED, null));
        }

        String idempotencyKey = "tradeexample:image:" + source.productId() + ":v" + source.imageVersion();
        String uploadedId;
        try {
            uploadedId = externalClient.upload(source.imageUrl(), idempotencyKey);
        } catch (RestClientException exception) {
            boolean rejected = exception instanceof RestClientResponseException responseException
                    && responseException.getStatusCode().is4xxClientError();
            ResultStatus status = rejected ? ResultStatus.FAILED : ResultStatus.UNKNOWN;
            String message = rejected ? "外部图库拒绝上传请求" : "未能确认外部图库的上传结果";

            return transactions.execute(tx -> finish(attempt, status, null, message, null));
        }

        return transactions.execute(tx -> publish(attempt, uploadedId));
    }

    /** 锁住产品后再核对版本，将映射和本次结果一起提交。 */
    private String publish(Attempt attempt, String uploadedId) {
        long productId = attempt.source().productId();
        long expectedVersion = attempt.source().imageVersion();

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

    /** 一起结束明细和任务，返回提交后要删除的图片 ID。 */
    private String finish(
            Attempt attempt,
            ResultStatus status,
            String externalImageId,
            String message,
            String pendingDeleteId) {

        requireOne(mapper.finishItem(
                attempt.itemId(), status.name(), externalImageId, message, pendingDeleteId));

        boolean completed = status == ResultStatus.SUCCESS || status == ResultStatus.SKIPPED;
        requireOne(mapper.finishTask(
                attempt.taskId(), completed ? "SUCCESS" : "FAILED", completed ? null : message));

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

    private record Attempt(long taskId, long itemId, ProductSnapshot source) {
    }

    private enum ResultStatus {
        SUCCESS, SKIPPED, FAILED, UNKNOWN
    }
}