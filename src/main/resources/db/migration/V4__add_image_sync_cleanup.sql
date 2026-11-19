ALTER TABLE image_sync_task_item
    ADD COLUMN pending_delete_image_id VARCHAR(128)
        CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT '尚未确认删除的外部图片ID',
    ADD COLUMN cleanup_error VARCHAR(255) NULL
        COMMENT '最近一次清理失败的原因';