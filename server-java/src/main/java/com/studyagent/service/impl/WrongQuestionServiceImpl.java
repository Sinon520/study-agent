package com.studyagent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.entity.WrongQuestion;
import com.studyagent.mapper.WrongQuestionMapper;
import com.studyagent.service.WrongQuestionService;
import org.springframework.stereotype.Service;

/**
 * 错题 业务实现
 */
@Service
public class WrongQuestionServiceImpl extends ServiceImpl<WrongQuestionMapper, WrongQuestion> implements WrongQuestionService {
}