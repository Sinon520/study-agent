package com.studyagent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.entity.KbDocument;
import com.studyagent.mapper.KbDocumentMapper;
import com.studyagent.service.KbDocumentService;
import org.springframework.stereotype.Service;

/**
 * 知识库文档 业务实现
 */
@Service
public class KbDocumentServiceImpl extends ServiceImpl<KbDocumentMapper, KbDocument> implements KbDocumentService {
}