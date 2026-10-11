package com.studyagent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.common.Result;
import com.studyagent.common.exception.BizException;
import com.studyagent.config.WxProperties;
import com.studyagent.entity.WxSubscribe;
import com.studyagent.mapper.WxSubscribeMapper;
import com.studyagent.model.dto.WxSubscribeRequest;
import com.studyagent.model.vo.WxSubscribeVO;
import com.studyagent.service.WxSubscribeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 微信订阅消息授权 业务实现（Day 25）。
 *
 * <h3>为什么单独建一张授权表，而不是推送时直接调微信</h3>
 * <ol>
 *   <li><b>额度是稀缺资源</b>：微信的语义是"一次订阅 = 一次发送额度"。
 *       不记账就不知道还能发几条，也无法在额度耗尽前引导用户再次授权。</li>
 *   <li><b>避免无效调用</b>：没授权/额度耗尽的用户在**调度阶段**就被过滤掉，
 *       不会白调微信接口（微信对无效调用有惩罚，43101 就是"用户拒收"）。</li>
 *   <li><b>可审计</b>：配合 {@code wx_push_log} 能回答"这条提醒到底发没发出去、
 *       为什么没发" —— 定时推送类功能最容易被追问的就是这个。</li>
 * </ol>
 *
 * <p>注意：这张表只是**服务端账本**，真实的额度由微信侧维护；
 * 因此这里只用于"是否值得发"，最终以微信返回为准（终态错误码会回写跳过记录）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WxSubscribeServiceImpl extends ServiceImpl<WxSubscribeMapper, WxSubscribe>
        implements WxSubscribeService {

    /** 额度上限：防止前端重复上报把账本刷到天上 */
    private static final int MAX_ACCEPT_COUNT = 20;

    private final WxProperties wxProperties;

    @Override
    public List<WxSubscribeVO> record(Long userId, WxSubscribeRequest request) {
        List<WxSubscribeVO> result = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (WxSubscribeRequest.Item item : request.getItems()) {
            String templateKey = requireTemplateKey(item.getTemplateKey());
            boolean accepted = "accept".equalsIgnoreCase(item.getStatus());

            WxSubscribe existing = baseMapper.selectByKey(userId, templateKey);
            if (existing != null) {
                existing.setStatus(accepted ? 1 : 0);
                if (StringUtils.hasText(item.getTemplateId())) {
                    existing.setTemplateId(item.getTemplateId());
                }
                existing.setAuthorizeTime(now);
                if (accepted) {
                    int remaining = existing.getAcceptCount() == null ? 0 : existing.getAcceptCount();
                    existing.setAcceptCount(Math.min(MAX_ACCEPT_COUNT, remaining + 1));
                }
                updateById(existing);
                result.add(WxSubscribeVO.from(existing));
                log.info("订阅授权更新 userId={} templateKey={} 同意={} 剩余={}",
                        userId, templateKey, accepted, existing.getAcceptCount());
            } else {
                WxSubscribe record = new WxSubscribe();
                record.setUserId(userId);
                record.setTemplateKey(templateKey);
                record.setTemplateId(item.getTemplateId());
                record.setStatus(accepted ? 1 : 0);
                record.setAcceptCount(accepted ? 1 : 0);
                record.setAuthorizeTime(now);
                try {
                    save(record);
                } catch (DuplicateKeyException e) {
                    // 并发：另一请求刚插了同一 (user, template)，查回来走更新分支
                    WxSubscribe again = baseMapper.selectByKey(userId, templateKey);
                    if (again == null) {
                        throw new BizException(Result.CODE_FAIL, "订阅授权写入失败，请重试");
                    }
                    again.setStatus(accepted ? 1 : 0);
                    again.setAuthorizeTime(now);
                    if (accepted) {
                        int remaining = again.getAcceptCount() == null ? 0 : again.getAcceptCount();
                        again.setAcceptCount(Math.min(MAX_ACCEPT_COUNT, remaining + 1));
                    }
                    updateById(again);
                    record = again;
                }
                result.add(WxSubscribeVO.from(record));
            }
        }
        log.info("订阅授权已记录 userId={} 条数={}", userId, result.size());
        return result;
    }

    @Override
    public List<WxSubscribeVO> listMy(Long userId) {
        return baseMapper.selectByUser(userId).stream().map(WxSubscribeVO::from).toList();
    }

    @Override
    public boolean hasApproved(Long userId, String templateKey) {
        WxSubscribe record = baseMapper.selectByKey(userId, requireTemplateKey(templateKey));
        return record != null && record.getStatus() != null && record.getStatus() == 1;
    }

    @Override
    public boolean consumeQuota(Long userId, String templateKey) {
        // 原子扣减：WHERE accept_count > 0 保证不会扣成负数，也挡住并发超发
        return baseMapper.consumeQuota(userId, requireTemplateKey(templateKey)) > 0;
    }

    // ------------------------------------------------------------------ 内部

    /** 场景 key 白名单校验：只接受配置里存在的模板，防止业务代码传错或伪造 */
    private String requireTemplateKey(String templateKey) {
        String key = templateKey == null ? "" : templateKey.trim();
        if (!wxProperties.getTemplates().containsKey(key)) {
            throw new BizException(Result.CODE_BAD_REQUEST, "未知的订阅模板场景：" + templateKey
                    + "，可选 " + wxProperties.getTemplates().keySet());
        }
        return key;
    }
}
