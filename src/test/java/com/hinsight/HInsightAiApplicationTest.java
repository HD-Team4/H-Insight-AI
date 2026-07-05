package com.hinsight;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HInsightAiApplicationTest {

    @Test
    void applicationClassExists() {
        assertNotNull(HInsightAiApplication.class);
    }

    @Test
    void applicationConfigUsesGeminiApiKeyEnvironmentVariable() throws Exception {
        String config = Files.readString(Path.of("src/main/resources/application.yml"));

        assertTrue(config.contains("google:"));
        assertTrue(config.contains("genai:"));
        assertTrue(config.contains("api-key: ${GEMINI_API_KEY:}"));
    }
}
