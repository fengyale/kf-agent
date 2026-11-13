package com.decision.export;

import com.decision.config.AgentProperties;
import com.decision.loop.AnalysisStateStore;
import com.decision.loop.AnalysisStore;
import com.decision.model.LoopModels.AssessOutput;
import com.decision.model.LoopModels.DataSource;
import com.decision.model.LoopModels.RiskItem;
import com.decision.model.LoopModels.RiskOutput;
import com.decision.model.LoopModels.Scenario;
import com.decision.model.LoopModels.ScenarioMilestone;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 降级路径自测（不启动联调，F 盘沙箱限制）：
 * - ReportWriter 双级落盘：目标目录不可写（Z 盘不存在）→ 降级 data/reports 且文件可读
 * - ExcelExporter：xlsx 四 sheet（三档对比/里程碑计划/周投入排期/风险清单），POI 读回校验
 * - PdfExporter：openhtmltopdf 直转，产物 %PDF 头
 * - WordExporter：XWPF docx，产物 PK(zip) 头
 */
class ExportSelfTest {

    private static ExportData sampleData() {
        List<Scenario> scenarios = List.of(
                new Scenario("A 乐观", 15.0, 24.0, 0.65,
                        List.of(new ScenarioMilestone("M1 入门", "完成基础课程", "连续两周投入<10h"), new ScenarioMilestone("M2 实战", "完成项目", "项目延期>2周")),
                        "连续两周投入<10小时 → 降 B 档"),
                new Scenario("B 现实", 9.0, 36.0, 0.40,
                        List.of(new ScenarioMilestone("M1 入门", "完成基础课程", "连续两周投入<6h")),
                        "连续两周投入<6小时 → 降 C 档"),
                new Scenario("C 保守", 5.0, 52.0, 0.20,
                        List.of(new ScenarioMilestone("M1 慢速入门", "完成基础课程前半", "连续一月未投入")),
                        "连续一月未投入 → 暂停评估"));
        AssessOutput assess = new AssessOutput(
                "可行，按 B 现实档推进", List.of(new DataSource("调研报告《AI 转型》", "2026-08-01")),
                scenarios, List.of(), "校准说明");
        RiskOutput risk = new RiskOutput(List.of(
                new RiskItem("时间冲突", "高", "中", "固定每周二四晚投入")));
        return new ExportData("t001", "我想转 AI", "学习计划", "deep", "done",
                "可行性分析：我想转 AI", "# 可行性分析：我想转 AI\n\n## 可行性结论\n\n可行。\n\n## 三档情景\n\n### A 档 · 乐观\n- 达成概率：0.65\n",
                "2026-08-31 12:00:00", assess, null, null, null, risk, null);
    }

    @Test
    void reportWriterFallsBackToDataReports() throws Exception {
        AgentProperties props = new AgentProperties();
        props.getReport().setOutputDir("Z:\\not-exist\\决策分析"); // 必然写失败 → 降级
        ReportWriter writer = new ReportWriter(props);
        Path file = writer.write("我想转AI", "学习计划", "deep", "# 测试报告\n\n正文");
        assertNotNull(file);
        // 降级路径在 data/reports 下
        assertTrue(file.toString().replace("\\", "/").contains("data/reports"),
                "应降级到 data/reports，实际: " + file);
        assertTrue(Files.exists(file), "降级文件应存在: " + file);
        String content = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(content.contains("decision-agent"), "报告头应含来源元信息");
        assertTrue(content.contains("# 测试报告"), "报告正文应完整");
        // 清理自测产物
        Files.deleteIfExists(file);
    }

