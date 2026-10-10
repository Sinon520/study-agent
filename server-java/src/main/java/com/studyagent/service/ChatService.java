package com.studyagent.service;

import com.studyagent.model.dto.ChatAskRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 问答服务（Java 侧编排：权限校验 + 转发 Python SSE + 落库）。
 */
public interface ChatService {

    /**
     * 处理一次问答请求，并以 SSE 流式写回给前端。
     *
     * <p>为什么 Java 要做透传而不是让前端直连 Python：</p>
     * <ul>
     *   <li>鉴权统一：前端只需要一个域名、一套 JWT；</li>
     *   <li>Python 只对内网暴露，不承担公网流量；</li>
     *   <li>落库与审计在 Java 侧一处完成。</li>
     * </ul>
     *
     * @param userId   当前登录用户（来自 JWT，不接受前端传入，防越权）
     * @param request  问答请求
     * @param response 用于写 SSE 的响应对象
     */
    void askStream(Long userId, ChatAskRequest request, HttpServletResponse response) throws IOException;
}
