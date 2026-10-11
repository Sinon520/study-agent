package com.studyagent.common.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Cache Aside 的统一实现（第 4 周 Day 27）。
 *
 * <h3>Cache Aside 的三个纪律，以及本类怎么处理</h3>
 * <ol>
 *   <li><b>缓存绝不接管可用性</b>：Redis 挂掉/超时/脏数据都不能让业务接口失败 ——
 *       读异常按 miss 处理（回源查库），写异常只告警。缓存是加速器，不是数据库。</li>
 *   <li><b>写库后删缓存（而不是更新缓存）</b>：更新缓存要枚举"所有可能读过它的场景"，
 *       而且并发写会互相覆盖；删除只产生一次回源，简单且不会错。
 *       删除时机选在<b>事务提交后</b>：先删后提交的话，并发读会在提交前
 *       把旧值重新读进缓存（"删除即失效"变成"删除即回填旧值"）。</li>
 *   <li><b>坏值要能自愈</b>：序列化格式变更/脏数据导致反序列化失败时，
 *       删 key 回源，而不是把异常抛给用户。</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCacheHelper {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 读缓存；miss 则回源并回填。回源返回 {@code null} 不缓存
     * （避免把"不存在"也缓存下来 —— 负缓存需要额外的失效通知，场景不划算）。
     */
    public <T> T getOrLoad(String key, Class<T> type, Duration ttl, Supplier<T> loader) {
        String cached = safeGet(key);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, type);
            } catch (Exception e) {
                log.warn("缓存反序列化失败，删除后回源 key={}: {}", key, e.getMessage());
                safeDelete(key);
            }
        }
        T value = loader.get();
        if (value != null) {
            safeSet(key, value, ttl);
        }
        return value;
    }

    /** 泛型版本（如 {@code List<String>}）：Jackson 的 Class 参数表达不了泛型，用 TypeReference。 */
    public <T> T getOrLoad(String key, TypeReference<T> type, Duration ttl, Supplier<T> loader) {
        String cached = safeGet(key);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, type);
            } catch (Exception e) {
                log.warn("缓存反序列化失败，删除后回源 key={}: {}", key, e.getMessage());
                safeDelete(key);
            }
        }
        T value = loader.get();
        if (value != null) {
            safeSet(key, value, ttl);
        }
        return value;
    }

    /** 立即删除（无事务上下文时用；有事务请用 {@link #evictAfterCommit}） */
    public void evict(String... keys) {
        for (String key : keys) {
            safeDelete(key);
        }
    }

    /**
     * 事务提交后删除：提交成功才删，回滚不删（数据没变，缓存仍然对）；
     * 且删动作发生在提交之后，不会出现"删完又被并发读回填旧值"的窗口。
     * 无事务上下文时立即删。
     */
    public void evictAfterCommit(String... keys) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(keys);
                }
            });
        } else {
            evict(keys);
        }
    }

    // ------------------------------------------------------------------ 内部

    private String safeGet(String key) {
        try {
            return stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis 读取失败，按缓存未命中处理 key={}: {}", key, e.getMessage());
            return null;
        }
    }

    private void safeSet(String key, Object value, Duration ttl) {
        try {
            stringRedisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
            log.debug("缓存回填 key={} ttl={}s", key, ttl.toSeconds());
        } catch (Exception e) {
            log.warn("Redis 写入失败（忽略，仅放弃本次缓存）key={}: {}", key, e.getMessage());
        }
    }

    private void safeDelete(String key) {
        try {
            stringRedisTemplate.delete(key);
            log.debug("缓存失效 key={}", key);
        } catch (Exception e) {
            log.warn("Redis 删除失败（将依赖 TTL 兜底）key={}: {}", key, e.getMessage());
        }
    }
}
