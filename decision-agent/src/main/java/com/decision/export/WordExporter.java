package com.decision.export;

import com.decision.model.LoopModels.AssessLightOutput;
import com.decision.model.LoopModels.AssessOutput;
import com.decision.model.LoopModels.GapItem;
import com.decision.model.LoopModels.PathStage;
import com.decision.model.LoopModels.RedTeamChallenge;
import com.decision.model.LoopModels.RedTeamMeta;
import com.decision.model.LoopModels.RiskItem;
import com.decision.model.LoopModels.Scenario;
import com.decision.model.LoopModels.ScenarioMilestone;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Word 导出（spec Requirement: 产出物五形态，Task 11.3）：
 * POI XWPF 生成 docx——标题/结论/三档/路径/差距/风险/已知局限结构化为段落 + 表格，
 * 适合正式场合发送（叙事留在 MD/PDF，这里放结构化数据）。
 */
@Service
public class WordExporter {

    private static final Logger log = LoggerFactory.getLogger(WordExporter.class);

    private static final String[] GRADES = {"A", "B", "C"};

    public byte[] export(ExportData data) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            title(doc, data);
            meta(doc, data);
            conclusion(doc, data);
            scenarios(doc, data);
            path(doc, data);
            gap(doc, data);
            risk(doc, data);
            knownLimits(doc, data);
            footer(doc);
            doc.write(bos);
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("docx 导出失败: " + e.getMessage(), e);
        }
    }

    // ==================== 段落 ====================

    private void title(XWPFDocument doc, ExportData data) {
        XWPFParagraph p = doc.createParagraph();
        p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun r = p.createRun();
        r.setBold(true);
        r.setFontSize(18);
        r.setText(data.title() == null ? "可行性分析报告" : data.title());
    }

    private void meta(XWPFDocument doc, ExportData data) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setFontSize(10);
        r.setColor("6B7280");
        r.setText("来源：decision-agent 可行性分析 · 类型：" + nz(data.type())
                + " · 深度：" + nz(data.depth()) + " · 生成：" + nz(data.createdAt()));
    }

    private void heading(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setBold(true);
        r.setFontSize(14);
        r.setColor("4C1D95");
        r.setText(text);
        p.setSpacingBefore(240);
    }

    private void para(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setFontSize(11);
        r.setText(text == null ? "" : text);
    }

    private void bullet(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(360);
        XWPFRun r = p.createRun();
        r.setFontSize(11);
        r.setText("• " + (text == null ? "" : text));
    }

    // ==================== 结论 ====================

    private void conclusion(XWPFDocument doc, ExportData data) {
        heading(doc, "可行性结论");
        if (data.assess() != null) {
            para(doc, nz(data.assess().conclusion()));
            if (data.assess().notes() != null && !data.assess().notes().isBlank()) {
                para(doc, "备注：" + data.assess().notes());
            }
        } else if (data.light() != null) {
            para(doc, nz(data.light().conclusion()));
            if (data.light().suggestion() != null && !data.light().suggestion().isBlank()) {
                para(doc, "建议：" + data.light().suggestion());
            }
            if (data.light().keyRisks() != null) {
                for (String k : data.light().keyRisks()) {
                    bullet(doc, k);
                }
            }
        } else {
            para(doc, "（该分析未产出可行性结论：轻量路径或分析中断）");
        }
    }

    // ==================== 三档情景表格 ====================

    private void scenarios(XWPFDocument doc, ExportData data) {
        if (!data.hasScenarios()) {
            return;
        }
        heading(doc, "三档情景");
        List<Scenario> scs = data.assess().scenarios();
        XWPFTable table = doc.createTable(scs.size() + 1, 5);
        String[] head = {"档位", "周投入", "时长", "概率", "里程碑 / 验收标准 / 降档条件"};
        for (int c = 0; c < head.length; c++) {
            cell(table.getRow(0).getCell(c), head[c], true);
        }
        for (int i = 0; i < scs.size(); i++) {
            Scenario s = scs.get(i);
            if (s == null) {
                continue;
            }
            XWPFTableRow row = table.getRow(i + 1);
            cell(row.getCell(0), gradeOf(i, s.name()) + " 档", true);
            cell(row.getCell(1), num(s.weeklyHours()) + " 小时/周");
            cell(row.getCell(2), num(s.duration()) + " 周");
            cell(row.getCell(3), pct(s.probability()));
            cell(row.getCell(4), milestoneText(s));
        }
    }

    // ==================== 路径表格 ====================

    private void path(XWPFDocument doc, ExportData data) {
        if (data.path() == null || data.path().stages() == null || data.path().stages().isEmpty()) {
            return;
        }
        heading(doc, "路径设计");
        List<PathStage> stages = data.path().stages();
        XWPFTable table = doc.createTable(stages.size() + 1, 4);
        String[] head = {"阶段", "周数", "目标", "验收标准"};
        for (int c = 0; c < head.length; c++) {
            cell(table.getRow(0).getCell(c), head[c], true);
        }
        for (int i = 0; i < stages.size(); i++) {
            PathStage st = stages.get(i);
            if (st == null) {
                continue;
            }
            XWPFTableRow row = table.getRow(i + 1);
            cell(row.getCell(0), nz(st.name()));
            cell(row.getCell(1), num(st.weeks()) + " 周");
            cell(row.getCell(2), nz(st.goal()));
            cell(row.getCell(3), nz(st.validation()));
        }
    }

    // ==================== 差距表格 ====================

    private void gap(XWPFDocument doc, ExportData data) {
        if (data.gap() == null || data.gap().gaps() == null || data.gap().gaps().isEmpty()) {
            return;
        }
        heading(doc, "差距清单");
        List<GapItem> gaps = data.gap().gaps();
        XWPFTable table = doc.createTable(gaps.size() + 1, 5);
        String[] head = {"差距项", "现状", "目标", "严重度", "kill criteria"};
        for (int c = 0; c < head.length; c++) {
            cell(table.getRow(0).getCell(c), head[c], true);
        }
        for (int i = 0; i < gaps.size(); i++) {
            GapItem g = gaps.get(i);
            if (g == null) {
                continue;
            }
            XWPFTableRow row = table.getRow(i + 1);
            cell(row.getCell(0), nz(g.item()));
            cell(row.getCell(1), nz(g.current()));
            cell(row.getCell(2), nz(g.target()));
            cell(row.getCell(3), nz(g.severity()));
            cell(row.getCell(4), nz(g.killCriteria()));
        }
    }

    // ==================== 风险表格 ====================

    private void risk(XWPFDocument doc, ExportData data) {
        if (data.risk() == null || data.risk().risks() == null || data.risk().risks().isEmpty()) {
            return;
        }
        heading(doc, "风险与对策");
        List<RiskItem> risks = data.risk().risks();
        XWPFTable table = doc.createTable(risks.size() + 1, 4);
        String[] head = {"风险", "概率", "影响", "缓解措施"};
        for (int c = 0; c < head.length; c++) {
            cell(table.getRow(0).getCell(c), head[c], true);
        }
        for (int i = 0; i < risks.size(); i++) {
            RiskItem r = risks.get(i);
            if (r == null) {
                continue;
            }
            XWPFTableRow row = table.getRow(i + 1);
            cell(row.getCell(0), nz(r.risk()));
            cell(row.getCell(1), nz(r.probability()));
            cell(row.getCell(2), nz(r.impact()));
            cell(row.getCell(3), nz(r.mitigation()));
        }
    }

    // ==================== 已知局限（红队 minor） ====================

    private void knownLimits(XWPFDocument doc, ExportData data) {
        RedTeamMeta meta = data.redteam();
        if (meta == null) {
            return;
        }
        // 与报告 Markdown 同口径：优先跨轮累计质疑，缺失时退回最后一轮（避免导出漏掉历轮 minor）
        List<RedTeamChallenge> source = meta.allChallenges() != null && !meta.allChallenges().isEmpty()
                ? meta.allChallenges()
                : (meta.challenges() == null ? List.of() : meta.challenges());
        List<RedTeamChallenge> minors = source.stream()
                .filter(c -> c != null && !com.decision.model.LoopModels.isMajor(c.severity()))
                .toList();
        if (minors.isEmpty()) {
            return;
        }
        heading(doc, "已知局限");
        for (RedTeamChallenge c : minors) {
            bullet(doc, c.point() + (c.mitigation() == null || c.mitigation().isBlank()
                    ? "" : "（应对：" + c.mitigation() + "）"));
        }
    }

    private void footer(XWPFDocument doc) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(400);
        p.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun r = p.createRun();
        r.setFontSize(9);
        r.setColor("9CA3AF");
        r.setText("由 decision-agent 生成，仅供参考；「该不该做」的决策权在用户。");
    }

    // ==================== 工具 ====================

    private void cell(XWPFTableCell c, String text, boolean bold) {
        XWPFParagraph p = c.getParagraphs().isEmpty() ? c.addParagraph() : c.getParagraphs().get(0);
        XWPFRun r = p.createRun();
        r.setFontSize(10);
        r.setBold(bold);
        r.setText(text);
    }

    private void cell(XWPFTableCell c, String text) {
        cell(c, text, false);
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

    private static String milestoneText(Scenario s) {
        StringBuilder sb = new StringBuilder();
        if (s.milestones() != null) {
            int i = 1;
            for (ScenarioMilestone m : s.milestones()) {
                if (m == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append("M").append(i++).append(" ").append(nz(m.name()));
                if (m.criteria() != null && !m.criteria().isBlank()) {
                    sb.append("（验收：").append(m.criteria()).append("）");
                }
                if (m.demoteCondition() != null && !m.demoteCondition().isBlank()) {
                    sb.append("（降档：").append(m.demoteCondition()).append("）");
                }
            }
        }
        if (s.demoteTrigger() != null && !s.demoteTrigger().isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("整体降档触发：").append(s.demoteTrigger());
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

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }
}
