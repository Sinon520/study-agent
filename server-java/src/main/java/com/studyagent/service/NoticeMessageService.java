package com.studyagent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.studyagent.entity.NoticeMessage;
import com.studyagent.model.dto.PageQuery;
import com.studyagent.model.vo.NoticeMessageVO;
import com.studyagent.model.vo.PageResult;

import java.util.List;

/**
 * 站内通知 业务接口。
 *
 * <p>本周只做"列表 + 已读"，推送触发放到第 4 周（RocketMQ + 微信订阅消息）。
 * 但 {@link #push(Long, String, String, String, Long)} 先在这里定义好：
 * 第 4 周的消费者只需要调它，接口层不用改。</p>
 */
public interface NoticeMessageService extends IService<NoticeMessage> {

    /** 分页查询通知（可按类型、已读状态过滤） */
    PageResult<NoticeMessageVO> pageNotices(Long userId, PageQuery query, String type, Integer readFlag);

    /** 未读数量（PC 端角标 / 小程序 tabBar 红点） */
    long unreadCount(Long userId);

    /** 标记单条已读 */
    void markRead(Long userId, Long id);

    /** 批量标记已读，返回实际更新条数 */
    int markReadBatch(Long userId, List<Long> ids);

    /** 全部标记已读，返回实际更新条数 */
    int markAllRead(Long userId);

    /** 逻辑删除单条通知 */
    void deleteNotice(Long userId, Long id);

    /**
     * 写一条站内通知（供定时任务与 MQ 消费者调用）。
     *
     * @param userId  接收用户
     * @param type    提醒/复习/报告
     * @param title   标题
     * @param content 内容
     * @param bizId   业务 ID（如报告 ID），前端据此跳转
     */
    NoticeMessage push(Long userId, String type, String title, String content, Long bizId);
}
