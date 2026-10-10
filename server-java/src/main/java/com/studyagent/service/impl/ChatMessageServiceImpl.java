package com.studyagent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.entity.ChatMessage;
import com.studyagent.mapper.ChatMessageMapper;
import com.studyagent.service.ChatMessageService;
import org.springframework.stereotype.Service;

/**
 * 问答消息 业务实现
 */
@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatMessageService {
}