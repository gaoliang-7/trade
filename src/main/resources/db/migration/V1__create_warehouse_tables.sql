CREATE TABLE wms_inventory_detail
(
    id                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '库存明细ID',
    warehouse_code    VARCHAR(32) NOT NULL COMMENT '仓库编码',
    purchase_order_no VARCHAR(64) NOT NULL COMMENT '采购单号',
    product_no        VARCHAR(64) NOT NULL COMMENT '产品编号',
    inbound_order_no  VARCHAR(64) NOT NULL COMMENT '入库单号',
    inbound_date      DATE        NOT NULL COMMENT '入库日期',

    input_box_count   INT         NOT NULL COMMENT '入库箱数',
    output_box_count  INT         NOT NULL DEFAULT 0 COMMENT '当前净出库箱数',
    stock_box_count   INT         NOT NULL COMMENT '当前在仓箱数',

    version           INT         NOT NULL DEFAULT 0 COMMENT '并发控制版本',
    created_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    KEY               idx_inventory_fifo (
        warehouse_code,
        purchase_order_no,
        product_no,
        inbound_date,
        id
    ),

    CONSTRAINT ck_inventory_quantity CHECK (
        input_box_count >= 0
            AND output_box_count >= 0
            AND stock_box_count >= 0
            AND stock_box_count = input_box_count - output_box_count
        ),

    CONSTRAINT ck_inventory_version CHECK (
        version >= 0
        )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '库存批次明细';



CREATE TABLE wms_outbound_order
(
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '出库单ID',
    order_no        VARCHAR(64) NOT NULL COMMENT '出库单号',
    warehouse_code  VARCHAR(32) NOT NULL COMMENT '出库仓库编码',
    outbound_date   DATE        NOT NULL COMMENT '出库日期',

    status          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
                                NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT草稿，SUBMITTED已提交，CANCELED已作废',

    revision        INT         NOT NULL DEFAULT 1 COMMENT '当前业务修订号',
    total_box_count BIGINT      NOT NULL DEFAULT 0 COMMENT '当前修订合计出库箱数',
    version         INT         NOT NULL DEFAULT 0 COMMENT '并发控制版本',

    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
        ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    UNIQUE KEY uk_outbound_order_no (order_no),

    CONSTRAINT ck_outbound_order_status CHECK (
        status IN ('DRAFT', 'SUBMITTED', 'CANCELED')
        ),

    CONSTRAINT ck_outbound_order_numbers CHECK (
        revision >= 1
            AND total_box_count >= 0
            AND version >= 0
        )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '出库单主表';



CREATE TABLE wms_outbound_order_item
(
    id                       BIGINT      NOT NULL AUTO_INCREMENT COMMENT '出库明细ID',
    outbound_order_id        BIGINT      NOT NULL COMMENT '所属出库单ID',
    revision                 INT         NOT NULL COMMENT '所属业务修订号',
    line_no                  INT         NOT NULL COMMENT '当前修订内的明细行号',

    inventory_detail_id      BIGINT      NOT NULL COMMENT '分配的库存明细ID',
    purchase_order_no        VARCHAR(64) NOT NULL COMMENT '采购单号快照',
    product_no               VARCHAR(64) NOT NULL COMMENT '产品编号快照',

    outbound_box_count       INT         NOT NULL COMMENT '本行出库箱数',
    stock_snapshot_box_count INT         NOT NULL COMMENT '生成时该库存批次的余量快照',

    created_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    UNIQUE KEY uk_outbound_item_revision_line (
        outbound_order_id,
        revision,
        line_no
        ),

    KEY                      idx_outbound_item_inventory (inventory_detail_id),

    CONSTRAINT fk_outbound_item_order
        FOREIGN KEY (outbound_order_id)
            REFERENCES wms_outbound_order (id),

    CONSTRAINT fk_outbound_item_inventory
        FOREIGN KEY (inventory_detail_id)
            REFERENCES wms_inventory_detail (id),

    CONSTRAINT ck_outbound_item_numbers CHECK (
        revision >= 1
            AND line_no >= 1
        ),

    CONSTRAINT ck_outbound_item_quantity CHECK (
        outbound_box_count > 0
            AND stock_snapshot_box_count >= outbound_box_count
        )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '出库修订明细';







CREATE TABLE wms_inventory_movement
(
    id                     BIGINT NOT NULL AUTO_INCREMENT COMMENT '库存流水ID',

    outbound_order_id      BIGINT NOT NULL COMMENT '出库单ID',
    revision               INT    NOT NULL COMMENT '出库业务修订号',
    outbound_order_item_id BIGINT NOT NULL COMMENT '出库明细ID',
    inventory_detail_id    BIGINT NOT NULL COMMENT '库存明细ID',

    movement_type          VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin
                                  NOT NULL COMMENT 'DEDUCT扣减，RESTORE恢复',

    box_count_change       INT    NOT NULL COMMENT '库存变化量，扣减为负，恢复为正',
    before_stock_box_count INT    NOT NULL COMMENT '变动前在仓箱数',
    after_stock_box_count  INT    NOT NULL COMMENT '变动后在仓箱数',

    original_movement_id   BIGINT NULL COMMENT '恢复时关联的原扣减流水ID',
    created_at             DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),

    UNIQUE KEY uk_movement_item_type (
        outbound_order_item_id,
        movement_type
        ),

    UNIQUE KEY uk_movement_original (original_movement_id),

    KEY                    idx_movement_order_revision (
        outbound_order_id,
        revision,
        movement_type
    ),

    KEY                    idx_movement_inventory (
        inventory_detail_id,
        id
    ),

    CONSTRAINT fk_movement_order
        FOREIGN KEY (outbound_order_id)
            REFERENCES wms_outbound_order (id),

    CONSTRAINT fk_movement_item
        FOREIGN KEY (outbound_order_item_id)
            REFERENCES wms_outbound_order_item (id),

    CONSTRAINT fk_movement_inventory
        FOREIGN KEY (inventory_detail_id)
            REFERENCES wms_inventory_detail (id),

    CONSTRAINT fk_movement_original
        FOREIGN KEY (original_movement_id)
            REFERENCES wms_inventory_movement (id),

    CONSTRAINT ck_movement_revision CHECK (
        revision >= 1
        ),

    CONSTRAINT ck_movement_balance CHECK (
        before_stock_box_count >= 0
            AND after_stock_box_count >= 0
            AND after_stock_box_count =
                before_stock_box_count + box_count_change
        ),

    CONSTRAINT ck_movement_direction CHECK (
        (
            movement_type = 'DEDUCT'
                AND box_count_change < 0
                AND original_movement_id IS NULL
            )
            OR
        (
            movement_type = 'RESTORE'
                AND box_count_change > 0
                AND original_movement_id IS NOT NULL
            )
        )
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '库存变动流水';