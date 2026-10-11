package com.studyagent.common.cache;

import java.io.Serializable;
import java.time.Duration;

/**
 * 缓存 key 与 TTL 的统一约定（第 4 周 Day 27 Cache Aside）。
 *
 * <h3>为什么 key 集中定义</h3>
 * <p>缓存的失效 bug 大多来自"写入端删的 key 和读取端写的 key 长得不一样"。
 * 把拼 key 的动作收敛到一个类里，读写两端只可能调用同一方法，从源头消灭这类漂移。</p>
 *
 * <h3>为什么计划详情的 key 里带 userId</h3>
 * <p>缓存命中时**不会**再走一遍归属校验（那本来就是省掉的那次查询）——
 * 如果 key 只带 planId，B 用户请求 A 的计划时，会把 A 查过并缓存的详情原样命中，
 * 缓存反而把越权防护绕过去了。把"归属"写进 key，命中隔离天然成立：
 * 每个用户只能命中自己名下的缓存，越权请求必然 miss → 回源 → 403。</p>
 *
 * <h3>TTL 的取舍</h3>
 * <p>三个场景都是"读多写极少"的数据，失效以**主动删除**为主、TTL 只做兜底：
 * 即使某条失效路径漏了，最坏情况也只脏 TTL 这么久。TTL 越短兜底越强、
 * 但对"写少读多"的数据没必要频繁回源 —— 按数据的变化频率从快到慢取 5/10/30 分钟。</p>
 */
public final class CacheKeys {

    /** 计划详情：包含任务统计，任务勾选/增删都会触发失效，TTL 取中间档 */
    public static final Duration PLAN_DETAIL_TTL = Duration.ofMinutes(10);

    /** 错题标签统计：新增/删除错题、AI 打标都会触发失效，变化相对频繁 */
    public static final Duration WRONG_TAGS_TTL = Duration.ofMinutes(5);

    /** 用户信息：几乎不变（唯一写点是微信登录同步昵称/绑定 openid），TTL 取最长 */
    public static final Duration USER_INFO_TTL = Duration.ofMinutes(30);

    public static String planDetail(Long userId, Long planId) {
        return "plan:detail:" + userId + ":" + planId;
    }

    public static String wrongTags(Long userId) {
        return "wrong:tags:" + userId;
    }

    public static String userInfo(Serializable userId) {
        return "user:info:" + userId;
    }

    private CacheKeys() {
    }
}
