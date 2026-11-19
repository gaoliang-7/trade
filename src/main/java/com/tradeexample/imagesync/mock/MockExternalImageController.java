package com.tradeexample.imagesync.mock;

import com.tradeexample.common.api.ApiResponse;
import com.tradeexample.common.exception.BusinessException;
import com.tradeexample.common.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 本地模拟外部图库，用内存记录已上传的图片。 */
@Profile("local")
@RestController
@RequestMapping("/api/v1/mock/external-images")
public class MockExternalImageController {

    private final Map<String, String> uploadedImages = new ConcurrentHashMap<>();

    /** 模拟接收图片地址并分配外部 ID，故障发生在保存之前。 */
    @PostMapping
    public ApiResponse<UploadImageResponse> upload(
            @Valid @RequestBody UploadImageRequest request,
            @RequestParam(name = "fail", defaultValue = "false") boolean fail) {

        if (fail) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "模拟上传失败");
        }

        String externalImageId = UUID.randomUUID().toString();
        uploadedImages.put(externalImageId, request.imageUrl().strip());
        return ApiResponse.success(new UploadImageResponse(externalImageId));
    }

    /** 按外部 ID 删除图片，已经不存在时也返回成功。 */
    @DeleteMapping("/{externalImageId}")
    public ApiResponse<DeleteImageResponse> delete(
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
}