package com.studyagent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.entity.ChatSession;
import com.studyagent.mapper.ChatSessionMapper;
import com.studyagent.service.ChatSessionService;
import org.springframework.stereotype.Service;

/**
 * 问答会话 业务实现
 */
@Service
public class ChatSessionServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession> implements ChatSessionService {
}