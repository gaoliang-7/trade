package com.tradeexample.common.controller;

import com.tradeexample.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final String applicationName;

    public HealthController(
            @Value("${spring.application.name}") String applicationName) {
        this.applicationName = applicationName;
    }

    /** 返回应用名称和存活状态，不访问外部依赖。 */
    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {

        Map<String, String> data = Map.of(
                "application", applicationName,
                "status", "UP"
        );

        return ApiResponse.success(data);
    }
}