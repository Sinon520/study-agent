package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.entity.PlanTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 计划任务 Mapper
 */
@Mapper
public interface PlanTaskMapper extends BaseMapper<PlanTask> {
}