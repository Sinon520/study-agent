package com.studyagent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.entity.KbChunk;
import com.studyagent.mapper.KbChunkMapper;
import com.studyagent.service.KbChunkService;
import org.springframework.stereotype.Service;

/**
 * 知识库切片 业务实现
 */
@Service
public class KbChunkServiceImpl extends ServiceImpl<KbChunkMapper, KbChunk> implements KbChunkService {
}