package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.entity.WrongQuestionTagRel;
import org.apache.ibatis.annotations.Mapper;

/**
 * 错题标签关联 Mapper
 */
@Mapper
public interface WrongQuestionTagRelMapper extends BaseMapper<WrongQuestionTagRel> {
}