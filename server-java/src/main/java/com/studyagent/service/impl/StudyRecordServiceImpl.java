package com.studyagent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.entity.StudyRecord;
import com.studyagent.mapper.StudyRecordMapper;
import com.studyagent.service.StudyRecordService;
import org.springframework.stereotype.Service;

/**
 * 学习记录 业务实现
 */
@Service
public class StudyRecordServiceImpl extends ServiceImpl<StudyRecordMapper, StudyRecord> implements StudyRecordService {
}