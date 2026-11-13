package com.decision.export;

import com.decision.model.LoopModels.RiskItem;
import com.decision.model.LoopModels.Scenario;
import com.decision.model.LoopModels.ScenarioMilestone;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Excel 计划附件导出（spec Requirement: 产出物五形态，Task 11.3）：
 * POI 渲染 xlsx，四 sheet：
 * - 三档对比：A/B/C 各档参数列（周投入/时长/概率/里程碑/验收标准/降档条件）
 * - 里程碑计划：档位 + 里程碑 + 验收标准 + 降档条件（平铺）
 * - 周投入排期：周次 + A/B/C 档每周投入（可编辑打卡跟踪，不锁单元格）
 * - 风险清单：风险/概率/影响/缓解措施
 * 可编辑（默认不锁单元格）；叙事内容不进 Excel（留在 MD/PDF）。
 */
@Service
public class ExcelExporter {

    private static final Logger log = LoggerFactory.getLogger(ExcelExporter.class);

    private static final String[] GRADES = {"A", "B", "C"};
    private static final String[] GRADE_LABELS = {"乐观", "现实", "保守"};

    public byte[] export(ExportData data) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            CellStyle headerStyle = headerStyle(wb);
            scenarioSheet(wb, headerStyle, data);
            milestoneSheet(wb, headerStyle, data);
            scheduleSheet(wb, headerStyle, data);
            riskSheet(wb, headerStyle, data);
            wb.write(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("xlsx 导出失败: " + e.getMessage(), e);
        }
    }

    // ==================== 三档对比 ====================

    private void scenarioSheet(Workbook wb, CellStyle headerStyle, ExportData data) {
        Sheet sheet = wb.createSheet("三档对比");
        sheet.setColumnWidth(0, 22);
        for (int i = 1; i <= 3; i++) {
            sheet.setColumnWidth(i, 52);
        }
        // 表头：参数 | A 档 · 乐观 | B 档 · 现实 | C 档 · 保守
        Row head = sheet.createRow(0);
        head.createCell(0).setCellValue("参数");
        for (int i = 0; i < 3; i++) {
            Cell c = head.createCell(i + 1);
            c.setCellValue(GRADES[i] + " 档 · " + GRADE_LABELS[i]);
            c.setCellStyle(headerStyle);
        }
        head.getCell(0).setCellStyle(headerStyle);

        if (!data.hasScenarios()) {
            Row r = sheet.createRow(1);
            r.createCell(0).setCellValue("该分析未产出三档情景（轻量路径或分析中断）");
            return;
        }
        List<Scenario> scs = data.assess().scenarios();
        String[][] rows = {
                {"周投入（小时/周）", num(scenario(scs, 0).weeklyHours()), num(scenario(scs, 1).weeklyHours()), num(scenario(scs, 2).weeklyHours())},
                {"持续时长（周）", num(scenario(scs, 0).duration()), num(scenario(scs, 1).duration()), num(scenario(scs, 2).duration())},
                {"达成概率", pct(scenario(scs, 0).probability()), pct(scenario(scs, 1).probability()), pct(scenario(scs, 2).probability())},
                {"里程碑", milestonesOf(scenario(scs, 0)), milestonesOf(scenario(scs, 1)), milestonesOf(scenario(scs, 2))},
                {"验收标准", criteriaOf(scenario(scs, 0)), criteriaOf(scenario(scs, 1)), criteriaOf(scenario(scs, 2))},
                {"降档条件", demoteOf(scenario(scs, 0)), demoteOf(scenario(scs, 1)), demoteOf(scenario(scs, 2))},
        };
        for (int i = 0; i < rows.length; i++) {
            Row r = sheet.createRow(i + 1);
            for (int j = 0; j < rows[i].length; j++) {
                r.createCell(j).setCellValue(rows[i][j]);
            }
        }
    }

    // ==================== 里程碑计划 ====================

    private void milestoneSheet(Workbook wb, CellStyle headerStyle, ExportData data) {
        Sheet sheet = wb.createSheet("里程碑计划");
        sheet.setColumnWidth(0, 12);
        sheet.setColumnWidth(1, 40);
        sheet.setColumnWidth(2, 60);
        sheet.setColumnWidth(3, 50);
        String[] head = {"档位", "里程碑", "验收标准", "降档条件"};
        Row h = sheet.createRow(0);
        for (int i = 0; i < head.length; i++) {
            h.createCell(i).setCellValue(head[i]);
            h.getCell(i).setCellStyle(headerStyle);
        }
        int rowIdx = 1;
        if (data.hasScenarios()) {
            for (int i = 0; i < data.assess().scenarios().size(); i++) {
                Scenario s = data.assess().scenarios().get(i);
                if (s == null || s.milestones() == null) {
                    continue;
                }
                String grade = gradeOf(i, s.name());
                for (ScenarioMilestone m : s.milestones()) {
                    if (m == null) {
                        continue;
                    }
                    Row r = sheet.createRow(rowIdx++);
                    r.createCell(0).setCellValue(grade + " 档");
                    r.createCell(1).setCellValue(str(m.name()));
                    r.createCell(2).setCellValue(str(m.criteria()));
                    r.createCell(3).setCellValue(str(m.demoteCondition()));
                }
            }
        }
        if (rowIdx == 1) {
            sheet.createRow(1).createCell(0).setCellValue("该分析未产出里程碑计划（轻量路径或分析中断）");
        }
    }

    // ==================== 周投入排期（可编辑打卡） ====================

    private void scheduleSheet(Workbook wb, CellStyle headerStyle, ExportData data) {
        Sheet sheet = wb.createSheet("周投入排期");
        sheet.setColumnWidth(0, 10);
        sheet.setColumnWidth(1, 16);
        sheet.setColumnWidth(2, 16);
        sheet.setColumnWidth(3, 16);
        sheet.setColumnWidth(4, 40);
        String[] head = {"周次", "A 档投入", "B 档投入", "C 档投入", "备注/打卡"};
        Row h = sheet.createRow(0);
        for (int i = 0; i < head.length; i++) {
            h.createCell(i).setCellValue(head[i]);
            h.getCell(i).setCellStyle(headerStyle);
        }
        if (!data.hasScenarios()) {
            sheet.createRow(1).createCell(0).setCellValue("该分析未产出三档投入排期（轻量路径或分析中断）");
            return;
        }
        List<Scenario> scs = data.assess().scenarios();
        double[] hours = new double[3];
        double[] weeks = new double[3];
        double maxWeeks = 0;
        for (int i = 0; i < 3; i++) {
            Scenario s = scenario(scs, i);
            hours[i] = s.weeklyHours() == null ? 0 : s.weeklyHours();
            weeks[i] = s.duration() == null ? 0 : s.duration();
            maxWeeks = Math.max(maxWeeks, weeks[i]);
        }
        int rows = (int) Math.ceil(maxWeeks);
        for (int w = 1; w <= Math.max(rows, 1); w++) {
            Row r = sheet.createRow(w);
            r.createCell(0).setCellValue("第 " + w + " 周");
            for (int i = 0; i < 3; i++) {
                r.createCell(i + 1).setCellValue(w <= weeks[i] ? hours[i] : 0);
            }
            r.createCell(4).setCellValue("");
        }
        // 表尾汇总
        Row total = sheet.createRow(rows + 1);
        total.createCell(0).setCellValue("合计（按 B 档）");
        total.createCell(1).setCellValue(hours[0] * weeks[0]);
        total.createCell(2).setCellValue(hours[1] * weeks[1]);
        total.createCell(3).setCellValue(hours[2] * weeks[2]);
    }

    // ==================== 风险清单 ====================

    private void riskSheet(Workbook wb, CellStyle headerStyle, ExportData data) {
        Sheet sheet = wb.createSheet("风险清单");
        sheet.setColumnWidth(0, 48);
        sheet.setColumnWidth(1, 12);
        sheet.setColumnWidth(2, 12);
        sheet.setColumnWidth(3, 60);
        String[] head = {"风险", "概率", "影响", "缓解措施"};
        Row h = sheet.createRow(0);
        for (int i = 0; i < head.length; i++) {
            h.createCell(i).setCellValue(head[i]);
            h.getCell(i).setCellStyle(headerStyle);
        }
        int rowIdx = 1;
        if (data.risk() != null && data.risk().risks() != null) {
            for (RiskItem r : data.risk().risks()) {
                if (r == null) {
                    continue;
                }
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue(str(r.risk()));
                row.createCell(1).setCellValue(str(r.probability()));
                row.createCell(2).setCellValue(str(r.impact()));
                row.createCell(3).setCellValue(str(r.mitigation()));
            }
        }
        if (rowIdx == 1) {
            sheet.createRow(1).createCell(0).setCellValue("该分析未产出风险清单（轻量路径或分析中断）");
        }
    }

    // ==================== 工具 ====================

    private CellStyle headerStyle(Workbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(IndexedColors.INDIGO.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = wb.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        return style;
    }

    private static Scenario scenario(List<Scenario> scs, int idx) {
        if (scs == null || scs.isEmpty()) {
            return new Scenario("", null, null, null, null, null);
        }
        Scenario s = scs.get(Math.min(idx, scs.size() - 1));
        return s == null ? new Scenario("", null, null, null, null, null) : s;
    }

    private static String gradeOf(int idx, String name) {
        String n = name == null ? "" : name.strip();
        if (!n.isEmpty()) {
            String g = n.substring(0, 1).toUpperCase();
            if (g.matches("[A-C]")) {
                return g;
            }
        }
        return GRADES[Math.min(Math.max(idx, 0), 2)];
    }

    private static String milestonesOf(Scenario s) {
        if (s.milestones() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (ScenarioMilestone m : s.milestones()) {
            if (m == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append("M").append(i++).append(" ").append(str(m.name()));
        }
        return sb.toString();
    }

    private static String criteriaOf(Scenario s) {
        if (s.milestones() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ScenarioMilestone m : s.milestones()) {
            if (m == null || m.criteria() == null || m.criteria().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(str(m.criteria()));
        }
        return sb.toString();
    }

    private static String demoteOf(Scenario s) {
        if (s.milestones() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ScenarioMilestone m : s.milestones()) {
            if (m == null || m.demoteCondition() == null || m.demoteCondition().isBlank()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(str(m.demoteCondition()));
        }
        if (s.demoteTrigger() != null && !s.demoteTrigger().isBlank()) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append("整体触发：").append(s.demoteTrigger());
        }
        return sb.toString();
    }

    private static String num(Double d) {
        if (d == null) {
            return "";
        }
        return d == Math.floor(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
    }

    private static String pct(Double p) {
        if (p == null) {
            return "";
        }
        return Math.round(p * 100) + "%";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
