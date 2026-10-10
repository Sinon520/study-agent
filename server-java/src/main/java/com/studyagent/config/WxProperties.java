package com.studyagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
     * 是否启用 mock 登录。
     * <p>第 1 周还没有真实 AppID，开启后 {@code /auth/wx-login} 会根据 code
     * 派生一个稳定的假 openid 走通全流程；第 4 周拿到真实 AppID 后置为 false。</p>
     */
    private boolean mockEnabled = true;
}
