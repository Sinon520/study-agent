package com.studyagent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.entity.WrongQuestionTagRel;
import com.studyagent.mapper.WrongQuestionTagRelMapper;
import com.studyagent.service.WrongQuestionTagRelService;
import org.springframework.stereotype.Service;

/**
 * 错题标签关联 业务实现
 */
@Service
public class WrongQuestionTagRelServiceImpl extends ServiceImpl<WrongQuestionTagRelMapper, WrongQuestionTagRel> implements WrongQuestionTagRelService {
}