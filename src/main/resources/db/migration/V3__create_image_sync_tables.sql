CREATE TABLE image_product
(
    id            BIGINT                                            NOT NULL AUTO_INCREMENT,
    product_type  VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    product_no    VARCHAR(64)                                       NOT NULL,
    image_url     VARCHAR(1024)                                     NOT NULL COMMENT '待同步的源图片地址',
    image_version BIGINT                                            NOT NULL DEFAULT 1 COMMENT '源图片变化时递增',
    created_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_image_product_type_no (product_type, product_no),
    CONSTRAINT ck_image_product_type
        CHECK (product_type IN ('OWN', 'CUSTOMER', 'ACCESSORY')),
    CONSTRAINT ck_image_product_source
        CHECK (image_version >= 1 AND CHAR_LENGTH(TRIM(image_url)) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '待同步图片的产品';


CREATE TABLE image_mapping
(
    product_id        BIGINT                                             NOT NULL,
    image_version     BIGINT                                             NOT NULL COMMENT '当前外部图片对应的源图版本',
    external_image_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '外部图库返回的图片ID',
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (product_id),
    UNIQUE KEY uk_image_mapping_external_id (external_image_id),
    CONSTRAINT fk_image_mapping_product
        FOREIGN KEY (product_id) REFERENCES image_product (id),
    CONSTRAINT ck_image_mapping_version
        CHECK (image_version >= 1),
    CONSTRAINT ck_image_mapping_external_id
        CHECK (CHAR_LENGTH(TRIM(external_image_id)) > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '产品当前生效的外部图片映射';


CREATE TABLE image_sync_task
(
    id             BIGINT                                            NOT NULL AUTO_INCREMENT,
    product_type   VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status         VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
                                                                     NOT NULL DEFAULT 'PENDING',
    result_message VARCHAR(255) NULL COMMENT '任务执行失败等情况的说明',
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    CONSTRAINT ck_image_sync_task_product_type
        CHECK (product_type IN ('OWN', 'CUSTOMER', 'ACCESSORY')),
    CONSTRAINT ck_image_sync_task_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'PARTIAL_FAILED', 'FAILED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '按产品类型执行的图片同步任务';


CREATE TABLE image_sync_task_item
(
    id                BIGINT NOT NULL AUTO_INCREMENT,
    task_id           BIGINT NOT NULL,
    product_id        BIGINT NOT NULL,
    image_version     BIGINT NOT NULL COMMENT '本条任务明细要处理的源图版本',
    status            VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
                             NOT NULL DEFAULT 'PENDING',
    external_image_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL
                                   COMMENT '本次同步成功时生效的外部图片ID',
    result_message    VARCHAR(255) NULL COMMENT '跳过、失败或结果未知的原因',
    created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_image_sync_item_task_product (task_id, product_id),
    KEY               idx_image_sync_item_product (product_id),
    CONSTRAINT fk_image_sync_item_task
        FOREIGN KEY (task_id) REFERENCES image_sync_task (id),
    CONSTRAINT fk_image_sync_item_product
        FOREIGN KEY (product_id) REFERENCES image_product (id),
    CONSTRAINT ck_image_sync_item_version
        CHECK (image_version >= 1),
    CONSTRAINT ck_image_sync_item_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'SKIPPED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_image_sync_item_success_id
        CHECK (
            status <> 'SUCCESS'
                OR (external_image_id IS NOT NULL AND CHAR_LENGTH(TRIM(external_image_id)) > 0)
            )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = '任务内每个产品的图片同步结果';