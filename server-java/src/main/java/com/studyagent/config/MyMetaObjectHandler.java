package com.studyagent.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 自动填充 createTime / updateTime。
 * <p>注意：</p>
 * <ul>
 *   <li>填入的字段是 <b>驼峰 Java 字段名</b>，不是数据库列名（MyBatis-Plus 会自动做下划线转换）；</li>
 *   <li>用 {@code metaObject.hasSetter(...)} 先判断，因为 13 张表里
 *       {@code wrong_question_tag} / {@code study_record} / {@code review_report} /
 *       {@code notice_message} / {@code kb_chunk} / {@code chat_message} 等表只有 create_time 没有 update_time，
 *       实体里也就没有该字段，直接填会抛异常；</li>
 *   <li>只在字段为 null 时填充，避免覆盖业务显式设置的值。</li>
 * </ul>
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    private static final String CREATE_TIME = "createTime";
    private static final String UPDATE_TIME = "updateTime";

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();

        if (metaObject.hasSetter(CREATE_TIME) && getFieldValByName(CREATE_TIME, metaObject) == null) {
            this.strictInsertFill(metaObject, CREATE_TIME, LocalDateTime.class, now);
        }
        if (metaObject.hasSetter(UPDATE_TIME) && getFieldValByName(UPDATE_TIME, metaObject) == null) {
            this.strictInsertFill(metaObject, UPDATE_TIME, LocalDateTime.class, now);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        if (metaObject.hasSetter(UPDATE_TIME)) {
            // 更新时间每次都要覆盖，所以用 setFieldValByName 而不是 strict 判空
            this.setFieldValByName(UPDATE_TIME, LocalDateTime.now(), metaObject);
        }
    }
}