    @Test
    void excelExportHasFourSheets() throws Exception {
        byte[] bytes = new ExcelExporter().export(sampleData());
        assertTrue(bytes.length > 0);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertEquals(4, wb.getNumberOfSheets());
            Sheet s0 = wb.getSheetAt(0);
            assertEquals("三档对比", s0.getSheetName());
            assertEquals("A 档 · 乐观", s0.getRow(0).getCell(1).getStringCellValue());
            // 三档对比表头 + 6 参数行
            assertEquals(7, s0.getLastRowNum() + 1);
            assertEquals("里程碑计划", wb.getSheetAt(1).getSheetName());
            assertEquals("周投入排期", wb.getSheetAt(2).getSheetName());
            assertEquals("风险清单", wb.getSheetAt(3).getSheetName());
        }
    }

    @Test
    void pdfExportProducesPdf() throws Exception {
        byte[] bytes = new PdfExporter().export(sampleData());
        assertTrue(bytes.length > 0);
        String head = new String(bytes, 0, Math.min(5, bytes.length), StandardCharsets.ISO_8859_1);
        assertTrue(head.startsWith("%PDF"), "PDF 应以 %PDF 开头，实际: " + head);
    }

    @Test
    void docxExportProducesDocx() throws Exception {
        byte[] bytes = new WordExporter().export(sampleData());
        assertTrue(bytes.length > 0);
        // docx = zip（PK 头）
        assertEquals('P', bytes[0]);
        assertEquals('K', bytes[1]);
    }

    @Test
    void exportServiceBundleProducesZipWithFourFiles() throws Exception {
        String dbFile = "data/test-bundle.db";
        Files.deleteIfExists(Path.of(dbFile));
        // 最小库结构（analysis/report/analysis_state 三表，列与生产 DbInitializer 对齐）
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE analysis (id TEXT PRIMARY KEY, question TEXT, type TEXT, depth TEXT, "
                    + "status TEXT, progress TEXT, report_id INTEGER, model_calls INTEGER, search_count INTEGER, "
                    + "total_cost_ms INTEGER, created_at TEXT, finished_at TEXT)");
            st.execute("CREATE TABLE report (id INTEGER PRIMARY KEY AUTOINCREMENT, analysis_id TEXT, title TEXT, "
                    + "markdown TEXT, file_path TEXT, created_at TEXT)");
            st.execute("CREATE TABLE analysis_state (analysis_id TEXT PRIMARY KEY, current_step TEXT, "
                    + "steps_output TEXT, slot_snapshot TEXT, status TEXT, pending_question TEXT, updated_at TEXT)");
            st.execute("CREATE TABLE analysis_expectation (id INTEGER PRIMARY KEY AUTOINCREMENT, analysis_id TEXT, "
                    + "scenario TEXT, expectation TEXT, probability REAL, actual_result TEXT, actual_note TEXT, "
                    + "created_at TEXT, reviewed_at TEXT)");
        }
        try {
            DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:sqlite:" + dbFile);
            JdbcTemplate jt = new JdbcTemplate(ds);
            jt.update("INSERT INTO analysis(id, question, type, depth, status, created_at) VALUES (?,?,?,?,?,?)",
                    "t001", "我想转 AI", "学习计划", "deep", "done", "2026-08-31 12:00:00");
            AnalysisStore store = new AnalysisStore(jt);
            String md = "# 可行性分析：我想转 AI\n\n## 可行性结论\n\n可行。\n\n## 三档情景\n\n### A 档 · 乐观\n- 达成概率：0.65\n";
            long reportId = store.saveReport("t001", "可行性分析：我想转 AI", md);
            store.updateReportFilePath(reportId, "data/reports/t001.md");

            ObjectMapper om = new ObjectMapper();
            Map<String, Object> steps = Map.of("assess", om.convertValue(sampleData().assess(), Object.class),
                    "risk", om.convertValue(sampleData().risk(), Object.class));
            new AnalysisStateStore(jt).create("t001", "risk", om.writeValueAsString(steps), "{}", "done", null);

            ExportService svc = new ExportService(store, new AnalysisStateStore(jt),
                    new ReportWriter(noWriteProps()), new ExcelExporter(), new PdfExporter(), new WordExporter());

            byte[] zip = svc.bundle("t001");
            assertTrue(zip.length > 0);
            assertEquals('P', zip[0]); // zip 头 PK
            int count = 0;
            boolean[] seen = {false, false, false, false};
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
                java.util.zip.ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    count++;
                    if (e.getName().endsWith(".md")) seen[0] = true;
                    if (e.getName().endsWith(".xlsx")) seen[1] = true;
                    if (e.getName().endsWith(".pdf")) seen[2] = true;
                    if (e.getName().endsWith(".docx")) seen[3] = true;
                }
            }
            assertEquals(4, count, "zip 应含 4 个文件");
            assertTrue(seen[0] && seen[1] && seen[2] && seen[3], "zip 应含 md+xlsx+pdf+docx 四件");
        } finally {
            Files.deleteIfExists(Path.of(dbFile));
        }
    }

    /** ReportWriter 只用于 md 头生成（不落盘），outputDir 指向不存在盘符不影响 */
    private static AgentProperties noWriteProps() {
        AgentProperties p = new AgentProperties();
        p.getReport().setOutputDir("Z:\\not-exist");
        return p;
    }
}
