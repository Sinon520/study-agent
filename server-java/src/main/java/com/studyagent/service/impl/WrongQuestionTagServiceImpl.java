package com.studyagent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.entity.WrongQuestionTag;
import com.studyagent.mapper.WrongQuestionTagMapper;
import com.studyagent.service.WrongQuestionTagService;
import org.springframework.stereotype.Service;

/**
 * 错题标签 业务实现
 */
@Service
public class WrongQuestionTagServiceImpl extends ServiceImpl<WrongQuestionTagMapper, WrongQuestionTag> implements WrongQuestionTagService {
}