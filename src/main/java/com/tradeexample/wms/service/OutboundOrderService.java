package com.tradeexample.wms.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tradeexample.common.exception.BusinessException;
import com.tradeexample.common.exception.ErrorCode;
import com.tradeexample.wms.dto.CreateOutboundOrderRequest;
import com.tradeexample.wms.entity.InventoryDetail;
import com.tradeexample.wms.entity.OutboundOrder;
import com.tradeexample.wms.entity.OutboundOrderItem;
import com.tradeexample.wms.mapper.InventoryDetailMapper;
import com.tradeexample.wms.mapper.OutboundOrderItemMapper;
import com.tradeexample.wms.mapper.OutboundOrderMapper;
import com.tradeexample.wms.vo.OutboundOrderDetailVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.tradeexample.wms.dto.SubmitOutboundOrderRequest;
import com.tradeexample.wms.entity.InventoryMovement;
import com.tradeexample.wms.mapper.InventoryMovementMapper;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 生成和查询出库草稿。 */
@Service
@RequiredArgsConstructor
public class OutboundOrderService {

    private final InventoryDetailMapper inventoryDetailMapper;
    private final OutboundOrderMapper outboundOrderMapper;
    private final OutboundOrderItemMapper outboundOrderItemMapper;
    private final InventoryMovementMapper inventoryMovementMapper;

