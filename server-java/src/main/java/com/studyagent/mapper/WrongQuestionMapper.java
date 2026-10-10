package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.entity.WrongQuestion;
import org.apache.ibatis.annotations.Mapper;

/**
 * 错题 Mapper
 */
@Mapper
public interface WrongQuestionMapper extends BaseMapper<WrongQuestion> {
}