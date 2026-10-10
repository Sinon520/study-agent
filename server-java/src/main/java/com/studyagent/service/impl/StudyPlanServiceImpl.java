package com.studyagent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.entity.StudyPlan;
import com.studyagent.mapper.StudyPlanMapper;
import com.studyagent.service.StudyPlanService;
import org.springframework.stereotype.Service;

/**
 * 学习计划 业务实现
 */
@Service
public class StudyPlanServiceImpl extends ServiceImpl<StudyPlanMapper, StudyPlan> implements StudyPlanService {
}