package com.studyagent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.studyagent.entity.WxSubscribe;
import com.studyagent.model.dto.WxSubscribeRequest;
import com.studyagent.model.vo.WxSubscribeVO;

import java.util.List;

/**
 * 微信订阅消息授权 业务接口（Day 25）。
 *
 * <p>微信的订阅消息是"**一次订阅 = 一次发送额度**"：用户点了同意，我们才有一条
 * 发送额度；发送成功扣一条；扣完要等用户再次授权。这张表就是这份额度账本。</p>
 */
public interface WxSubscribeService extends IService<WxSubscribe> {

    /**
     * 记录一次订阅授权结果（按 user_id + template_key 幂等覆盖）。
     *
     * @return 更新后的记录列表（前端可据此提示"已开启/未开启"）
     */
    List<WxSubscribeVO> record(Long userId, WxSubscribeRequest request);

    /** 当前用户全部订阅状态 */
    List<WxSubscribeVO> listMy(Long userId);

    /**
     * 最近一次授权是否为「同意」（不关心剩余额度）。
     *
     * <p>为什么授权状态与额度要分开判定：两者是**不同的终态原因** ——
     * 「未授权」说明用户从没同意/已拒绝，再投一万次也没用，需要产品侧引导授权；
     * 「额度用完」说明用户同意过但额度用尽，需要引导再次授权。
     * 混成一个 code，运维在日志里根本分不清该走哪条处理路径。</p>
     */
    boolean hasApproved(Long userId, String templateKey);

    /**
     * 原子扣减一次发送额度。
     *
     * @return true=扣减成功；false=额度不足（终态，不应重试）
     */
    boolean consumeQuota(Long userId, String templateKey);
}
