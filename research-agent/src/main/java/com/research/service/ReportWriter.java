package com.research.service;

import com.research.config.AgentProperties;
import com.research.model.SearchModels.SearchItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ReportWriter {

    private static final Logger log = LoggerFactory.getLogger(ReportWriter.class);
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final DateTimeFormatter DISPLAY_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AgentProperties props;
    private final TaskStore store;

    public ReportWriter(AgentProperties props, TaskStore store) {
        this.props = props;
        this.store = store;
    }

    public Path write(String taskId, String topic, String markdown, List<SearchItem> pool,
                      int rounds, int searchCount, long totalCostMs) {
        String confidence = confidenceOf(pool);
        String header = "> 来源：research-agent 自动调研 · 生成时间：" + LocalDateTime.now().format(DISPLAY_TS)
                + " · 主题：" + topic
                + " · 置信度：" + confidence
                + "（依据：" + pool.size() + " 条来源 / " + distinctSites(pool) + " 个独立站点）"
                + " · 调研轮数：" + rounds
                + " · 搜索：" + searchCount + " 次\n\n";

        Path dir = Path.of(props.getReport().getOutputDir());
        Path file = dir.resolve(LocalDateTime.now().format(FILE_TS) + "-" + simplify(topic) + ".md");

        // 先入库（监控页/历史页始终可见），再落盘；落盘失败降级到 data/reports，避免全链路失败
        store.saveReport(taskId, extractTitle(markdown), markdown);
        try {
            Files.createDirectories(dir);
            Files.writeString(file, header + markdown, StandardCharsets.UTF_8);
            log.info("报告已落盘: {}", file);
            return file;
        } catch (IOException e) {
            log.warn("目标目录落盘失败({})，降级写入 data/reports", e.getMessage());
            Path fallbackDir = Path.of("data", "reports");
            try {
                Files.createDirectories(fallbackDir);
                Path fallbackFile = fallbackDir.resolve(file.getFileName());
                Files.writeString(fallbackFile, header + markdown, StandardCharsets.UTF_8);
                log.info("报告已降级落盘: {}", fallbackFile);
                return fallbackFile;
            } catch (IOException e2) {
                throw new IllegalStateException("报告落盘失败(含降级): " + e2.getMessage(), e2);
            }
        }
    }

    private String confidenceOf(List<SearchItem> pool) {
        if (pool.size() >= 10 && distinctSites(pool) >= 6) return "高";
        if (pool.size() >= 5) return "中";
        return "低";
    }

    private long distinctSites(List<SearchItem> pool) {
        Set<String> sites = pool.stream()
                .map(i -> i.siteName() == null ? "" : i.siteName().strip().toLowerCase())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        return sites.isEmpty() ? 1 : sites.size();
    }

    private String extractTitle(String markdown) {
        for (String line : markdown.split("\n", 3)) {
            String t = line.strip();
            if (t.startsWith("# ")) return t.substring(2).strip();
        }
        return "调研报告";
    }

    private String simplify(String topic) {
        String s = topic.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t ]", "");
        return s.length() > 30 ? s.substring(0, 30) : s;
    }
}
