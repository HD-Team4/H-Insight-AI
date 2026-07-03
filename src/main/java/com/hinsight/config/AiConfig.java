package com.hinsight.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AI 설정. google-genai 스타터가 자동 구성한 ChatModel(Gemini) 위에
 * 편의용 ChatClient 를 노출한다 (챗봇 질의 조건추출에 사용).
 */
@Configuration
public class AiConfig {

    @Bean   
    public ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.create(chatModel);
    }
}
