package com.learnkb.service;

import com.learnkb.config.KbProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private static final PromptTemplate RAG_PROMPT = PromptTemplate.from("""
            你是学习知识库助手，基于下面提供的参考资料回答问题。

            要求：
            1. 只使用参考资料中的信息回答，不要编造
            2. 回答用中文，条理清晰，可用 Markdown 格式
            3. 如果参考资料不足以回答，明确说明"知识库中暂无相关内容"
            4. 在回答末尾列出引用的来源文件名

            参考资料：
            {{context}}

            问题：{{question}}
            """);

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final IngestService ingestService;
    private final KbProperties props;

    public ChatService(ChatModel chatModel,
                       StreamingChatModel streamingChatModel,
                       IngestService ingestService,
                       KbProperties props) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.ingestService = ingestService;
        this.props = props;
    }

    public ChatResult chat(String question) {
        if (!ingestService.isIndexed()) {
            return new ChatResult("知识库尚未建立索引，请先调用 POST /api/ingest 建立索引。", List.of());
        }

        List<EmbeddingMatch<TextSegment>> matches = ingestService.search(question);
        if (matches.isEmpty()) {
            return new ChatResult("知识库中没有检索到与该问题相关的内容，换个问法试试。", List.of());
        }

        Prompt prompt = buildPrompt(question, matches);

        long start = System.currentTimeMillis();
        String answer = chatModel.chat(prompt.text());
        long cost = System.currentTimeMillis() - start;

        List<String> sources = extractSources(matches);
        log.info("问答完成: 问题[{}] 命中{}条 耗时{}ms", question, matches.size(), cost);
        return new ChatResult(answer, sources);
    }

    public void chatStream(String question, Consumer<String> onThinking, Consumer<String> onToken, Consumer<ChatResult> onComplete) {
        if (!ingestService.isIndexed()) {
            onComplete.accept(new ChatResult("知识库尚未建立索引，请先建立索引。", List.of()));
            return;
        }

        List<EmbeddingMatch<TextSegment>> matches = ingestService.search(question);
        if (matches.isEmpty()) {
            onComplete.accept(new ChatResult("知识库中没有检索到与该问题相关的内容，换个问法试试。", List.of()));
            return;
        }

        Prompt prompt = buildPrompt(question, matches);
        List<String> sources = extractSources(matches);
        long start = System.currentTimeMillis();

        streamingChatModel.chat(prompt.text(), new dev.langchain4j.model.chat.response.StreamingChatResponseHandler() {
            final StringBuilder sb = new StringBuilder();
            final StringBuilder thinking = new StringBuilder();

            @Override
            public void onPartialResponse(String token) {
                sb.append(token);
                onToken.accept(token);
            }

            @Override
            public void onPartialThinking(dev.langchain4j.model.chat.response.PartialThinking partialThinking) {
                thinking.append(partialThinking.text());
                onThinking.accept(partialThinking.text());
            }

            @Override
            public void onCompleteResponse(dev.langchain4j.model.chat.response.ChatResponse response) {
                log.info("流式问答完成: 问题[{}] 命中{}条 思考{}字 回答{}字 耗时{}ms",
                        question, matches.size(), thinking.length(), sb.length(), System.currentTimeMillis() - start);
                onComplete.accept(new ChatResult(sb.toString(), sources));
            }

            @Override
            public void onError(Throwable error) {
                log.warn("流式问答失败: {}", error.getMessage());
                // 流式中断：已收到的部分仍有价值，连同错误标记一起返回
                onComplete.accept(new ChatResult(sb.length() > 0
                        ? sb.toString() + "\n\n（⚠ 生成中断：" + error.getMessage() + "）"
                        : "生成失败：" + error.getMessage(), sources));
            }
        });
    }

    private Prompt buildPrompt(String question, List<EmbeddingMatch<TextSegment>> matches) {
        StringBuilder context = new StringBuilder();
        int idx = 1;
        for (EmbeddingMatch<TextSegment> match : matches) {
            String fileName = match.embedded().metadata().getString("file_name");
            context.append("--- 片段 ").append(idx++).append("（来源: ")
                    .append(fileName == null ? "unknown" : fileName)
                    .append("，相似度 ").append(String.format("%.3f", match.score())).append("） ---\n")
                    .append(match.embedded().text().trim())
                    .append("\n\n");
        }
        Map<String, Object> vars = new HashMap<>();
        vars.put("context", context.toString());
        vars.put("question", question);
        return RAG_PROMPT.apply(vars);
    }

    private List<String> extractSources(List<EmbeddingMatch<TextSegment>> matches) {
        return matches.stream()
                .map(m -> m.embedded().metadata().getString("file_name"))
                .distinct()
                .toList();
    }

    public record ChatResult(String answer, List<String> sources) {
    }
}
