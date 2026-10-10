package com.studyagent.model.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 今日任务视图（小程序首页 / PC 端首页用）。
 *
 * <p>一次请求把"今天要做什么 + 做得怎么样"全给出来，
 * 首屏不用发第二次请求，弱网下体验差别很明显。</p>
 */
@Data
@Schema(description = "今日任务视图")
public class TodayTaskVO {

    @Schema(description = "日期")
    private LocalDate date;

    @Schema(description = "今日任务列表")
    private List<PlanTaskVO> tasks = new ArrayList<>();

    @Schema(description = "今日任务总数")
    private Integer total = 0;

    @Schema(description = "已完成数")
    private Integer done = 0;

    @Schema(description = "已跳过数")
    private Integer skipped = 0;

    @Schema(description = "待办数")
    private Integer todo = 0;

    @Schema(description = "今日已完成学习时长（分钟）")
    private Integer doneMinutes = 0;

    @Schema(description = "今日计划总时长（分钟）")
    private Integer planMinutes = 0;

    @Schema(description = "完成率百分比（0~100，保留 1 位小数）")
    private Double progress = 0.0;

    @Schema(description = "今日鼓励语（按完成率给不同文案，纯前端体验细节）")
    private String tip;
}
