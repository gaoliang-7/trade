package com.tradeexample.imagesync.mapper;

import com.tradeexample.imagesync.vo.ImageSyncResultVO;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import com.tradeexample.imagesync.vo.ImageSyncTaskVO;

/** 读取源图和当前映射，保存单条同步及清理结果。 */
public interface ImageSyncMapper {

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

    @Insert("""
            INSERT INTO image_sync_task (product_type, status)
            VALUES (#{productType}, #{status})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertTask(TaskInsert task);

    @Insert("""
            INSERT INTO image_sync_task_item (task_id, product_id, image_version, status)
            VALUES (#{taskId}, #{productId}, #{imageVersion}, 'RUNNING')
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertItem(TaskItemInsert item);

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

    @Update("""
            UPDATE image_sync_task_item
            SET status = #{status},
                external_image_id = #{externalImageId,jdbcType=VARCHAR},
                result_message = #{message,jdbcType=VARCHAR},
                pending_delete_image_id = #{pendingDeleteId,jdbcType=VARCHAR}
            WHERE id = #{itemId} AND status = 'RUNNING'
            """)
    int finishItem(
            @Param("itemId") long itemId,
            @Param("status") String status,
            @Param("externalImageId") String externalImageId,
            @Param("message") String message,
            @Param("pendingDeleteId") String pendingDeleteId
    );

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

    @Update("""
            UPDATE image_sync_task_item
            SET pending_delete_image_id = #{remainingId,jdbcType=VARCHAR},
                cleanup_error = #{error,jdbcType=VARCHAR}
            WHERE id = #{itemId} AND pending_delete_image_id = #{expectedId}
            """)
    int finishCleanup(
            @Param("itemId") long itemId,
            @Param("expectedId") String expectedId,
            @Param("remainingId") String remainingId,
            @Param("error") String error
    );

    @Select("""
            SELECT task_id, id AS item_id, product_id, image_version, status,
                   external_image_id, result_message, pending_delete_image_id, cleanup_error
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

    @Update("""
            UPDATE image_sync_task
            SET status = 'RUNNING'
            WHERE id = #{taskId} AND status = 'PENDING'
            """)
    int startTask(@Param("taskId") long taskId);

    @Update("""
            UPDATE image_sync_task_item
            SET status = 'RUNNING'
            WHERE task_id = #{taskId} AND id = #{itemId} AND status = 'PENDING'
            """)
    int startItem(@Param("taskId") long taskId, @Param("itemId") long itemId);

    @Update("""
            UPDATE image_sync_task
            SET status = 'FAILED', result_message = #{message}
            WHERE id = #{taskId} AND status IN ('PENDING', 'RUNNING')
            """)
    int failOpenTask(@Param("taskId") long taskId, @Param("message") String message);

    @Update("""
            UPDATE image_sync_task_item
            SET status = CASE WHEN status = 'PENDING' THEN 'FAILED' ELSE 'UNKNOWN' END,
                result_message = #{message}
            WHERE id = #{itemId} AND status IN ('PENDING', 'RUNNING')
            """)
    int recordItemError(
            @Param("itemId") long itemId,
            @Param("message") String message
    );

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

    @Select("SELECT EXISTS (SELECT 1 FROM image_sync_task WHERE id = #{taskId})")
    boolean taskExists(@Param("taskId") long taskId);

    /** 同一任务内每个产品只有一条明细，直接用产品 ID 做游标。 */
    @Select("""
            SELECT task_id, id AS item_id, product_id, image_version, status,
                   external_image_id, result_message, pending_delete_image_id, cleanup_error
            FROM image_sync_task_item
            WHERE task_id = #{taskId} AND product_id > #{afterProductId}
            ORDER BY product_id ASC
            LIMIT #{limit}
            """)
    java.util.List<ImageSyncResultVO> selectTaskItems(
            @Param("taskId") long taskId,
            @Param("afterProductId") long afterProductId,
            @Param("limit") int limit
    );


    record ProductSnapshot(
            long productId,
            String productType,
            String imageUrl,
            long imageVersion,
            Long mappedVersion,
            String mappedExternalImageId
    ) {
        public boolean hasCurrentMapping() {
            return mappedVersion != null && mappedVersion == imageVersion;
        }
    }

    @Getter
    @Setter
    @RequiredArgsConstructor
    class TaskInsert {
        private Long id;
        private final String productType;
        private final String status;
    }

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
