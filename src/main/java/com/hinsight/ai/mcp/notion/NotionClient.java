package com.hinsight.ai.mcp.notion;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class NotionClient {

    private final NotionProperties props;
    private final RestClient restClient;

    public NotionClient(NotionProperties props, RestClient.Builder builder) {
        this.props = props;
        this.restClient = builder
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + (props.getToken() == null ? "" : props.getToken()))
                .defaultHeader("Notion-Version", props.getVersion())
                .build();
    }


    public void attachImageToPage(String pageId, String heading, byte[] imageBytes, String filename, String contentType) {
        String fileUploadId = createFileUpload(filename, contentType);
        sendFileContents(fileUploadId, imageBytes, filename, contentType);
        appendImageBlock(pageId, heading, fileUploadId);
        log.info("노션 이미지 첨부 완료: page={}, fileUpload={}", pageId, fileUploadId);
    }

    private String createFileUpload(String filename, String contentType) {
        JsonNode res = restClient.post()
                .uri("/file_uploads")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("filename", filename, "content_type", contentType))
                .retrieve()
                .body(JsonNode.class);

        if (res == null || res.get("id") == null) {
            throw new IllegalStateException("노션 file_upload 생성 응답에 id 가 없습니다: " + res);
        }
        return res.get("id").asText();
    }

    private void sendFileContents(String fileUploadId, byte[] imageBytes, String filename, String contentType) {
        ByteArrayResource filePart = new ByteArrayResource(imageBytes) {
            @Override
            public String getFilename() {
                return filename; // multipart 파일명
            }
        };

        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", filePart);

        restClient.post()
                .uri("/file_uploads/{id}/send", fileUploadId)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .toBodilessEntity();
    }

    private void appendImageBlock(String pageId, String heading, String fileUploadId) {
        Map<String, Object> imageBlock = Map.of(
                "type", "image",
                "image", Map.of(
                        "type", "file_upload",
                        "file_upload", Map.of("id", fileUploadId)
                )
        );

        List<Object> children = new ArrayList<>();
        if (heading != null && !heading.isBlank()) {
            children.add(Map.of(
                    "type", "heading_2",
                    "heading_2", Map.of(
                            "rich_text", List.of(Map.of(
                                    "type", "text",
                                    "text", Map.of("content", heading)
                            ))
                    )
            ));
        }
        children.add(imageBlock);

        restClient.patch()
                .uri("/blocks/{pageId}/children", pageId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("children", children))
                .retrieve()
                .toBodilessEntity();
    }
}
