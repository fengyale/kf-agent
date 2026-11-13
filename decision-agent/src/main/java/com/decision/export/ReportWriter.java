package com.decision.export;

import com.decision.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 报告 Markdown 双级落盘（spec Requirement: 产出物五形态 / 启动与配置，Task 11.1）：
 * 目标目录 agent.report.outputDir（F:\learn\ai转型计划个人学习\decision-agent决策分析\，learnkb 语料回流），
 * 写失败降级 data/reports——照 research-agent ReportWriter 双级模式。
 *
 * 报告头携带元信息：来源（decision-agent 可行性分析）/ 生成时间 / 类型 / 深度 / 结论摘要入口。
 * 落盘路径回写 report 表 file_path（AnalysisStore.updateReportFilePath），
 * 历史页"结论摘要"与导出 md 复用落盘文件或动态重生成。
 */
@Service
public class ReportWriter {

    private static final Logger log = LoggerFactory.getLogger(ReportWriter.class);
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");
    private static final DateTimeFormatter DISPLAY_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final AgentProperties props;

    public ReportWriter(AgentProperties props) {
        this.props = props;
    }

    /**
     * 双级落盘：先试目标目录，失败降级 data/reports；返回最终落盘路径。
     * 任何一级失败都抛 IllegalStateException（由调用方捕获，不阻断报告生成主链路）。
     */
    public Path write(String question, String type, String depth, String markdown) {
        String header = buildHeader(question, type, depth);
        String fileName = LocalDateTime.now().format(FILE_TS) + "-" + simplify(question) + ".md";

        Path dir = Path.of(props.getReport().getOutputDir());
        Path file = dir.resolve(fileName);
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
                Path fallbackFile = fallbackDir.resolve(fileName);
                Files.writeString(fallbackFile, header + markdown, StandardCharsets.UTF_8);
                log.info("报告已降级落盘: {}", fallbackFile);
                return fallbackFile;
            } catch (IOException e2) {
                throw new IllegalStateException("报告落盘失败(含降级): " + e2.getMessage(), e2);
            }
        }
    }

    /** 报告头元信息：来源 / 生成时间 / 类型 / 深度（markdown 引用行，export md 与落盘文件共用） */
    public String buildHeader(String question, String type, String depth) {
        return "> 来源：decision-agent 可行性分析 · 生成时间：" + LocalDateTime.now().format(DISPLAY_TS)
                + " · 问题：" + (question == null ? "" : question)
                + " · 类型：" + (type == null ? "—" : type)
                + " · 深度：" + (depth == null ? "—" : depth) + "\n\n";
    }

    /** 文件名安全化：去除非法字符与空白，截 30 字 */
    private String simplify(String question) {
        String s = (question == null ? "决策分析" : question)
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t ]", "");
        return s.length() > 30 ? s.substring(0, 30) : (s.isEmpty() ? "决策分析" : s);
    }
}