package com.decision.export;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * PDF 导出（spec Requirement: 产出物五形态，Task 11.3）：
 * openhtmltopdf 从报告 Markdown 直转——Markdown → 纯 HTML（浅色打印风内联 CSS，
 * 比深色门面更适合打印/存档/转发；openhtmltopdf 的 CSS 支持是 2.1 子集，
 * 深色渐变背景打印效果差，故选浅色方案）→ PDF。
 *
 * 中文渲染：探测 Windows 常见中文字体（黑体/雅黑/楷体/宋体）注册给 openhtmltopdf，
 * 找不到时降级默认字体（中文可能显示为占位符，但导出不崩溃）。
 */
@Service
public class PdfExporter {

    private static final Logger log = LoggerFactory.getLogger(PdfExporter.class);

    /** 中文字体探测链（ttf 优先，openhtmltopdf 对 ttc 支持有限） */
    private static final String[][] FONT_CANDIDATES = {
            {"C:/Windows/Fonts/simhei.ttf", "SimHei"},
            {"C:/Windows/Fonts/simkai.ttf", "KaiTi"},
            {"C:/Windows/Fonts/msyh.ttc", "MicrosoftYaHei"},
            {"C:/Windows/Fonts/simsun.ttc", "SimSun"},
    };

    // pdfbox 字体缓存默认写 ~/.pdfbox.cache（沙箱受限），改到项目内 data/pdfbox-cache
    static {
        if (System.getProperty("pdfbox.fontcache") == null) {
            System.setProperty("pdfbox.fontcache", "data/pdfbox-cache");
        }
    }

    public byte[] export(ExportData data) {
        String html = toHtml(data);
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            for (String[] f : FONT_CANDIDATES) {
                File font = new File(f[0]);
                if (font.exists()) {
                    try {
                        builder.useFont(font, f[1]);
                        log.info("PDF 注册中文字体: {} → {}", f[0], f[1]);
                        break;
                    } catch (Exception e) {
                        log.debug("字体注册失败（跳过）: {} {}", f[0], e.getMessage());
                    }
                }
            }
            builder.withHtmlContent(html, null);
            builder.toStream(bos);
            builder.run();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PDF 导出失败: " + e.getMessage(), e);
        }
    }

    // ==================== Markdown → HTML（浅色打印风） ====================

    private String toHtml(ExportData data) {
        String title = data.title() == null ? "可行性分析报告" : data.title();
        String meta = "来源：decision-agent 可行性分析 · 类型：" + nz(data.type())
                + " · 深度：" + nz(data.depth()) + " · 生成：" + nz(data.createdAt());
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head><meta charset="UTF-8"/><title>%s</title>
                <style>
                  body { font-family: 'SimHei','MicrosoftYaHei','KaiTi','SimSun',sans-serif; color:#1f2430;
                         line-height:1.85; font-size:13px; margin:34px 40px; }
                  h1 { font-size:21px; color:#111827; border-bottom:2px solid #8b5cf6; padding-bottom:8px; }
                  h2 { font-size:16px; color:#4c1d95; border-bottom:1px solid #e5e7eb; padding-bottom:4px; margin-top:26px; }
                  h3 { font-size:14px; color:#312e81; margin-top:18px; }
                  .meta { font-size:11px; color:#6b7280; border-left:3px solid #c4b5fd; padding:6px 12px;
                          background:#faf5ff; margin:10px 0 20px; }
                  blockquote { border-left:3px solid #8b5cf6; margin:8px 0; padding:4px 14px;
                               background:#f5f3ff; color:#4b5563; }
                  code { background:#f3f4f6; padding:1px 5px; border-radius:4px; font-size:12px; }
                  ul { padding-left:22px; }
                  li { margin:4px 0; }
                  strong { color:#111827; }
                  table { border-collapse:collapse; width:100%%; margin:10px 0; }
                  th,td { border:1px solid #d1d5db; padding:6px 9px; text-align:left; font-size:12px; }
                  th { background:#ede9fe; color:#4c1d95; }
                  hr { border:0; border-top:1px dashed #d1d5db; margin:22px 0; }
                  .footer { margin-top:30px; font-size:11px; color:#9ca3af; text-align:center; }
                </style></head>
                <body>
                <h1>%s</h1>
                <div class="meta">%s</div>
                %s
                <div class="footer">由 decision-agent 生成，仅供参考；「该不该做」的决策权在用户。</div>
                </body></html>""".formatted(esc(title), esc(title), esc(meta), mdToHtml(data.markdown()));
    }

    /** 迷你 Markdown → HTML（标题/列表/引用/加粗/代码/分割线/表格草稿；不支持行内链接等复杂语法） */
    static String mdToHtml(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "<p>（无内容）</p>";
        }
        StringBuilder html = new StringBuilder();
        boolean inTable = false;
        List<String> lines = new ArrayList<>(List.of(markdown.split("\n")));
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("|") && i + 1 < lines.size() && lines.get(i + 1).strip().matches("\\|?[\\s:|-]+\\|?\\s*")) {
                // 表格：表头行 + 分隔行
                if (!inTable) {
                    html.append("<table><tr>");
                    inTable = true;
                }
                html.append("<tr>");
                for (String cell : line.substring(1, line.length() - 1).split("\\|")) {
                    html.append("<td>").append(inline(cell.strip())).append("</td>");
                }
                html.append("</tr>");
                i++; // 跳过分隔行
                continue;
            }
            if (inTable) {
                html.append("</table>");
                inTable = false;
            }
            if (line.startsWith("# ")) {
                html.append("<h1>").append(inline(line.substring(2))).append("</h1>");
            } else if (line.startsWith("## ")) {
                html.append("<h2>").append(inline(line.substring(3))).append("</h2>");
            } else if (line.startsWith("### ")) {
                html.append("<h3>").append(inline(line.substring(4))).append("</h3>");
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                html.append("<li>").append(inline(line.substring(2))).append("</li>");
            } else if (line.startsWith("> ")) {
                html.append("<blockquote>").append(inline(line.substring(2))).append("</blockquote>");
            } else if (line.matches("-{3,}")) {
                html.append("<hr/>");
            } else {
                html.append("<p>").append(inline(line)).append("</p>");
            }
        }
        if (inTable) {
            html.append("</table>");
        }
        return html.toString();
    }

    /** 行内：转义 + 加粗 + 行内代码 */
    private static String inline(String s) {
        if (s == null) {
            return "";
        }
        String out = esc(s);
        // 简单加粗：**text**；行内代码：`text`
        out = out.replaceAll("\\*\\*([^*]+)\\*\\*", "<strong>$1</strong>");
        out = out.replaceAll("`([^`]+)`", "<code>$1</code>");
        return out;
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }
}
