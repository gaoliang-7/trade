package com.tradeexample.imagesync.client;

import com.tradeexample.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

/** 调用外部图库的上传和删除接口，检查返回的业务结果。 */
@Component
public class ExternalImageClient {

    private final RestClient client;
    private final String uploadUrl;
    private final String deleteUrl;

    public ExternalImageClient(
            RestClient.Builder builder,
            @Value("${tradeexample.image-sync.upload-url}") String uploadUrl,
            @Value("${tradeexample.image-sync.delete-url}") String deleteUrl) {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(5000);
        this.client = builder.requestFactory(factory).build();
        this.uploadUrl = uploadUrl;
        this.deleteUrl = deleteUrl;
    }

    public String upload(String imageUrl, String idempotencyKey) {
        ApiResponse<UploadData> response = client.post()
                .uri(uploadUrl)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("imageUrl", imageUrl))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<UploadData>>() {});

        if (response == null || !"SUCCESS".equals(response.code()) || response.data() == null) {
            throw new RestClientException("外部图库未返回有效的上传回执");
        }

        String externalImageId = response.data().externalImageId();
        if (externalImageId == null || externalImageId.isBlank()
                || !externalImageId.matches("[\\x20-\\x7E]{1,128}")) {
            throw new RestClientException("外部图片ID不符合存储要求");
        }
        return externalImageId;
    }

    public void delete(String externalImageId) {
        ApiResponse<?> response = client.delete()
                .uri(deleteUrl, externalImageId)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<?>>() {});

        if (response == null || !"SUCCESS".equals(response.code())) {
            throw new RestClientException("外部图库未确认删除");
        }
    }

    private record UploadData(String externalImageId) {
    }
}