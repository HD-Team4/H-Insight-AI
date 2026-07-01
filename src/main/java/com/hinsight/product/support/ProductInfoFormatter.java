package com.hinsight.product.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ProductInfoFormatter {

    private final ObjectMapper objectMapper;

    public Map<String, String> toRows(String productInfo) {
        Map<String, String> rows = new LinkedHashMap<>();
        if (productInfo == null || productInfo.isBlank()) {
            return rows;
        }

        try {
            JsonNode root = objectMapper.readTree(productInfo);
            if (!root.isObject()) {
                rows.put("제품 정보", productInfo);
                return rows;
            }

            root.fieldNames().forEachRemaining(fieldName -> {
                rows.put(fieldName, toDisplayText(root.get(fieldName)));
            });
        } catch (JsonProcessingException e) {
            rows.put("제품 정보", productInfo);
        }
        return rows;
    }

    private String toDisplayText(JsonNode value) {
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isValueNode()) {
            return value.asText();
        }
        return value.toString();
    }
}
