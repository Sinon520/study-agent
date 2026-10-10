package com.studyagent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.entity.PlanTask;
import com.studyagent.mapper.PlanTaskMapper;
import com.studyagent.service.PlanTaskService;
import org.springframework.stereotype.Service;

/**
 * 计划任务 业务实现
 */
@Service
public class PlanTaskServiceImpl extends ServiceImpl<PlanTaskMapper, PlanTask> implements PlanTaskService {
}