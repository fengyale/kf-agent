package com.decision.export;

import com.decision.loop.AnalysisStateStore;
import com.decision.loop.AnalysisStore;
import com.decision.model.LoopModels.AssessLightOutput;
import com.decision.model.LoopModels.AssessOutput;
import com.decision.model.LoopModels.GapOutput;
import com.decision.model.LoopModels.PathOutput;
import com.decision.model.LoopModels.RedTeamMeta;
import com.decision.model.LoopModels.RiskOutput;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 导出统一入口（spec Requirement: 产出物五形态，Task 11.3）：
 * - md   ：report.markdown + ReportWriter 元信息头（动态重生成，与落盘文件同构）
 * - xlsx ：POI 四 sheet（三档对比/里程碑计划/周投入排期/风险清单）
 * - pdf  ：openhtmltopdf 从报告 HTML 直转（浅色打印风）
 * - docx ：XWPF 段落 + 表格
 * - bundle：zip 打包 MD + xlsx + pdf + docx 四件（java.util.zip）
 * 数据源：report 表 markdown + analysis_state.steps_output 结构化产物（assess/path/gap/risk/redteam）。
 */
@Service
public class ExportService {

    private static final Logger log = LoggerFactory.getLogger(ExportService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm");

    private final AnalysisStore analysisStore;
    private final AnalysisStateStore stateStore;
    private final ReportWriter reportWriter;
    private final ExcelExporter excel;
    private final PdfExporter pdf;
    private final WordExporter word;
    private final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public ExportService(AnalysisStore analysisStore, AnalysisStateStore stateStore,
                         ReportWriter reportWriter, ExcelExporter excel,
                         PdfExporter pdf, WordExporter word) {
        this.analysisStore = analysisStore;
        this.stateStore = stateStore;
        this.reportWriter = reportWriter;
        this.excel = excel;
        this.pdf = pdf;
        this.word = word;
    }

    // ==================== 数据装载 ====================

    /** 从 analysis + report + analysis_state.steps_output 组装导出数据；无报告抛异常 */
    public ExportData load(String analysisId) {
        Map<String, Object> analysis = analysisStore.get(analysisId);
        if (analysis == null) {
            throw new IllegalArgumentException("分析不存在: " + analysisId);
        }
        Map<String, Object> report = analysisStore.getReport(analysisId);
        if (report == null) {
            throw new IllegalArgumentException("报告尚未生成（分析可能仍在进行或已中断）: " + analysisId);
        }
        LinkedHashMap<String, Object> steps = stateStore.parseSteps(stateStore.get(analysisId));
        return new ExportData(
                analysisId,
                str(analysis.get("question")),
                str(analysis.get("type")),
                str(analysis.get("depth")),
                str(analysis.get("status")),
                str(report.get("title")),
                str(report.get("markdown")),
                str(report.get("created_at")),
                convert(steps.get("assess"), AssessOutput.class),
                convert(steps.get("assess-light"), AssessLightOutput.class),
                convert(steps.get("path"), PathOutput.class),
                convert(steps.get("gap"), GapOutput.class),
                convert(steps.get("risk"), RiskOutput.class),
                convert(steps.get("redteam"), RedTeamMeta.class));
    }

    private <T> T convert(Object o, Class<T> type) {
        if (o == null) {
            return null;
        }
        if (type.isInstance(o)) {
            return type.cast(o);
        }
        try {
            return om.convertValue(o, type);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 四格式导出 ====================

    /** MD：report.markdown + 元信息头（与 ReportWriter 落盘文件同构） */
    public byte[] md(String analysisId) {
        ExportData data = load(analysisId);
        String header = reportWriter.buildHeader(data.question(), data.type(), data.depth());
        return (header + (data.markdown() == null ? "" : data.markdown()))
                .getBytes(StandardCharsets.UTF_8);
    }

    public byte[] xlsx(String analysisId) {
        return excel.export(load(analysisId));
    }

    public byte[] pdf(String analysisId) {
        return pdf.export(load(analysisId));
    }

    public byte[] docx(String analysisId) {
        return word.export(load(analysisId));
    }

    /** zip 打包：MD + xlsx + pdf + docx 四件（java.util.zip） */
    public byte[] bundle(String analysisId) {
        ExportData data = load(analysisId);
        String base = fileNameBase(data);
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            zos.putNextEntry(new ZipEntry(base + ".md"));
            zos.write(md(analysisId));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry(base + ".xlsx"));
            zos.write(xlsx(analysisId));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry(base + ".pdf"));
            zos.write(pdf(analysisId));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry(base + ".docx"));
            zos.write(docx(analysisId));
            zos.closeEntry();
            zos.finish();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("zip 打包失败: " + e.getMessage(), e);
        }
    }

    // ==================== 文件名 ====================

    /** 导出文件名基座：时间戳 + 问题（截 30 字符，去非法字符），zip 内四件共用 */
    public String fileNameBase(String analysisId) {
        ExportData data = load(analysisId);
        return LocalDateTime.now().format(TS) + "-" + simplify(data.question());
    }

    public String fileNameBase(ExportData data) {
        return LocalDateTime.now().format(TS) + "-" + simplify(data.question());
    }

    private static String simplify(String question) {
        String s = (question == null ? "决策分析" : question)
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t ]", "");
        if (s.length() > 30) {
            s = s.substring(0, 30);
        }
        return s.isEmpty() ? "决策分析" : s;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
