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

/** 调用外部图库的上传、回执查询和删除接口。 */
@Component
public class ExternalImageClient {

    private final RestClient client;
    private final String uploadUrl;
    private final String receiptUrl;
    private final String deleteUrl;

    /** 设置每次外部请求的连接和读取超时。 */
    public ExternalImageClient(
            RestClient.Builder builder,
            @Value("${tradeexample.image-sync.upload-url}") String uploadUrl,
            @Value("${tradeexample.image-sync.receipt-url}") String receiptUrl,
            @Value("${tradeexample.image-sync.delete-url}") String deleteUrl,
            @Value("${tradeexample.image-sync.connect-timeout-ms}") int connectTimeoutMs,
            @Value("${tradeexample.image-sync.read-timeout-ms}") int readTimeoutMs) {

        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("图片 HTTP 超时必须大于0");
        }

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.client = builder.requestFactory(factory).build();
        this.uploadUrl = uploadUrl;
        this.receiptUrl = receiptUrl;
        this.deleteUrl = deleteUrl;
    }

    /** 用原幂等键上传图片，要求外部返回有效的图片 ID。 */
    public String upload(String imageUrl, String idempotencyKey) {
        ApiResponse<UploadData> response = client.post()
                .uri(uploadUrl)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("imageUrl", imageUrl))
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<UploadData>>() {});

        String id = readReceipt(response);
        if (id == null) {
            throw new RestClientException("外部图库上传回执缺少图片ID");
        }
        return id;
    }

    /** 查询原请求的上传回执，返回 null 表示这次没有查到回执。 */
    public String findUploadedImage(String idempotencyKey) {
        ApiResponse<UploadData> response = client.get()
                .uri(receiptUrl)
                .header("Idempotency-Key", idempotencyKey)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<UploadData>>() {});

        return readReceipt(response);
    }

    /** 删除指定图片，模拟图库对已不存在的图片也确认成功。 */
    public void delete(String externalImageId) {
        ApiResponse<?> response = client.delete()
                .uri(deleteUrl, externalImageId)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<?>>() {});

        if (response == null || !"SUCCESS".equals(response.code())) {
            throw new RestClientException("外部图库未确认删除");
        }
    }

    /** 检查上传和回执查询共用的响应格式及图片 ID。 */
    private String readReceipt(ApiResponse<UploadData> response) {
        if (response == null || !"SUCCESS".equals(response.code()) || response.data() == null) {
            throw new RestClientException("外部图库未返回有效回执");
        }

        String id = response.data().externalImageId();
        if (id != null && (id.isBlank() || !id.matches("[\\x20-\\x7E]{1,128}"))) {
            throw new RestClientException("外部图片ID不符合存储要求");
        }
        return id;
    }

    /** 外部上传回执，查不到记录时图片 ID 为空。 */
    private record UploadData(String externalImageId) {
    }
}
