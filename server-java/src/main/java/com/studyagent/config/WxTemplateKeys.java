package com.studyagent.config;

import java.util.List;

/**
 * 微信订阅消息的场景 key（对应 {@code wx.templates.*} 的键）。
 *
 * <p>为什么用"场景 key"而不是直接写模板 ID：模板 ID 是**运行时配置**
 * （在小程序后台申请，换模板/换环境都会变），业务代码里散落模板 ID 意味着
 * 换模板要改一堆代码、且历史消息里带着旧 ID。统一成场景 key 后：
 * 消息只描述"要发什么业务通知"，消费端按当前配置解析成真实模板 ID。</p>
 */
public final class WxTemplateKeys {

    /** 学习提醒：每天 8:00，当日有待办任务 */
    public static final String REMINDER = "reminder";

    /** 错题复习：每天 20:00，未掌握错题堆积到阈值 */
    public static final String REVIEW = "review";

    /** 复盘报告：周报生成完成后通知 */
    public static final String REPORT = "report";

    /** 全部合法场景（上报表单校验白名单） */
    public static final List<String> ALL = List.of(REMINDER, REVIEW, REPORT);

    private WxTemplateKeys() {
    }
}
