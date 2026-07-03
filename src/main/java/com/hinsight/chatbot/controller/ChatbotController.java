package com.hinsight.chatbot.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Tag(name = "chatbot-controller", description = "챗봇 컨트롤러")
@Controller
@RequestMapping("/customer/chatbot")
public class ChatbotController {
}
