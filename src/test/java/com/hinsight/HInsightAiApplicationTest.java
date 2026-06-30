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
    void aiSecretConfigUsesGeminiApiKey() throws Exception {
        String config = Files.readString(Path.of("src/main/resources/application-secret.yml"));

        assertTrue(config.contains("google:"));
        assertTrue(config.contains("genai:"));
        assertTrue(config.contains("api-key: ${GEMINI_API_KEY:}"));
    }
}
