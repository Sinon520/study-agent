package com.studyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.studyagent.entity.NoticeMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 站内通知 Mapper
 */
@Mapper
public interface NoticeMessageMapper extends BaseMapper<NoticeMessage> {
}