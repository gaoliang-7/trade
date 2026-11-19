package com.tradeexample.imagesync.mock;

import com.tradeexample.common.api.ApiResponse;
import com.tradeexample.common.exception.BusinessException;
import com.tradeexample.common.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 本地模拟图库，图片和上传回执都保存在当前进程内。 */
@Profile("local")
@RestController
@RequestMapping("/api/v1/mock/external-images")
public class MockExternalImageController {

    private final Map<String, String> uploadedImages = new HashMap<>();
    private final Map<String, UploadReceipt> uploadReceipts = new HashMap<>();

    /** 同键同图复用第一次的外部 ID，同键换图会拒绝。 */
    @PostMapping
    public synchronized ApiResponse<UploadImageResponse> upload(
            @Valid @RequestBody UploadImageRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false)
            @Pattern(regexp = "\\S{1,128}", message = "幂等键必须为1到128个非空白字符")
            String idempotencyKey,
            @RequestParam(name = "fail", defaultValue = "false") boolean fail) {

        if (fail) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "模拟上传失败");
        }

        String imageUrl = request.imageUrl().strip();
        UploadReceipt receipt = uploadReceipts.get(idempotencyKey);
        if (receipt != null) {
            if (!receipt.imageUrl().equals(imageUrl)) {
                throw new BusinessException(ErrorCode.BUSINESS_CONFLICT, "同一个幂等键只能上传同一张图");
            }
            return ApiResponse.success(new UploadImageResponse(receipt.externalImageId()));
        }

        String externalImageId = UUID.randomUUID().toString();
        uploadedImages.put(externalImageId, imageUrl);
        if (idempotencyKey != null) {
            uploadReceipts.put(idempotencyKey, new UploadReceipt(imageUrl, externalImageId));
        }
        return ApiResponse.success(new UploadImageResponse(externalImageId));
    }

    /** 图片已经不存在也算删除成功。保留上传回执，防止旧请求重新建图。 */
    @DeleteMapping("/{externalImageId}")
    public synchronized ApiResponse<DeleteImageResponse> delete(
            @PathVariable("externalImageId") String externalImageId,
            @RequestParam(name = "fail", defaultValue = "false") boolean fail) {

        if (fail) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "模拟删除失败");
        }

        boolean removed = uploadedImages.remove(externalImageId) != null;
        return ApiResponse.success(new DeleteImageResponse(removed));
    }

    public record UploadImageRequest(
            @NotBlank(message = "图片地址不能为空")
            @Size(max = 1024, message = "图片地址不能超过1024个字符")
            String imageUrl
    ) {
    }

    public record UploadImageResponse(String externalImageId) {
    }

    public record DeleteImageResponse(boolean removed) {
    }

    private record UploadReceipt(String imageUrl, String externalImageId) {
    }
}