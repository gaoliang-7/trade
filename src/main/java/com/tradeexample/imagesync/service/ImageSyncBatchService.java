package com.tradeexample.imagesync.service;

import com.tradeexample.common.exception.BusinessException;
import com.tradeexample.common.exception.ErrorCode;
import com.tradeexample.imagesync.mapper.ImageSyncMapper;
import com.tradeexample.imagesync.mapper.ImageSyncMapper.TaskInsert;
import com.tradeexample.imagesync.vo.ImageSyncResultVO;
import com.tradeexample.imagesync.vo.ImageSyncTaskVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 创建、执行并查询三类产品的图片同步任务。每个任务按产品 ID 分页执行。 */
@Slf4j
@Service
public class ImageSyncBatchService {

    public static final int MAX_PAGE_SIZE = 500;

    private final ImageSyncMapper mapper;
    private final ImageSyncService imageSyncService;
    private final ThreadPoolTaskExecutor executor;
    private final ThreadPoolTaskExecutor pageExecutor;
    private final long batchWaitNanos;
    private final long taskTimeoutNanos;
    private final TransactionTemplate transactions;

    /** 注入协调和分页线程池，并读取一页和整项任务的时间上限。 */
    public ImageSyncBatchService(
            ImageSyncMapper mapper,
            ImageSyncService imageSyncService,
            @Qualifier("imageSyncExecutor") ThreadPoolTaskExecutor executor,
            @Qualifier("imageSyncPageExecutor") ThreadPoolTaskExecutor pageExecutor,
            @Value("${tradeexample.image-sync.batch-wait-timeout-ms}") int batchWaitTimeoutMs,
            @Value("${tradeexample.image-sync.task-timeout-ms}") int taskTimeoutMs,
            PlatformTransactionManager transactionManager) {

        if (batchWaitTimeoutMs <= 0 || taskTimeoutMs <= 0) {
            throw new IllegalArgumentException("图片批次和任务超时必须大于0");
        }

        this.mapper = mapper;
        this.imageSyncService = imageSyncService;
        this.executor = executor;
        this.pageExecutor = pageExecutor;
        this.batchWaitNanos = TimeUnit.MILLISECONDS.toNanos(batchWaitTimeoutMs);
        this.taskTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(taskTimeoutMs);
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

    /** 读取一个任务的状态和各类结果的数量。 */
    public ImageSyncTaskVO getProgress(long taskId) {
        ImageSyncTaskVO progress = mapper.selectTaskProgress(taskId);
        if (progress == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "图片同步任务不存在");
        }
        return progress;
    }

    /** 按产品 ID 游标读取任务明细和最近的清理结果。 */
    public List<ImageSyncResultVO> listItems(long taskId, long afterProductId, int limit) {
        if (!mapper.taskExists(taskId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "图片同步任务不存在");
        }
        return mapper.selectTaskItems(taskId, afterProductId, limit);
    }

    /** 按游标提交每页，等待时间取该页和任务剩余时间中的较小值。 */
    private void runTask(long taskId) {
        if (mapper.startTask(taskId) != 1) {
            return;
        }

        long taskDeadline = System.nanoTime() + taskTimeoutNanos;
        String stopReason = null;
        boolean interrupted = false;
        try {
            long afterProductId = 0;
            while (true) {
                if (taskDeadline - System.nanoTime() <= 0) {
                    stopReason = "任务达到总时限";
                    break;
                }
                List<ImageSyncResultVO> page =
                        mapper.selectTaskItems(taskId, afterProductId, MAX_PAGE_SIZE);
                if (page.isEmpty()) {
                    break;
                }

                long pageQueuedAt = System.nanoTime();
                long taskLeft = taskDeadline - pageQueuedAt;
                if (taskLeft <= 0) {
                    stopReason = "任务达到总时限";
                    break;
                }
                long pageDeadline = pageQueuedAt + Math.min(taskLeft, batchWaitNanos);
                String timeoutReason = taskLeft <= batchWaitNanos
                        ? "任务达到总时限" : "分页批次等待超时";

                // 一页的等待包含排队。分页线程每次只领取一条尚未超时的明细。
                Future<Boolean> work = pageExecutor.submit(() -> {
                    for (ImageSyncResultVO item : page) {
                        if (pageDeadline - System.nanoTime() <= 0
                                || !imageSyncService.syncBatchItem(item)) {
                            return false;
                        }
                    }
                    return true;
                });

                try {
                    long wait = Math.max(0L, pageDeadline - System.nanoTime());
                    if (!work.get(wait, TimeUnit.NANOSECONDS)) {
                        stopReason = timeoutReason;
                        break;
                    }
                } catch (TimeoutException exception) {
                    // 取消可阻止队列中的工作开始，已发出的 HTTP 仍可能完成。
                    work.cancel(false);
                    stopReason = timeoutReason;
                    break;
                } catch (InterruptedException exception) {
                    work.cancel(false);
                    throw exception;
                }

                if (page.size() < MAX_PAGE_SIZE) {
                    break;
                }
                afterProductId = page.get(page.size() - 1).productId();
            }
        } catch (TaskRejectedException exception) {
            stopReason = "分页线程池拒绝批次";
        } catch (InterruptedException exception) {
            interrupted = true;
            stopReason = "批量任务等待被中断";
        } catch (ExecutionException | RuntimeException exception) {
            log.error("图片同步任务中断，taskId={}", taskId, exception);
            stopReason = "批量任务执行中断";
        }

        try {
            String reason = stopReason;
            transactions.executeWithoutResult(tx -> {
                // 先关闭未完明细再结束任务，让人工重试看见的是同一次提交的结果。
                if (reason != null) {
                    mapper.closeOpenItems(taskId, reason);
                }
                imageSyncService.finishTask(taskId, reason);
            });
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 返回新建任务的 ID 和所属产品类型。 */
    public record CreatedTask(long taskId, String productType) {
    }
}
