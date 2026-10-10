package com.studyagent.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 错题 AI 归类结果（Python {@code /agent/classify} 的响应）。
 *
 * <p>用于 RocketMQ 消费者拿到结果后回写标签关系表（Day 23）。</p>
 */
@Data
@Schema(description = "错题 AI 归类结果")
public class ClassifyResultVO {

    @Schema(description = "科目建议（模型判断，可能与学生填写的一致）")
    private String subject;

    @Schema(description = "知识点标签（Python 侧已过滤与科目重复的泛标签，最多 4 个）")
    private List<String> tags = new ArrayList<>();

    @Schema(description = "难度估计：1 很易 ~ 5 很难（可能为 null）")
    private Integer difficulty;

    @Schema(description = "一句话判断依据（排查用，不展示给用户）")
    private String reason;
}
