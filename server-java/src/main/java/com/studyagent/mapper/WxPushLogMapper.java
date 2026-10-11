package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.entity.WxPushLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 微信推送日志 Mapper
 */
@Mapper
public interface WxPushLogMapper extends BaseMapper<WxPushLog> {

    /** 最近若干条推送记录（验收/排查用，倒序） */
    @Select("""
            SELECT *
              FROM wx_push_log
             WHERE user_id = #{userId}
             ORDER BY id DESC
             LIMIT #{limit}
            """)
    List<WxPushLog> recent(@Param("userId") Long userId, @Param("limit") int limit);

    /** 统计某场景的推送结果（验收脚本用） */
    @Select("""
            SELECT COUNT(*)
              FROM wx_push_log
             WHERE user_id = #{userId}
               AND template_key = #{templateKey}
               AND status = #{status}
            """)
    long countByStatus(@Param("userId") Long userId,
                       @Param("templateKey") String templateKey,
                       @Param("status") String status);
}
