package com.decision.collector;

import com.decision.model.CollectorModels.SlotRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * slot 表 CRUD（analysis_id / name / importance / value / filled / is_standard）。
 * 以 (analysis_id, name) 为业务主键做 upsert；采集会话的问答文本、默认值、假设标记
 * 等会话态放 analysis_state.slot_snapshot（见 CollectorStateMachine），不占 slot 表。
 */
@Service
public class SlotStore {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jt;

    public SlotStore(JdbcTemplate jt) {
        this.jt = jt;
    }

    public void upsert(SlotRecord r) {
        int updated = jt.update("""
                        UPDATE slot SET importance=?, value=?, filled=?, is_standard=?, updated_at=?
                        WHERE analysis_id=? AND name=?
                        """,
                r.importance(), r.value(), r.filled() ? 1 : 0, r.standard() ? 1 : 0,
                LocalDateTime.now().format(TS), r.analysisId(), r.name());
        if (updated == 0) {
            jt.update("""
                            INSERT INTO slot(analysis_id, name, importance, value, filled, is_standard, created_at, updated_at)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    r.analysisId(), r.name(), r.importance(), r.value(), r.filled() ? 1 : 0,
                    r.standard() ? 1 : 0, LocalDateTime.now().format(TS), LocalDateTime.now().format(TS));
        }
    }

    public List<SlotRecord> list(String analysisId) {
        return jt.query("""
                        SELECT id, analysis_id, name, importance, value, filled, is_standard
                        FROM slot WHERE analysis_id=? ORDER BY id
                        """,
                (rs, i) -> new SlotRecord(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6) == 1, rs.getInt(7) == 1),
                analysisId);
    }

    public Optional<SlotRecord> find(String analysisId, String name) {
        List<SlotRecord> rows = jt.query("""
                        SELECT id, analysis_id, name, importance, value, filled, is_standard
                        FROM slot WHERE analysis_id=? AND name=?
                        """,
                (rs, i) -> new SlotRecord(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getInt(6) == 1, rs.getInt(7) == 1),
                analysisId, name);
        return rows.stream().findFirst();
    }
}
