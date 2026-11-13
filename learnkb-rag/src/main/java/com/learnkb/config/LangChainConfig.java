package com.learnkb.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(KbProperties.class)
public class LangChainConfig {

    @Bean
    public EmbeddingModel embeddingModel(KbProperties props) {
        KbProperties.Embedding cfg = props.getEmbedding();
        if ("ollama".equals(cfg.getProvider())) {
            return OllamaEmbeddingModel.builder()
                    .baseUrl(cfg.getOllama().getBaseUrl())
                    .modelName(cfg.getOllama().getModel())
                    .timeout(Duration.ofMinutes(3))
                    .build();
        }
        KbProperties.Embedding.OpenaiCompatible oc = cfg.getOpenaiCompatible();
        return OpenAiEmbeddingModel.builder()
                .baseUrl(oc.getBaseUrl())
                .apiKey(oc.getApiKey())
                .modelName(oc.getModel())
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    public ChatModel chatModel(KbProperties props) {
        KbProperties.Chat cfg = props.getChat();
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
                    .timeout(Duration.ofMinutes(2))
                    .build();
            default -> OpenAiChatModel.builder()
                    .baseUrl(cfg.getOpenaiCompatible().getBaseUrl())
                    .apiKey(cfg.getOpenaiCompatible().getApiKey())
                    .modelName(cfg.getOpenaiCompatible().getModel())
                    .temperature(cfg.getOpenaiCompatible().getTemperature())
                    .timeout(Duration.ofMinutes(2))
                    .build();
        };
    }

    @Bean
    public StreamingChatModel streamingChatModel(KbProperties props) {
        KbProperties.Chat cfg = props.getChat();
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
                    .timeout(Duration.ofMinutes(2))
                    .build();
            default -> OpenAiStreamingChatModel.builder()
                    .baseUrl(cfg.getOpenaiCompatible().getBaseUrl())
                    .apiKey(cfg.getOpenaiCompatible().getApiKey())
                    .modelName(cfg.getOpenaiCompatible().getModel())
                    .temperature(cfg.getOpenaiCompatible().getTemperature())
                    .timeout(Duration.ofMinutes(2))
                    .build();
        };
    }
}
