package com.tradeexample.imagesync.service;

import com.tradeexample.common.exception.BusinessException;
import com.tradeexample.common.exception.ErrorCode;
import com.tradeexample.imagesync.mapper.ImageSyncMapper;
import com.tradeexample.imagesync.mapper.ImageSyncMapper.TaskInsert;
import com.tradeexample.imagesync.vo.ImageSyncResultVO;
import com.tradeexample.imagesync.vo.ImageSyncTaskVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/** 创建、执行并查询三类产品的图片同步任务。每个任务按产品 ID 分页执行。 */
@Slf4j
@Service
public class ImageSyncBatchService {

    public static final int MAX_PAGE_SIZE = 500;

    private final ImageSyncMapper mapper;
    private final ImageSyncService imageSyncService;
    private final ThreadPoolTaskExecutor executor;
    private final TransactionTemplate transactions;

    public ImageSyncBatchService(
            ImageSyncMapper mapper,
            ImageSyncService imageSyncService,
            @Qualifier("imageSyncExecutor") ThreadPoolTaskExecutor executor,
            PlatformTransactionManager transactionManager) {

        this.mapper = mapper;
        this.imageSyncService = imageSyncService;
        this.executor = executor;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** 先把任务和明细存好，再交给线程池。 */
    public List<CreatedTask> create() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("请在事务外创建图片同步任务");
        }

        List<CreatedTask> tasks = transactions.execute(tx -> {
            TaskInsert own = new TaskInsert("OWN", "PENDING");
            TaskInsert customer = new TaskInsert("CUSTOMER", "PENDING");
            TaskInsert accessory = new TaskInsert("ACCESSORY", "PENDING");

            List<CreatedTask> created = new ArrayList<>(3);
            for (TaskInsert task : List.of(own, customer, accessory)) {
                if (mapper.insertTask(task) != 1) {
                    throw new IllegalStateException("图片同步任务写入失败");
                }
                created.add(new CreatedTask(task.getId(), task.getProductType()));
            }

            mapper.insertBatchItems(own.getId(), customer.getId(), accessory.getId());
            return created;
        });

        for (CreatedTask task : tasks) {
            try {
                executor.execute(() -> runTask(task.taskId()));
            } catch (TaskRejectedException exception) {
                if (mapper.failOpenTask(task.taskId(), "线程池拒绝任务，任务未执行") != 1) {
                    throw new IllegalStateException("任务拒绝结果写入失败", exception);
                }
            }
        }

        return tasks;
    }

    public ImageSyncTaskVO getProgress(long taskId) {
        ImageSyncTaskVO progress = mapper.selectTaskProgress(taskId);
        if (progress == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "图片同步任务不存在");
        }
        return progress;
    }

    public List<ImageSyncResultVO> listItems(long taskId, long afterProductId, int limit) {
        if (!mapper.taskExists(taskId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "图片同步任务不存在");
        }
        return mapper.selectTaskItems(taskId, afterProductId, limit);
    }

    /** 一页页处理图片，最后根据明细结果更新任务状态。 */
    private void runTask(long taskId) {
        try {
            if (mapper.startTask(taskId) != 1) {
                return;
            }

            long afterProductId = 0;
            while (true) {
                List<ImageSyncResultVO> page =
                        mapper.selectTaskItems(taskId, afterProductId, MAX_PAGE_SIZE);

                for (ImageSyncResultVO item : page) {
                    imageSyncService.syncBatchItem(item);
                }

                if (page.size() < MAX_PAGE_SIZE) {
                    break;
                }
                afterProductId = page.get(page.size() - 1).productId();
            }

            ImageSyncTaskVO progress = getProgress(taskId);
            if (progress.pendingCount() > 0 || progress.runningCount() > 0) {
                throw new IllegalStateException("还有明细未处理完");
            }

            long errorCount = progress.failedCount() + progress.unknownCount();
            String status = "SUCCESS";
            String message = null;
            if (errorCount > 0) {
                status = errorCount == progress.totalCount() ? "FAILED" : "PARTIAL_FAILED";
                message = "有失败或结果未知的明细";
            }

            if (mapper.finishTask(taskId, status, message) != 1) {
                throw new IllegalStateException("任务结果写入失败");
            }
        } catch (RuntimeException exception) {
            log.error("图片同步任务中断，taskId={}", taskId, exception);
            mapper.failOpenTask(taskId, "任务执行中断");
        }
    }

    public record CreatedTask(long taskId, String productType) {
    }
}