package com.studyagent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.entity.NoticeMessage;
import com.studyagent.mapper.NoticeMessageMapper;
import com.studyagent.service.NoticeMessageService;
import org.springframework.stereotype.Service;

/**
 * 站内通知 业务实现
 */
@Service
public class NoticeMessageServiceImpl extends ServiceImpl<NoticeMessageMapper, NoticeMessage> implements NoticeMessageService {
}