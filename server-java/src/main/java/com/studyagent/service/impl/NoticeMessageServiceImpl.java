package com.studyagent.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.common.Result;
import com.studyagent.common.exception.BizException;
import com.studyagent.entity.NoticeMessage;
import com.studyagent.mapper.NoticeMessageMapper;
import com.studyagent.model.dto.PageQuery;
import com.studyagent.model.vo.NoticeMessageVO;
import com.studyagent.model.vo.PageResult;
import com.studyagent.service.NoticeMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 站内通知 业务实现。
 */
@Slf4j
@Service
public class NoticeMessageServiceImpl extends ServiceImpl<NoticeMessageMapper, NoticeMessage>
        implements NoticeMessageService {

    private static final List<String> VALID_TYPE = List.of("提醒", "复习", "报告");

    @Override
    public PageResult<NoticeMessageVO> pageNotices(Long userId, PageQuery query, String type, Integer readFlag) {
        Page<NoticeMessage> page = query.toPage();
        Page<NoticeMessage> result = page(page, Wrappers.<NoticeMessage>lambdaQuery()
                .eq(NoticeMessage::getUserId, userId)
                .eq(StringUtils.hasText(type), NoticeMessage::getType, type)
                .eq(readFlag != null, NoticeMessage::getReadFlag, readFlag)
                // 未读优先，再按时间倒序：用户打开通知列表最想先看到没读的
                .orderByAsc(NoticeMessage::getReadFlag)
                .orderByDesc(NoticeMessage::getSendTime)
                .orderByDesc(NoticeMessage::getId));

        return PageResult.of(result, NoticeMessageVO::from);
    }

    @Override
    public long unreadCount(Long userId) {
        return count(Wrappers.<NoticeMessage>lambdaQuery()
                .eq(NoticeMessage::getUserId, userId)
                .eq(NoticeMessage::getReadFlag, 0));
    }

    @Override
    public void markRead(Long userId, Long id) {
        requireOwned(userId, id);
        NoticeMessage patch = new NoticeMessage();
        patch.setReadFlag(1);
        update(patch, Wrappers.<NoticeMessage>lambdaUpdate()
                .eq(NoticeMessage::getId, id)
                .eq(NoticeMessage::getUserId, userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markReadBatch(Long userId, List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        NoticeMessage patch = new NoticeMessage();
        patch.setReadFlag(1);
        // 条件里必须带 user_id：否则改 URL 里的 id 就能标记别人的通知
        boolean updated = update(patch, Wrappers.<NoticeMessage>lambdaUpdate()
                .eq(NoticeMessage::getUserId, userId)
                .eq(NoticeMessage::getReadFlag, 0)
                .in(NoticeMessage::getId, ids));
        return updated ? ids.size() : 0;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int markAllRead(Long userId) {
        // 先数出未读数：update 的返回值是"条件是否匹配成功"的布尔量，拿不到影响行数，
        // 所以这里用 count 作为返回值，语义上就是"本次标记了几条"
        long unread = unreadCount(userId);
        if (unread == 0) {
            return 0;
        }
        NoticeMessage patch = new NoticeMessage();
        patch.setReadFlag(1);
        update(patch, Wrappers.<NoticeMessage>lambdaUpdate()
                .eq(NoticeMessage::getUserId, userId)
                .eq(NoticeMessage::getReadFlag, 0));
        log.info("全部标记已读 userId={} 条数={}", userId, unread);
        return (int) unread;
    }

    @Override
    public void deleteNotice(Long userId, Long id) {
        requireOwned(userId, id);
        removeById(id);
    }

    @Override
    public NoticeMessage push(Long userId, String type, String title, String content, Long bizId) {
        if (!VALID_TYPE.contains(type)) {
            throw new BizException(Result.CODE_BAD_REQUEST, "通知类型不合法：" + type + "，可选 " + VALID_TYPE);
        }
        NoticeMessage message = new NoticeMessage();
        message.setUserId(userId);
        message.setType(type);
        message.setTitle(title);
        message.setContent(content);
        message.setChannel("PC");
        message.setReadFlag(0);
        message.setBizId(bizId);
        message.setSendTime(LocalDateTime.now());
        save(message);
        log.info("写入站内通知 userId={} type={} title={} bizId={}", userId, type, title, bizId);
        return message;
    }

    private void requireOwned(Long userId, Long id) {
        NoticeMessage message = getById(id);
        if (message == null) {
            throw new BizException(Result.CODE_BAD_REQUEST, "通知不存在或已被删除：" + id);
        }
        if (!message.getUserId().equals(userId)) {
            log.warn("水平越权拦截：userId={} 试图访问 noticeId={}（属于 userId={}）",
                    userId, id, message.getUserId());
            throw new BizException(Result.CODE_FORBIDDEN, "无权访问该通知");
        }
    }
}
