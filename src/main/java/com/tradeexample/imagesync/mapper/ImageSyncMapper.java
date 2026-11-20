package com.tradeexample.imagesync.mapper;

import com.tradeexample.imagesync.vo.ImageSyncResultVO;
import com.tradeexample.imagesync.vo.ImageSyncTaskVO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 读取源图和映射，写入同步任务、明细执行和清理结果。 */
public interface ImageSyncMapper {

    /** 同时读取源图片版本和当前生效映射，供上传前和发布时比较。 */
    @Select("""
            SELECT p.id AS product_id, p.product_type, p.image_url, p.image_version,
                   m.image_version AS mapped_version,
                   m.external_image_id AS mapped_external_image_id
            FROM image_product p
            LEFT JOIN image_mapping m ON m.product_id = p.id
            WHERE p.id = #{productId}
            """)
    ProductSnapshot selectProduct(@Param("productId") long productId);

    /** 发布映射前锁住产品，让同一产品的发布操作依次检查版本。 */
    @Select("""
            SELECT image_version
            FROM image_product
            WHERE id = #{productId}
            FOR UPDATE
            """)
    Long lockImageVersion(@Param("productId") long productId);

    /** 写入任务并将生成的 ID 回写给调用方。 */
    @Insert("""
            INSERT INTO image_sync_task (product_type, status)
            VALUES (#{productType}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertTask(TaskInsert task);

    /** 写入已开始执行的单条同步明细并回写 ID。 */
    @Insert("""
            INSERT INTO image_sync_task_item (task_id, product_id, image_version, status)
            VALUES (#{taskId}, #{productId}, #{imageVersion}, 'RUNNING')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertItem(TaskItemInsert item);

    /** 为首次同步的产品发布外部图片映射。 */
    @Insert("""
            INSERT INTO image_mapping (product_id, image_version, external_image_id)
            VALUES (#{productId}, #{imageVersion}, #{externalImageId})
            """)
    int insertMapping(
            @Param("productId") long productId,
            @Param("imageVersion") long imageVersion,
            @Param("externalImageId") String externalImageId
    );

    /** 只替换刚读到的旧映射，映射版本只能往前走。 */
    @Update("""
            UPDATE image_mapping
            SET image_version = #{imageVersion},
                external_image_id = #{externalImageId}
            WHERE product_id = #{productId}
              AND image_version = #{previousVersion}
              AND image_version < #{imageVersion}
            """)
    int replaceMapping(
            @Param("productId") long productId,
            @Param("previousVersion") long previousVersion,
            @Param("imageVersion") long imageVersion,
            @Param("externalImageId") String externalImageId
    );

    /** 只接收当前执行的写回，已超时或被重试接管的旧执行无法修改结果。 */
    @Update("""
            UPDATE image_sync_task_item
            SET status = #{status},
                external_image_id = #{externalImageId,jdbcType=VARCHAR},
                result_message = #{message,jdbcType=VARCHAR},
                pending_delete_image_id = #{pendingDeleteId,jdbcType=VARCHAR},
                cleanup_status = CASE WHEN #{pendingDeleteId,jdbcType=VARCHAR} IS NULL
                                      THEN NULL ELSE 'PENDING' END
            WHERE id = #{itemId} AND status = 'RUNNING'
              AND execution_no = #{executionNo}
            """)
    int finishItem(
            @Param("itemId") long itemId,
            @Param("executionNo") int executionNo,
            @Param("status") String status,
            @Param("externalImageId") String externalImageId,
            @Param("message") String message,
            @Param("pendingDeleteId") String pendingDeleteId
    );

    /** 只允许执行中的任务写入汇总结果。 */
    @Update("""
            UPDATE image_sync_task
            SET status = #{status}, result_message = #{message,jdbcType=VARCHAR}
            WHERE id = #{taskId} AND status = 'RUNNING'
            """)
    int finishTask(
            @Param("taskId") long taskId,
            @Param("status") String status,
            @Param("message") String message
    );

    /** 只接收当前清理次数对同一外部图片的结果。 */
    @Update("""
            UPDATE image_sync_task_item
            SET pending_delete_image_id = CASE WHEN #{status} = 'SUCCESS'
                                               THEN NULL ELSE pending_delete_image_id END,
                cleanup_status = #{status},
                cleanup_error = #{error,jdbcType=VARCHAR}
            WHERE id = #{itemId} AND pending_delete_image_id = #{expectedId}
              AND cleanup_retry_count = #{cleanupRetryCount}
            """)
    int finishCleanup(
            @Param("itemId") long itemId,
            @Param("expectedId") String expectedId,
            @Param("cleanupRetryCount") int cleanupRetryCount,
            @Param("status") String status,
            @Param("error") String error
    );

    /** 查询一条明细的执行和清理结果。 */
    @Select("""
            SELECT task_id, id AS item_id, product_id, image_version, status,
                   external_image_id, result_message, pending_delete_image_id, cleanup_error,
                   execution_no, cleanup_retry_count, cleanup_status
            FROM image_sync_task_item
            WHERE id = #{itemId}
            """)
    ImageSyncResultVO selectResult(@Param("itemId") long itemId);

    /** 一次写入三类任务的产品清单和源图版本，后续按这份清单执行。 */
    @Insert("""
            INSERT INTO image_sync_task_item (task_id, product_id, image_version, status)
            SELECT t.id, p.id, p.image_version, 'PENDING'
            FROM image_sync_task t
            JOIN image_product p ON p.product_type = t.product_type
            WHERE t.id IN (#{ownTaskId}, #{customerTaskId}, #{accessoryTaskId})
            """)
    int insertBatchItems(
            @Param("ownTaskId") long ownTaskId,
            @Param("customerTaskId") long customerTaskId,
            @Param("accessoryTaskId") long accessoryTaskId
    );

    /** 将排队中的批量任务改成执行中。 */
    @Update("""
            UPDATE image_sync_task
            SET status = 'RUNNING'
            WHERE id = #{taskId} AND status = 'PENDING'
            """)
    int startTask(@Param("taskId") long taskId);

    /** 只有当前执行还有待处理明细时，分页线程才取得执行权。 */
    @Update("""
            UPDATE image_sync_task_item
            SET status = 'RUNNING'
            WHERE task_id = #{taskId} AND id = #{itemId} AND status = 'PENDING'
              AND execution_no = #{executionNo}
            """)
    int startItem(
            @Param("taskId") long taskId,
            @Param("itemId") long itemId,
            @Param("executionNo") int executionNo
    );

    /** 写入任务未能继续执行的原因。 */
    @Update("""
            UPDATE image_sync_task
            SET status = 'FAILED', result_message = #{message}
            WHERE id = #{taskId} AND status IN ('PENDING', 'RUNNING')
            """)
    int failOpenTask(@Param("taskId") long taskId, @Param("message") String message);

    /** 关闭尚未完成的明细，已经写回的结果继续有效。 */
    @Update("""
            UPDATE image_sync_task_item
            SET result_message = CASE WHEN status = 'RUNNING'
                                      THEN CONCAT(#{reason}, '，上传结果待确认')
                                      ELSE CONCAT(#{reason}, '，明细未开始') END,
                status = CASE WHEN status = 'RUNNING' THEN 'UNKNOWN' ELSE 'FAILED' END
            WHERE task_id = #{taskId} AND status IN ('PENDING', 'RUNNING')
            """)
    int closeOpenItems(@Param("taskId") long taskId, @Param("reason") String reason);

    /** 同一个已结束的任务一次只接收一条人工重试。 */
    @Update("""
            UPDATE image_sync_task
            SET status = 'RUNNING', result_message = NULL
            WHERE id = #{taskId} AND status IN ('FAILED', 'PARTIAL_FAILED')
            """)
    int reopenForRetry(@Param("taskId") long taskId);

    /** 重试取得执行权时增加序号，让晚到的旧执行无法写回。 */
    @Update("""
            UPDATE image_sync_task_item
            SET status = 'RUNNING', execution_no = execution_no + 1, result_message = NULL
            WHERE task_id = #{taskId} AND id = #{itemId}
              AND execution_no = #{expectedExecutionNo}
              AND status = #{previousStatus}
              AND status IN ('FAILED', 'UNKNOWN')
            """)
    int claimRetry(
            @Param("taskId") long taskId,
            @Param("itemId") long itemId,
            @Param("expectedExecutionNo") int expectedExecutionNo,
            @Param("previousStatus") String previousStatus
    );

    /** 只有待删图片和次数都吻合时，才为这次人工清理计数。 */
    @Update("""
            UPDATE image_sync_task_item
            SET cleanup_retry_count = cleanup_retry_count + 1,
                cleanup_status = 'RUNNING',
                cleanup_error = NULL
            WHERE id = #{itemId} AND pending_delete_image_id = #{expectedId}
              AND cleanup_retry_count = #{expectedRetryCount}
            """)
    int claimCleanupRetry(
            @Param("itemId") long itemId,
            @Param("expectedId") String expectedId,
            @Param("expectedRetryCount") int expectedRetryCount
    );

    /** 汇总任务所含明细的各类同步结果。 */
    @Select("""
            SELECT t.id AS task_id, t.product_type, t.status, t.result_message,
                   COUNT(i.id) AS total_count,
                   COUNT(CASE WHEN i.status = 'PENDING' THEN 1 END) AS pending_count,
                   COUNT(CASE WHEN i.status = 'RUNNING' THEN 1 END) AS running_count,
                   COUNT(CASE WHEN i.status = 'SUCCESS' THEN 1 END) AS success_count,
                   COUNT(CASE WHEN i.status = 'SKIPPED' THEN 1 END) AS skipped_count,
                   COUNT(CASE WHEN i.status = 'FAILED' THEN 1 END) AS failed_count,
                   COUNT(CASE WHEN i.status = 'UNKNOWN' THEN 1 END) AS unknown_count
            FROM image_sync_task t
            LEFT JOIN image_sync_task_item i ON i.task_id = t.id
            WHERE t.id = #{taskId}
            GROUP BY t.id, t.product_type, t.status, t.result_message
            """)
    ImageSyncTaskVO selectTaskProgress(@Param("taskId") long taskId);

    /** 区分不存在的任务和明细为空的任务。 */
    @Select("SELECT EXISTS (SELECT 1 FROM image_sync_task WHERE id = #{taskId})")
    boolean taskExists(@Param("taskId") long taskId);

    /** 同一任务内每个产品只有一条明细，直接用产品 ID 做游标。 */
    @Select("""
            SELECT task_id, id AS item_id, product_id, image_version, status,
                   external_image_id, result_message, pending_delete_image_id, cleanup_error,
                   execution_no, cleanup_retry_count, cleanup_status
            FROM image_sync_task_item
            WHERE task_id = #{taskId} AND product_id > #{afterProductId}
            ORDER BY product_id ASC
            LIMIT #{limit}
            """)
    List<ImageSyncResultVO> selectTaskItems(
            @Param("taskId") long taskId,
            @Param("afterProductId") long afterProductId,
            @Param("limit") int limit
    );


    /** 产品当前的源图和用于对照的生效映射。 */
    record ProductSnapshot(
            long productId,
            String productType,
            String imageUrl,
            long imageVersion,
            Long mappedVersion,
            String mappedExternalImageId
    ) {
        /** 判断映射是否已经同步到当前源图版本。 */
        public boolean hasCurrentMapping() {
            return mappedVersion != null && mappedVersion == imageVersion;
        }
    }

    /** 写入一项图片同步任务所需的信息及生成的 ID。 */
    @Getter
    @Setter
    @RequiredArgsConstructor
    class TaskInsert {
        private Long id;
        private final String productType;
        private final String status;
    }

    /** 写入一条同步明细所需的任务、产品、源图版本和生成的 ID。 */
    @Getter
    @Setter
    @RequiredArgsConstructor
    class TaskItemInsert {
        private Long id;
        private final long taskId;
        private final long productId;
        private final long imageVersion;
    }

}
