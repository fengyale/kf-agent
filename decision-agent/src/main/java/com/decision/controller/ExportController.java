package com.decision.controller;

import com.decision.export.ExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

/**
 * 导出下载路由（spec Requirement: 产出物五形态，Task 11.3）：
 * GET /api/export/{analysisId}?format=md|xlsx|pdf|docx —— 单项下载
 * GET /api/export/{analysisId}/bundle                    —— zip 打包（MD+xlsx+pdf+docx 四件）
 * 文件名：yyyyMMdd-HHmm-问题（去非法字符截 30 字），中文文件名 UTF-8 编码（RFC 5987）。
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping("/{analysisId}")
    public ResponseEntity<byte[]> export(@PathVariable String analysisId,
                                         @RequestParam(defaultValue = "md") String format) {
        try {
            return switch (format) {
                case "md" -> respond(exportService.md(analysisId),
                        "text/markdown; charset=UTF-8", ".md", analysisId);
                case "xlsx" -> respond(exportService.xlsx(analysisId),
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx", analysisId);
                case "pdf" -> respond(exportService.pdf(analysisId),
                        "application/pdf", ".pdf", analysisId);
                case "docx" -> respond(exportService.docx(analysisId),
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ".docx", analysisId);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "format 非法: " + format + "（可选 md|xlsx|pdf|docx）");
            };
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "导出失败: " + e.getMessage());
        }
    }

    @GetMapping("/{analysisId}/bundle")
    public ResponseEntity<byte[]> bundle(@PathVariable String analysisId) {
        try {
            byte[] bytes = exportService.bundle(analysisId);
            String base = exportService.fileNameBase(analysisId);
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("application/zip"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachment(base + ".zip"))
                    .body(bytes);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "打包失败: " + e.getMessage());
        }
    }

    private ResponseEntity<byte[]> respond(byte[] bytes, String contentType, String ext, String analysisId) {
        String base = exportService.fileNameBase(analysisId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, attachment(base + ext))
                .body(bytes);
    }

    /** Content-Disposition：中文文件名 UTF-8 编码（RFC 5987 filename*） */
    private static String attachment(String fileName) {
        return "attachment; filename=\"export\"; filename*=UTF-8''"
                + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
