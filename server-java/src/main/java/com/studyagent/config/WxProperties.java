package com.studyagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信小程序配置，对应 application.yml 中的 {@code wx.*}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "wx")
public class WxProperties {

    /** 小程序 AppID */
    private String appid = "wx_test_appid";

    /** 小程序 AppSecret */
    private String secret = "wx_test_secret";

    /** code2session 接口地址 */
    private String code2sessionUrl = "https://api.weixin.qq.com/sns/jscode2session";

    /**
     * 是否启用 mock 模式。
     * <p>第 1 周没有真实 AppID 时：{@code /auth/wx-login} 按 code 派生稳定假 openid；
     * 第 4 周订阅消息同理——true 时跳过真实微信 API 调用，走模拟发送并记
     * {@code wx_push_log.mock=1}，链路其余环节（授权校验/次数扣减/日志）与真实一致，
     * 拿到真实 AppID 后置为 false 即无缝切换。</p>
     */
    private boolean mockEnabled = true;

    /**
     * 订阅消息模板：场景 key → 模板 ID。
     *
     * <p>三个场景（对应执行计划 Day 25~26）：</p>
     * <ul>
     *   <li>{@code reminder}：学习提醒（每天 8:00，每日有待办任务）</li>
     *   <li>{@code review}：错题复习（每天 20:00，未掌握错题堆积）</li>
     *   <li>{@code report}：复盘报告已生成（Day 24 报告链路触发）</li>
     * </ul>
     *
     * <p>占位值在小程序后台申请到真实模板后经环境变量替换（见 application.yml）。</p>
     */
    private Map<String, String> templates = new LinkedHashMap<>(Map.of(
            WxTemplateKeys.REMINDER, "wx-template-reminder-placeholder",
            WxTemplateKeys.REVIEW, "wx-template-review-placeholder",
            WxTemplateKeys.REPORT, "wx-template-report-placeholder"
    ));

    /** 学习提醒定时 cron（默认每天 8:00） */
    private String reminderCron = "0 0 8 * * *";

    /** 错题复习提醒定时 cron（默认每天 20:00） */
    private String reviewCron = "0 0 20 * * *";

    /** 定时推送总开关（本地调试/演示时可关；手动触发接口不受影响） */
    private boolean pushSchedulerEnabled = true;

    /** 未掌握错题达到多少道触发复习提醒 */
    private int reviewWrongThreshold = 3;

    /** subscribeMessage.send 接口地址 */
    private String subscribeSendUrl = "https://api.weixin.qq.com/cgi-bin/message/subscribe/send";

    /** access_token 获取接口地址 */
    private String accessTokenUrl = "https://api.weixin.qq.com/cgi-bin/token";

    /**
     * 小程序版本：{@code formal}（正式版）/ {@code trial}（体验版）/ {@code developer}（开发版）。
     * <p>本地用测试号 + 开发者工具时填 {@code trial}，上线前切成 {@code formal}。</p>
     */
    private String miniprogramState = "trial";
}
