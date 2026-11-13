package com.decision.loop;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * analysis_state 表读写（人机交织挂起/恢复的核心持久层）：
 * current_step / steps_output JSON / slot_snapshot / pending_question / status。
 *
 * status 枚举（spec）：running / waiting / done / error / cancelled。
 * steps_output JSON 为 {步骤名: 步骤产出}，另含保留键：
 * - "_exit"：退出原因四分类（model-decision:* / guard:* / error:* / waiting:user）
 * - "_meta"：循环元数据（累计活跃时长 activeMs，挂起等待期间不计时）
 */
@Service
public class AnalysisStateStore {

    private static final Logger log = LoggerFactory.getLogger(AnalysisStateStore.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_WAITING = "waiting";
    public static final String STATUS_DONE = "done";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_CANCELLED = "cancelled";

    private final JdbcTemplate jt;
    private final ObjectMapper om = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public AnalysisStateStore(JdbcTemplate jt) {
        this.jt = jt;
    }

    /** analysis_state 行记录 */
    public record AnalysisState(String analysisId, String currentStep, String stepsOutput,
                                String slotSnapshot, String status, String pendingQuestion, String updatedAt) {}

    /** 全量写入（行不存在则插入） */
    public void create(String analysisId, String currentStep, String stepsOutput,
                       String slotSnapshot, String status, String pendingQuestion) {
        String now = LocalDateTime.now().format(TS);
        int updated = jt.update("""
                        UPDATE analysis_state SET current_step=?, steps_output=?, slot_snapshot=?, status=?, pending_question=?, updated_at=?
                        WHERE analysis_id=?
                        """,
                currentStep, stepsOutput, slotSnapshot, status, pendingQuestion, now, analysisId);
        if (updated == 0) {
            jt.update("""
                            INSERT INTO analysis_state(analysis_id, current_step, steps_output, slot_snapshot, status, pending_question, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?)
                            """,
                    analysisId, currentStep, stepsOutput, slotSnapshot, status, pendingQuestion, now);
        }
    }

    /** 仅更新槽位快照（采集会话态：设计字段 + 降级元数据） */
    public void saveSnapshot(String analysisId, String slotSnapshot) {
        String now = LocalDateTime.now().format(TS);
        int updated = jt.update("UPDATE analysis_state SET slot_snapshot=?, updated_at=? WHERE analysis_id=?",
                slotSnapshot, now, analysisId);
        if (updated == 0) {
            create(analysisId, null, "{}", slotSnapshot, STATUS_WAITING, null);
        }
    }

    /** 更新待答问题与状态（挂起点：collect 追问 / ask:followup / 暂停） */
    public void updatePending(String analysisId, String pendingQuestion, String status) {
        String now = LocalDateTime.now().format(TS);
        int updated = jt.update("UPDATE analysis_state SET pending_question=?, status=?, updated_at=? WHERE analysis_id=?",
                pendingQuestion, status, now, analysisId);
        if (updated == 0) {
            create(analysisId, null, "{}", "{}", status, pendingQuestion);
        }
    }

    /** 更新步骤推进进度（current_step / steps_output / status） */
    public void updateProgress(String analysisId, String currentStep, String stepsOutput, String status) {
        String now = LocalDateTime.now().format(TS);
        int updated = jt.update("""
                        UPDATE analysis_state SET current_step=?, steps_output=?, status=?, updated_at=?
                        WHERE analysis_id=?
                        """,
                currentStep, stepsOutput, status, now, analysisId);
        if (updated == 0) {
            create(analysisId, currentStep, stepsOutput, "{}", status, null);
        }
    }

    public void updateStatus(String analysisId, String status) {
        String now = LocalDateTime.now().format(TS);
        int updated = jt.update("UPDATE analysis_state SET status=?, updated_at=? WHERE analysis_id=?",
                status, now, analysisId);
        if (updated == 0) {
            create(analysisId, null, "{}", "{}", status, null);
        }
    }

    public AnalysisState get(String analysisId) {
        return jt.query("""
                        SELECT analysis_id, current_step, steps_output, slot_snapshot, status, pending_question, updated_at
                        FROM analysis_state WHERE analysis_id=?
                        """,
                (rs, i) -> new AnalysisState(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7)),
                analysisId).stream().findFirst().orElse(null);
    }

    /** steps_output JSON → 有序 Map（解析失败返回空 Map，循环从零步重建） */
    public LinkedHashMap<String, Object> parseSteps(AnalysisState state) {
        if (state == null || state.stepsOutput() == null || state.stepsOutput().isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return om.readValue(state.stepsOutput(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("steps_output 解析失败，按空处理: {}", e.getMessage());
            return new LinkedHashMap<>();
        }
    }

    public String writeSteps(Map<String, Object> steps) {
        try {
            return om.writeValueAsString(steps);
        } catch (Exception e) {
            return "{}";
        }
    }
}
