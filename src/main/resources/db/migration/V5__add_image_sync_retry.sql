ALTER TABLE image_sync_task_item
    ADD COLUMN execution_no INT NOT NULL DEFAULT 1
    COMMENT '本条明细的执行序号，初次为1，每次人工重试加1',
    ADD COLUMN cleanup_retry_count INT NOT NULL DEFAULT 0
        COMMENT '已发起的人工清理次数，首次自动清理不计入',
    ADD COLUMN cleanup_status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '旧图清理的最近状态',
    ADD CONSTRAINT ck_image_sync_execution_no CHECK (execution_no >= 1),
    ADD CONSTRAINT ck_image_sync_cleanup_retry_count CHECK (cleanup_retry_count >= 0),
    ADD CONSTRAINT ck_image_sync_cleanup_status
        CHECK (cleanup_status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'UNKNOWN'));

-- 旧进程已经停止，遗留的执行中明细可能已发出上传请求。
UPDATE image_sync_task_item
SET status = 'UNKNOWN', result_message = '升级前的执行已中断，上传结果待确认'
WHERE status = 'RUNNING';

UPDATE image_sync_task
SET status = 'FAILED', result_message = '升级前的任务已中断'
WHERE status = 'RUNNING';

-- V4 只留下待删除的 ID 和错误，按现有记录恢复待处理状态。
UPDATE image_sync_task_item
SET cleanup_status = CASE WHEN cleanup_error IS NULL THEN 'PENDING' ELSE 'UNKNOWN' END
WHERE pending_delete_image_id IS NOT NULL;