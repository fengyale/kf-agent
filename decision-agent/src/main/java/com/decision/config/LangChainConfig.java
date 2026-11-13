package com.decision.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(AgentProperties.class)
public class LangChainConfig {

    @Bean
    public ChatModel chatModel(AgentProperties props) {
        AgentProperties.Llm cfg = props.getLlm();
        return switch (cfg.getProvider()) {
            case "ollama" -> OllamaChatModel.builder()
                    .baseUrl(cfg.getOllama().getBaseUrl())
                    .modelName(cfg.getOllama().getModel())
                    .temperature(cfg.getOllama().getTemperature())
                    .timeout(Duration.ofMinutes(5))
                    .build();
            case "deepseek" -> OpenAiChatModel.builder()
                    .baseUrl(cfg.getDeepseek().getBaseUrl())
                    .apiKey(cfg.getDeepseek().getApiKey())
                    .modelName(cfg.getDeepseek().getModel())
                    .temperature(cfg.getDeepseek().getTemperature())
                    .responseFormat("json_object")
                    .timeout(Duration.ofMinutes(3))
                    .build();
            default -> OpenAiChatModel.builder()
                    .baseUrl(cfg.getOpenaiCompatible().getBaseUrl())
                    .apiKey(cfg.getOpenaiCompatible().getApiKey())
                    .modelName(cfg.getOpenaiCompatible().getModel())
                    .temperature(cfg.getOpenaiCompatible().getTemperature())
                    .responseFormat("json_object")
                    .timeout(Duration.ofMinutes(3))
                    .build();
        };
    }

    @Bean
    public StreamingChatModel streamingChatModel(AgentProperties props) {
        AgentProperties.Llm cfg = props.getLlm();
        return switch (cfg.getProvider()) {
            case "ollama" -> OllamaStreamingChatModel.builder()
                    .baseUrl(cfg.getOllama().getBaseUrl())
                    .modelName(cfg.getOllama().getModel())
                    .temperature(cfg.getOllama().getTemperature())
                    .timeout(Duration.ofMinutes(5))
                    .build();
            case "deepseek" -> OpenAiStreamingChatModel.builder()
                    .baseUrl(cfg.getDeepseek().getBaseUrl())
                    .apiKey(cfg.getDeepseek().getApiKey())
                    .modelName(cfg.getDeepseek().getModel())
                    .temperature(cfg.getDeepseek().getTemperature())
                    .returnThinking(true)
                    .timeout(Duration.ofMinutes(5))
                    .build();
            default -> OpenAiStreamingChatModel.builder()
                    .baseUrl(cfg.getOpenaiCompatible().getBaseUrl())
                    .apiKey(cfg.getOpenaiCompatible().getApiKey())
                    .modelName(cfg.getOpenaiCompatible().getModel())
                    .temperature(cfg.getOpenaiCompatible().getTemperature())
                    .returnThinking(true)
                    .timeout(Duration.ofMinutes(5))
                    .build();
        };
    }
}
