package com.decision.export;

import com.decision.model.LoopModels.AssessLightOutput;
import com.decision.model.LoopModels.AssessOutput;
import com.decision.model.LoopModels.GapOutput;
import com.decision.model.LoopModels.PathOutput;
import com.decision.model.LoopModels.RedTeamMeta;
import com.decision.model.LoopModels.RiskOutput;

/**
 * 导出数据载体（Task 11.3）：由 ExportService 从 analysis_state.steps_output 解析
 * 各步骤结构化产物 + report 表 markdown，供 Excel/Pdf/Word 三导出器消费。
 * light 路径无 assess/path/risk，仅 light 结论；中断分析仅 markdown。
 */
public record ExportData(
        String analysisId,
        String question,
        String type,
        String depth,
        String status,
        String title,
        String markdown,
        String createdAt,
        AssessOutput assess,
        AssessLightOutput light,
        PathOutput path,
        GapOutput gap,
        RiskOutput risk,
        RedTeamMeta redteam) {

    public boolean hasScenarios() {
        return assess != null && assess.scenarios() != null && !assess.scenarios().isEmpty();
    }
}