    /**
     * 按FIFO生成草稿。全部需求满足后，在同一事务中保存主表和明细，
     * 任意保存失败都回滚。
     */
    @Transactional(
            isolation = Isolation.REPEATABLE_READ,
            rollbackFor = Exception.class
    )
    public OutboundOrderDetailVO createDraft(CreateOutboundOrderRequest request) {

        String warehouseCode = request.warehouseCode().strip();

        checkDuplicateDemands(request.demands());

        List<OutboundOrderItem> items =
                allocate(warehouseCode, request.demands());

        long totalBoxCount = 0L;
        for (OutboundOrderItem item : items) {
            totalBoxCount += item.getOutboundBoxCount().longValue();
        }

        OutboundOrder order = new OutboundOrder();
        order.setOrderNo(
                "OUT-" + UUID.randomUUID().toString().replace("-", "")
        );
        order.setWarehouseCode(warehouseCode);
        order.setOutboundDate(request.outboundDate());
        order.setStatus("DRAFT");
        order.setRevision(1);
        order.setTotalBoxCount(totalBoxCount);
        order.setVersion(0);

        if (outboundOrderMapper.insert(order) != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }

        for (OutboundOrderItem item : items) {
            item.setOutboundOrderId(order.getId());
            item.setRevision(order.getRevision());

            if (outboundOrderItemMapper.insert(item) != 1) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR);
            }
        }

        return loadDetail(order.getId());
    }

    /** 提交当前修订。状态、库存和流水在同一个事务里修改。 */
    @Transactional(
            isolation = Isolation.READ_COMMITTED,
            rollbackFor = Exception.class
    )
    public OutboundOrderDetailVO submit(Long id, SubmitOutboundOrderRequest request) {

        OutboundOrder order = outboundOrderMapper.selectByIdForUpdate(id);
        if (order == null) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "出库单不存在"
            );
        }

        if (!order.getRevision().equals(request.revision())) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "出库单修订号不匹配，请刷新后确认"
            );
        }

        if ("SUBMITTED".equals(order.getStatus())) {
            return loadDetail(id);
        }

        if (!"DRAFT".equals(order.getStatus())) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "当前状态不允许提交出库单"
            );
        }

        if (!order.getVersion().equals(request.version())) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "出库单版本已变化，请刷新后确认"
            );
        }

        int updatedRows = outboundOrderMapper.markSubmitted(
                id, request.revision(), request.version()
        );

        if (updatedRows != 1) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "出库单状态或版本已变化，请刷新后确认"
            );
        }

        OutboundOrderDetailVO detail = loadDetail(id);
        if (detail.items().isEmpty()) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "出库单缺少当前修订的分配明细"
            );
        }

        // 多张单按相同的库存ID顺序更新，减少互相等待造成的死锁。
        List<OutboundOrderItem> itemsByInventoryId = detail.items().stream()
                .sorted(Comparator.comparing(OutboundOrderItem::getInventoryDetailId))
                .toList();

        for (OutboundOrderItem item : itemsByInventoryId) {
            deductAndRecord(item);
        }

        return detail;
    }

    /**
     * 查询主表和当前修订明细。
     * 两次查询使用同一事务快照，避免读到不同时间的主表和明细。
     */
    @Transactional(
            readOnly = true,
            isolation = Isolation.REPEATABLE_READ
    )
    public OutboundOrderDetailVO getDetail(Long id) {
        return loadDetail(id);
    }

    /** 去掉首尾空白后检查重复项 */
    private void checkDuplicateDemands(
            List<CreateOutboundOrderRequest.Demand> demands) {

        Set<DemandKey> keys = new HashSet<>();

        for (CreateOutboundOrderRequest.Demand demand : demands) {
            DemandKey key = new DemandKey(
                    demand.purchaseOrderNo().strip(),
                    demand.productNo().strip()
            );

            if (!keys.add(key)) {
                throw new BusinessException(
                        ErrorCode.INVALID_ARGUMENT,
                        "需求项重复：采购单号=" + key.purchaseOrderNo()
                                + "，产品编号=" + key.productNo()
                );
            }
        }
    }

    /**
     * 每项先取最早入库的批次，不够再取下一批。
     * 仅构造明细，库存不足时抛出异常。
     */
    private List<OutboundOrderItem> allocate(String warehouseCode, List<CreateOutboundOrderRequest.Demand> demands) {

        List<OutboundOrderItem> items = new ArrayList<>();
        Set<Long> matchedInventoryIds = new HashSet<>();

        for (CreateOutboundOrderRequest.Demand demand : demands) {
            String purchaseOrderNo = demand.purchaseOrderNo().strip();
            String productNo = demand.productNo().strip();

            List<InventoryDetail> inventoryList = inventoryDetailMapper.selectList(
                            Wrappers.<InventoryDetail>lambdaQuery()
                                    .eq(
                                            InventoryDetail::getWarehouseCode,
                                            warehouseCode
                                    )
                                    .eq(
                                            InventoryDetail::getPurchaseOrderNo,
                                            purchaseOrderNo
                                    )
                                    .eq(
                                            InventoryDetail::getProductNo,
                                            productNo
                                    )
                                    .gt(
                                            InventoryDetail::getStockBoxCount,
                                            0
                                    )
                                    .orderByAsc(
                                            InventoryDetail::getInboundDate
                                    )
                                    .orderByAsc(
                                            InventoryDetail::getId
                                    )
                    );

            for (InventoryDetail inventory : inventoryList) {
                if (!matchedInventoryIds.add(inventory.getId())) {
                    throw new BusinessException(
                            ErrorCode.INVALID_ARGUMENT,
                            "需求项匹配到重复库存，请合并同一采购单和产品的需求"
                    );
                }
            }

            int remaining = demand.boxCount();

            for (InventoryDetail inventory : inventoryList) {
                if (remaining == 0) {
                    break;
                }

                int allocated = Math.min(
                        remaining,
                        inventory.getStockBoxCount()
                );

                OutboundOrderItem item = new OutboundOrderItem();
                item.setLineNo(items.size() + 1);
                item.setInventoryDetailId(inventory.getId());

                item.setPurchaseOrderNo(inventory.getPurchaseOrderNo());
                item.setProductNo(inventory.getProductNo());
                item.setOutboundBoxCount(allocated);
                item.setStockSnapshotBoxCount(
                        inventory.getStockBoxCount()
                );

                items.add(item);
                remaining -= allocated;
            }

            if (remaining > 0) {
                throw new BusinessException(
                        ErrorCode.BUSINESS_CONFLICT,
                        "库存不足：采购单号=" + purchaseOrderNo
                                + "，产品编号=" + productNo
                                + "，需求" + demand.boxCount()
                                + "箱，缺少" + remaining + "箱"
                );
            }
        }

        return items;
    }

    /** 按草稿明细扣减原批次，记录实际扣减前后的在仓箱数。 */
    private void deductAndRecord(OutboundOrderItem item) {

        Long inventoryId = item.getInventoryDetailId();
        InventoryDetail inventory = inventoryDetailMapper.selectById(inventoryId);
        if (inventory == null) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "分配的库存批次不存在"
            );
        }

        int boxCount = item.getOutboundBoxCount();
        int beforeStock = inventory.getStockBoxCount();

        if (beforeStock < boxCount) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "库存批次 " + inventoryId + " 余量不足，需要 "
                            + boxCount + " 箱，当前 " + beforeStock + " 箱"
            );
        }

        int updatedRows = inventoryDetailMapper.deductStock(
                inventoryId, boxCount, inventory.getVersion()
        );
        if (updatedRows != 1) {
            throw new BusinessException(
                    ErrorCode.BUSINESS_CONFLICT,
                    "库存批次 " + inventoryId + " 已变化，请重试提交"
            );
        }

        InventoryMovement movement = new InventoryMovement();
        movement.setOutboundOrderId(item.getOutboundOrderId());
        movement.setRevision(item.getRevision());
        movement.setOutboundOrderItemId(item.getId());
        movement.setInventoryDetailId(inventoryId);
        movement.setMovementType("DEDUCT");
        movement.setBoxCountChange(-boxCount);
        movement.setBeforeStockBoxCount(beforeStock);
        movement.setAfterStockBoxCount(beforeStock - boxCount);

        if (inventoryMovementMapper.insert(movement) != 1) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR);
        }
    }

    /** 只查询主表当前revision对应的明细，按行号返回。 */
    private OutboundOrderDetailVO loadDetail(Long id) {
        OutboundOrder order = outboundOrderMapper.selectById(id);

        if (order == null) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "出库单不存在"
            );
        }

        List<OutboundOrderItem> items =
                outboundOrderItemMapper.selectList(
                        Wrappers.<OutboundOrderItem>lambdaQuery()
                                .eq(
                                        OutboundOrderItem::getOutboundOrderId,
                                        order.getId()
                                )
                                .eq(
                                        OutboundOrderItem::getRevision,
                                        order.getRevision()
                                )
                                .orderByAsc(
                                        OutboundOrderItem::getLineNo
                                )
                );

        return new OutboundOrderDetailVO(order, List.copyOf(items));
    }

    private record DemandKey(
            String purchaseOrderNo,
            String productNo
    ) {
    }
}