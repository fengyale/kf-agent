package com.learnkb.service;

import com.learnkb.config.KbProperties;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final KbProperties props;
    private final EmbeddingModel embeddingModel;
    private volatile EmbeddingStore<TextSegment> embeddingStore = new InMemoryEmbeddingStore<>();
    private volatile int segmentCount = 0;

    public IngestService(KbProperties props, EmbeddingModel embeddingModel) {
        this.props = props;
        this.embeddingModel = embeddingModel;
    }

    public synchronized IngestResult ingest() {
        long start = System.currentTimeMillis();
        Path corpusDir = Paths.get(props.getCorpusDir());
        if (!Files.isDirectory(corpusDir)) {
            throw new IllegalStateException("语料目录不存在: " + props.getCorpusDir());
        }

        List<Path> files;
        try (Stream<Path> paths = Files.walk(corpusDir)) {
            files = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".md") || name.endsWith(".txt");
                    })
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("扫描语料目录失败: " + e.getMessage(), e);
        }

        DocumentParser parser = new TextDocumentParser();
        DocumentSplitter splitter = DocumentSplitters.recursive(
                props.getChunkSize(), props.getChunkOverlap());

        Map<String, Integer> perFileChunks = new LinkedHashMap<>();
        EmbeddingStore<TextSegment> freshStore = new InMemoryEmbeddingStore<>();
        int totalSegments = 0;

        for (Path file : files) {
            Document doc;
            try {
                doc = FileSystemDocumentLoader.loadDocument(file, parser);
            } catch (Exception e) {
                log.debug("跳过无法解析的文件 {}: {}", file.getFileName(), e.getMessage());
                perFileChunks.put(file.getFileName().toString(), 0);
                continue;
            }
            if (doc.text() == null || doc.text().isBlank()) {
                perFileChunks.put(file.getFileName().toString(), 0);
                continue;
            }
            List<TextSegment> segments = splitter.split(doc);
            if (segments.isEmpty()) {
                perFileChunks.put(file.getFileName().toString(), 0);
                continue;
            }
            List<TextSegment> kept = new ArrayList<>(segments.size());
            List<Embedding> embeddings = new ArrayList<>(segments.size());
            int batch = 32;
            for (int i = 0; i < segments.size(); i += batch) {
                embedBatchSafe(segments.subList(i, Math.min(i + batch, segments.size())), kept, embeddings);
            }
            freshStore.addAll(embeddings, kept);
            perFileChunks.put(file.getFileName().toString(), kept.size());
            totalSegments += kept.size();
        }

        this.embeddingStore = freshStore;
        this.segmentCount = totalSegments;
        persist(freshStore);

        long cost = System.currentTimeMillis() - start;
        log.info("索引完成: {} 个文件, {} 个文本块, 耗时 {} ms", files.size(), totalSegments, cost);
        return new IngestResult(files.size(), totalSegments, cost, perFileChunks);
    }

    // bge GGUF 对个别超短文本会让 Ollama runner 崩溃（特定 token 序列触发，无法预判），
    // 批量失败时降级为逐条定位，丢弃坏块
    private void embedBatchSafe(List<TextSegment> batch, List<TextSegment> kept, List<Embedding> out) {
        try {
            out.addAll(embeddingModel.embedAll(batch).content());
            kept.addAll(batch);
            return;
        } catch (RuntimeException e) {
            log.warn("批量向量化失败({}条)，切换逐条定位: {}", batch.size(), e.getMessage());
        }
        for (TextSegment seg : batch) {
            try {
                out.add(embeddingModel.embed(seg).content());
                kept.add(seg);
            } catch (RuntimeException e) {
                String preview = seg.text().length() > 50 ? seg.text().substring(0, 50) + "..." : seg.text();
                log.warn("丢弃无法向量化的文本块: [{}]", preview);
            }
        }
    }

    public List<EmbeddingMatch<TextSegment>> search(String query) {
        return embeddingStore.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(embeddingModel.embed(query).content())
                .maxResults(props.getTopK())
                .minScore(0.0)
                .build()).matches();
    }

    public boolean isIndexed() {
        return segmentCount > 0;
    }

    public int getSegmentCount() {
        return segmentCount;
    }

    public synchronized void loadPersisted() {
        Path file = Paths.get(props.getStoreFile());
        if (!Files.exists(file)) {
            log.info("未找到持久化向量库 {}，跳过载入", file.toAbsolutePath());
            return;
        }
        try {
            this.embeddingStore = InMemoryEmbeddingStore.fromFile(file.toAbsolutePath().toString());
            this.segmentCount = readCountFile();
            if (segmentCount >= 0) {
                log.info("从 {} 载入向量库: {} 个文本块", file.toAbsolutePath(), segmentCount);
            } else {
                log.info("已载入向量库 {}，块数量未知，建议重建索引", file.toAbsolutePath());
            }
        } catch (Exception e) {
            log.warn("载入持久化向量库失败（可重新建立索引）: {}", e.getMessage());
            this.segmentCount = 0;
        }
    }

    private int readCountFile() {
        try {
            Path countFile = Paths.get(props.getStoreFile() + ".count");
            if (Files.exists(countFile)) {
                return Integer.parseInt(Files.readString(countFile).trim());
            }
        } catch (Exception e) {
            log.debug("读取计数文件失败: {}", e.getMessage());
        }
        return -1;
    }

    private void writeCountFile() {
        try {
            Path countFile = Paths.get(props.getStoreFile() + ".count");
            Files.createDirectories(countFile.toAbsolutePath().getParent());
            Files.writeString(countFile, String.valueOf(segmentCount));
        } catch (IOException e) {
            log.debug("写入计数文件失败: {}", e.getMessage());
        }
    }

    private void persist(EmbeddingStore<TextSegment> store) {
        if (store instanceof InMemoryEmbeddingStore<TextSegment> inMemory) {
            try {
                Path file = Paths.get(props.getStoreFile());
                Files.createDirectories(file.toAbsolutePath().getParent());
                inMemory.serializeToFile(file.toAbsolutePath().toString());
                writeCountFile();
                log.info("向量库已持久化到 {}", file.toAbsolutePath());
            } catch (IOException e) {
                log.warn("向量库持久化失败: {}", e.getMessage());
            }
        }
    }

    public record IngestResult(int fileCount, int segmentCount, long costMs, Map<String, Integer> perFileChunks) {
    }
}
