package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.entity.StudyRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 学习记录 Mapper
 */
@Mapper
public interface StudyRecordMapper extends BaseMapper<StudyRecord> {
}