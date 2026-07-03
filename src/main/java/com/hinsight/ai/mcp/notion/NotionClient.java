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

        appendBlocks(pageId, children);
    }

    public void appendBlocks(String pageId, List<?> blocks) {
        restClient.patch()
                .uri("/blocks/{pageId}/children", pageId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("children", blocks))
                .retrieve()
                .toBodilessEntity();
    }

    // 토글 블록을 추가하고 생성된 id 를 반환. 본문/표는 이 id 를 부모로 별도 append 한다.
    // (노션 append 는 요청당 2단계 중첩까지만 허용)
    public String appendToggle(String pageId, String title) {
        Map<String, Object> toggle = Map.of(
                "type", "toggle",
                "toggle", Map.of("rich_text", List.of(
                        Map.of("type", "text", "text", Map.of("content", title)))));

        JsonNode res = restClient.patch()
                .uri("/blocks/{pageId}/children", pageId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("children", List.of(toggle)))
                .retrieve()
                .body(JsonNode.class);

        String id = (res == null) ? null : res.path("results").path(0).path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("노션 toggle 생성 응답에 id 가 없습니다: " + res);
        }
        return id;
    }

    // 페이지의 to-do 블록을 [{text, checked}] 로 읽어온다.
    public List<Map<String, Object>> getTodos(String pageId) {
        JsonNode res = restClient.get()
                .uri("/blocks/{pageId}/children?page_size=100", pageId)
                .retrieve()
                .body(JsonNode.class);

        List<Map<String, Object>> todos = new ArrayList<>();
        if (res == null || res.get("results") == null) return todos;
        for (JsonNode block : res.get("results")) {
            if (!"to_do".equals(text(block.get("type")))) continue;
            JsonNode todo = block.get("to_do");
            todos.add(Map.of(
                    "text", plainText(todo.get("rich_text")),
                    "checked", todo.path("checked").asBoolean(false)
            ));
        }
        return todos;
    }

    // 페이지에 달린 코멘트를 [{text, createdTime}] 로 읽어온다.
    public List<Map<String, Object>> getComments(String pageId) {
        JsonNode res = restClient.get()
                .uri("/comments?block_id={pageId}&page_size=100", pageId)
                .retrieve()
                .body(JsonNode.class);

        List<Map<String, Object>> comments = new ArrayList<>();
        if (res == null || res.get("results") == null) return comments;
        for (JsonNode c : res.get("results")) {
            comments.add(Map.of(
                    "text", plainText(c.get("rich_text")),
                    "createdTime", text(c.get("created_time"))
            ));
        }
        return comments;
    }

    private String plainText(JsonNode richText) {
        if (richText == null || !richText.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode t : richText) {
            sb.append(t.path("plain_text").asText(""));
        }
        return sb.toString();
    }

    private String text(JsonNode node) {
        return node == null ? "" : node.asText("");
    }
}
