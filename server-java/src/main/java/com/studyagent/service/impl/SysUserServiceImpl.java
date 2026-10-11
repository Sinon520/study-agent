package com.studyagent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.studyagent.common.cache.CacheKeys;
import com.studyagent.common.cache.RedisCacheHelper;
import com.studyagent.entity.SysUser;
import com.studyagent.mapper.SysUserMapper;
import com.studyagent.service.SysUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.Serializable;

/**
 * 用户 业务实现。
 *
 * <h3>用户信息缓存（Day 27）</h3>
 * <p>{@code getById} 是典型的"读多写极少"：登录/刷新令牌校验、微信推送查 openid、
 * 规划 Agent 校验用户，每个链路都要按主键读一次用户行。直接做 Cache Aside：</p>
 * <ul>
 *   <li>缓存 key {@code user:info:{id}}，TTL 30 分钟（唯一写点是微信登录同步
 *       昵称/头像，变化极少，取最长 TTL）；</li>
 *   <li>写侧唯一入口是 {@code updateById}（当前只有微信登录的昵称/头像同步用到），
 *       覆盖它统一做"提交后删缓存"，以后新增的资料修改接口天然走同一条失效路径；</li>
 *   <li>缓存的实体包含 openid —— 这段数据本来就存在 MySQL 里，
 *       Redis 属同一信任域的内网组件，不引入新的暴露面。</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements SysUserService {

    private final RedisCacheHelper cacheHelper;

    @Override
    public SysUser getById(Serializable id) {
        if (id == null) {
            return null;
        }
        // 回源仍走 super.getById（MyBatis-Plus 的标准查询），保证逻辑删等默认行为不变
        return cacheHelper.getOrLoad(CacheKeys.userInfo(id), SysUser.class,
                CacheKeys.USER_INFO_TTL, () -> super.getById(id));
    }

    @Override
    public boolean updateById(SysUser entity) {
        boolean updated = super.updateById(entity);
        if (updated && entity != null && entity.getId() != null) {
            cacheHelper.evictAfterCommit(CacheKeys.userInfo(entity.getId()));
        }
        return updated;
    }
}
