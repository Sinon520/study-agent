package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.entity.WxSubscribe;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 微信订阅授权 Mapper
 */
@Mapper
public interface WxSubscribeMapper extends BaseMapper<WxSubscribe> {

    /** 取某用户某个场景的授权记录（幂等写入的快速路径） */
    @Select("""
            SELECT *
              FROM wx_subscribe
             WHERE user_id = #{userId} AND template_key = #{templateKey}
             LIMIT 1
            """)
    WxSubscribe selectByKey(@Param("userId") Long userId,
                            @Param("templateKey") String templateKey);

    /** 批量取授权记录（列表接口用） */
    @Select("""
            SELECT *
              FROM wx_subscribe
             WHERE user_id = #{userId}
               AND template_key IN
                   <foreach collection="keys" item="key" open="(" separator="," close=")">
                       #{key}
                   </foreach>
            """)
    List<WxSubscribe> selectByKeys(@Param("userId") Long userId, @Param("keys") List<String> keys);

    /** 取当前用户全部授权记录 */
    @Select("""
            SELECT *
              FROM wx_subscribe
             WHERE user_id = #{userId}
             ORDER BY id
            """)
    List<WxSubscribe> selectByUser(@Param("userId") Long userId);

    /**
     * 原子扣减一次发送额度。
     *
     * <p>为什么用 SQL 而不是"先查后改"：两个消费者并发推送同一用户同一模板时，
     * 先查后改的额度检查赢不了 TOCTOU 竞态（踩坑 #20 的教训）。
     * {@code accept_count > 0} 写进 WHERE，扣减交给数据库保证。
     *
     * @return 影响行数：1=扣减成功，0=额度不足
     */
    @Update("""
            UPDATE wx_subscribe
               SET accept_count = accept_count - 1,
                   update_time  = NOW()
             WHERE user_id      = #{userId}
               AND template_key = #{templateKey}
               AND accept_count > 0
            """)
    int consumeQuota(@Param("userId") Long userId, @Param("templateKey") String templateKey);
}
