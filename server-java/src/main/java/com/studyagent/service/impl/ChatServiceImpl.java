package com.studyagent.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.Result;
import com.studyagent.common.exception.BizException;
import com.studyagent.config.InternalProperties;
import com.studyagent.entity.ChatMessage;
import com.studyagent.entity.ChatSession;
import com.studyagent.model.dto.ChatAskRequest;
import com.studyagent.service.ChatMessageService;
import com.studyagent.service.ChatService;
import com.studyagent.service.ChatSessionService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答服务实现：把 Python 的 SSE 流原样透传给前端，并在结束后落库。
 *
 * <h3>为什么用 HttpURLConnection 而不是 RestClient</h3>
 * Spring 的 {@code RestClient} 面向"一次性拿到完整响应体"的模型，
 * 虽然支持 {@code body(InputStream.class)}，但在需要**即时**读取增量、
 * 且**不能有中间缓冲**的 SSE 场景下，直接用 {@link HttpURLConnection}
 * 读 {@code InputStream} 更可控：
 * <ul>
 *   <li>可以逐行读、逐行转发，完全掌握刷新时机；</li>
 *   <li>避免任何隐式的 HttpResponse 包装层做缓冲；</li>
 *   <li>不需要为了流式而引入 WebClient（本项目没上 WebFlux）。</li>
 * </ul>
 *
 * <h3>落库时机</h3>
 * 边转发边把帧解析出来累积文本，流结束后写一条 user、一条 assistant 到
 * {@code chat_message}（citations 存 JSON）。这样即使前端中途刷新，
 * 已生成的内容也不会丢。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private static final String PYTHON_CHAT_PATH = "/rag/chat";

    private final InternalProperties internalProperties;
    private final ChatSessionService chatSessionService;
    private final ChatMessageService chatMessageService;
    private final ObjectMapper objectMapper;

    @Override
    public void askStream(Long userId, ChatAskRequest request, HttpServletResponse response) throws IOException {
        long started = System.currentTimeMillis();

        // 1) 会话准备：没有 sessionId 就先建一个，标题取问题前 30 字
        ChatSession session = resolveSession(userId, request);

        // 2) 组装发给 Python 的请求体（userId 来自 JWT，不由前端决定）
        Map<String, Object> pythonBody = new HashMap<>();
        pythonBody.put("userId", userId);
        pythonBody.put("question", request.getQuestion());
        pythonBody.put("sessionId", session.getId());
        if (request.getDocumentIds() != null && !request.getDocumentIds().isEmpty()) {
            pythonBody.put("documentIds", request.getDocumentIds());
        }

        // 3) 先落 user 消息：用户的问题一定要留下，即使后面生成失败
        saveMessage(session, userId, "user", request.getQuestion(), null, null);

        // 4) 打开到 Python 的连接
        HttpURLConnection connection = openConnection(pythonBody);
        int status = connection.getResponseCode();
        if (status != HttpURLConnection.HTTP_OK) {
            String errorBody = readAll(connection.getErrorStream());
            log.error("Python 问答接口返回 HTTP {} body={}", status, truncate(errorBody));
            connection.disconnect();
            // 关键：此时 SSE 还没开始写，仍然可以返回正常的 JSON 错误，
            // 前端 axios 拦截器能给出明确提示（一旦开了流就只能发 error 帧了）
            throw new BizException(Result.CODE_FAIL, buildPythonErrorMessage(status, errorBody));
        }

        // 5) 开始写 SSE
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/event-stream; charset=UTF-8");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-cache, no-transform");
        response.setHeader("Connection", "keep-alive");
        // 让 Nginx 不要缓冲：否则"流式"会变成"一次性返回"，打字机效果全没了
        response.setHeader("X-Accel-Buffering", "no");

        StringBuilder answer = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        String citationsJson = null;

        try (OutputStream out = response.getOutputStream();
             InputStream in = connection.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

            StringBuilder frameBuffer = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                // 逐行转发，并在遇到空行（帧结束）时解析这一帧用于落库
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                if (line.isEmpty()) {
                    out.write('\n');
                    out.flush();

                    parseFrame(frameBuffer.toString(), answer, reasoning);
                    // citations 帧要单独取出来存 JSON
                    String captured = captureCitations(frameBuffer.toString());
                    if (captured != null) {
                        citationsJson = captured;
                    }
                    frameBuffer.setLength(0);
                } else {
                    frameBuffer.append(line).append('\n');
                }
            }
            out.write('\n');
            out.flush();
        } catch (IOException e) {
            // 前端主动断开（AbortController）会走到这里，属于正常情况，不要打 error 噪音
            log.info("SSE 转发中断（可能前端主动取消）sessionId={} 原因={}", session.getId(), e.getMessage());
        } finally {
            connection.disconnect();
        }

        // 6) 落 assistant 消息
        //
        // 本项目约定：SSE 问答链路的落库由 Java **独占**完成 —— user 消息在开流前写、
        // assistant 消息在流结束后写（连接中断时也保留已生成的部分内容）。
        // 历史踩坑：Python 侧曾在发 [DONE] 之前调用 /internal/chat/messages 也写一遍，
        // 而这里的保存没有幂等守卫、且总在对方插入之后执行 —— 结果每条 assistant
        // 消息都存了两份（同秒、内容完全相同）。"同 session 同 role 同内容不重复插"
        // 挡不住这种竞态（两边都在对方插入前完成了存在性检查），根治靠写入口唯一化。
        // 详见 server-ai/app/routers/rag.py 的说明与 docs/踩坑记录.md。
        String answerText = answer.toString();
        boolean assistantSaved = false;
        if (StringUtils.hasText(answerText) || StringUtils.hasText(reasoning.toString())) {
            saveMessage(session, userId, "assistant", answerText, reasoning.toString(), citationsJson);
            assistantSaved = true;
        }
        chatSessionService.updateById(buildSessionPatch(session, answerText, assistantSaved));

        log.info("问答完成 userId={} sessionId={} 回答 {} 字 思考 {} 字 引用={} 耗时={}ms",
                userId, session.getId(), answerText.length(), reasoning.length(),
                citationsJson == null ? 0 : "有",
                System.currentTimeMillis() - started);
    }

    // ------------------------------------------------------------------ 会话

    private ChatSession resolveSession(Long userId, ChatAskRequest request) {
        if (request.getSessionId() != null) {
            ChatSession session = chatSessionService.getById(request.getSessionId());
            if (session == null) {
                throw new BizException(Result.CODE_BAD_REQUEST, "会话不存在：" + request.getSessionId());
            }
            if (!session.getUserId().equals(userId)) {
                log.warn("水平越权拦截：userId={} 试图使用 sessionId={}（属于 userId={}）",
                        userId, request.getSessionId(), session.getUserId());
                throw new BizException(Result.CODE_FORBIDDEN, "无权访问该会话");
            }
            return session;
        }

        ChatSession session = new ChatSession();
        session.setUserId(userId);
        session.setTitle(truncate(request.getQuestion(), 30));
        session.setMessageCount(0);
        session.setLastTime(LocalDateTime.now());
        chatSessionService.save(session);
        log.info("新建问答会话 userId={} sessionId={}", userId, session.getId());
        return session;
    }

    private ChatSession buildSessionPatch(ChatSession session, String answer, boolean assistantSaved) {
        ChatSession patch = new ChatSession();
        patch.setId(session.getId());
        // 按本轮实际写入的消息行数累加：user 必写 1 条；assistant 全空（生成彻底失败）时不写行
        patch.setMessageCount((session.getMessageCount() == null ? 0 : session.getMessageCount())
                + (assistantSaved ? 2 : 1));
        patch.setLastTime(LocalDateTime.now());
        // 首轮问答结束后用问题做标题（如果标题还是默认值）
        if ("新会话".equals(session.getTitle()) && StringUtils.hasText(answer)) {
            patch.setTitle(truncate(answer, 30));
        }
        return patch;
    }

    private void saveMessage(ChatSession session, Long userId, String role,
                            String content, String reasoning, String citationsJson) {
        try {
            ChatMessage message = new ChatMessage();
            message.setSessionId(session.getId());
            message.setUserId(userId);
            message.setRole(role);
            message.setContent(content);
            message.setReasoningContent(reasoning);
            if (citationsJson != null) {
                message.setCitations(citationsJson);
            }
            message.setTokenCount(0);
            chatMessageService.save(message);
        } catch (Exception e) {
            // 落库失败不影响已经流给用户的内容
            log.error("问答消息落库失败 sessionId={} role={}", session.getId(), role, e);
        }
    }

    // ------------------------------------------------------------------ HTTP

    private HttpURLConnection openConnection(Map<String, Object> body) throws IOException {
        String url = internalProperties.getPythonBaseUrl().replaceAll("/+$", "") + PYTHON_CHAT_PATH;
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(10_000);
        // 读超时放宽：首次请求要触发本地 BGE-M3 加载（CPU 上可能几十秒）
        connection.setReadTimeout(internalProperties.getPythonTimeoutSeconds() * 1000);
        connection.setRequestProperty("Content-Type", MediaType.APPLICATION_JSON_VALUE);
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setRequestProperty("X-Internal-Token", internalProperties.getToken());
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            connection.setRequestProperty("X-Trace-Id", traceId);
        }
        // 关闭 JDK 的响应缓存，确保增量立刻可读
        connection.setUseCaches(false);

        try (OutputStream out = connection.getOutputStream()) {
            out.write(objectMapper.writeValueAsBytes(body));
            out.flush();
        }
        return connection;
    }

    private String readAll(InputStream stream) {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 把 Python 的错误响应翻译成人话。
     *
     * <p>最能帮到用户的场景是"模型还没下完"（Python 返回 503 + code 5031），
     * 这时应该直接告诉用户去下模型，而不是丢一句"服务器内部错误"。</p>
     */
    private String buildPythonErrorMessage(int status, String errorBody) {
        if (StringUtils.hasText(errorBody)) {
            try {
                JsonNode root = objectMapper.readTree(errorBody);
                String msg = root.path("msg").asText("");
                if (StringUtils.hasText(msg)) {
                    if (msg.contains("模型未就绪") || msg.contains("未下载")) {
                        return "本地向量模型尚未就绪，请先执行 server-ai/scripts/download_models.py 完成模型下载：" + msg;
                    }
                    return "AI 服务返回错误：" + msg;
                }
            } catch (Exception ignored) {
                // 不是 JSON，走下面的兜底
            }
        }
        if (status == 503) {
            return "AI 服务暂时不可用（本地模型未就绪或正在加载），请稍后重试";
        }
        return "AI 服务返回 HTTP " + status + "：" + truncate(errorBody);
    }

    // ------------------------------------------------------------------ SSE 解析

    /** 从一帧里抽出 event 名与 data 文本 */
    private Frame parse(String rawFrame) {
        String event = "message";
        List<String> dataLines = new ArrayList<>();
        for (String raw : rawFrame.split("\n")) {
            String line = raw.stripTrailing();
            if (line.startsWith("event:")) {
                event = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                dataLines.add(line.substring(5).replaceFirst("^ ", ""));
            }
        }
        return new Frame(event, String.join("\n", dataLines));
    }

    private void parseFrame(String rawFrame, StringBuilder answer, StringBuilder reasoning) {
        if (!StringUtils.hasText(rawFrame)) {
            return;
        }
        Frame frame = parse(rawFrame);
        if (!StringUtils.hasText(frame.data()) || "[DONE]".equals(frame.data())) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(frame.data());
            String text = node.path("text").asText("");
            if ("reasoning".equals(frame.event())) {
                reasoning.append(text);
            } else if ("message".equals(frame.event()) || "content".equals(frame.event())) {
                answer.append(text);
            }
        } catch (Exception ignored) {
            // 非 JSON 帧（例如纯文本探针）不影响落库
        }
    }

    /** 提取 citations 帧的原始 JSON（存进 chat_message.citations） */
    private String captureCitations(String rawFrame) {
        if (!StringUtils.hasText(rawFrame)) {
            return null;
        }
        Frame frame = parse(rawFrame);
        if (!"citations".equals(frame.event()) || !StringUtils.hasText(frame.data())) {
            return null;
        }
        try {
            // 校验一下是不是合法 JSON，避免把脏数据写进 JSON 列
            objectMapper.readTree(frame.data());
            return frame.data();
        } catch (Exception e) {
            log.warn("citations 帧不是合法 JSON，已忽略：{}", truncate(frame.data()));
            return null;
        }
    }

    private record Frame(String event, String data) {
    }

    private String truncate(String text) {
        return truncate(text, 300);
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
