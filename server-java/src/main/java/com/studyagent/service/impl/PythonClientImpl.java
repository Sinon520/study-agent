package com.studyagent.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyagent.common.Result;
import com.studyagent.common.exception.BizException;
import com.studyagent.config.InternalProperties;
import com.studyagent.model.vo.KbIngestResultVO;
import com.studyagent.service.PythonClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

/**
 * Python AI 服务客户端实现。
 *
 * <p>两个关键约定：</p>
 * <ol>
 *   <li><b>透传 traceId</b>：把当前 MDC 里的 traceId 放进 {@code X-Trace-Id} 请求头，
 *       Python 侧 loguru 会自动带上同一个值，两端日志可串起来。</li>
 *   <li><b>超时时间要长</b>：首次解析会触发 BGE-M3 加载（CPU 上数十秒），
 *       默认 30 秒必然超时。这里按配置默认 600 秒。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PythonClientImpl implements PythonClient {

    private final InternalProperties internalProperties;
    private final ObjectMapper objectMapper;

    private RestClient client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(internalProperties.getPythonTimeoutSeconds());
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(timeout);

        return RestClient.builder()
                .baseUrl(internalProperties.getPythonBaseUrl())
                .requestFactory(factory)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("X-Internal-Token", internalProperties.getToken())
                .build();
    }

    @Override
    public KbIngestResultVO ingest(Long documentId, Long userId, String filePath,
                                   Integer chunkSize, Integer chunkOverlap) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("documentId", documentId);
        body.put("userId", userId);
        body.put("filePath", filePath);
        if (chunkSize != null) {
            body.put("chunkSize", chunkSize);
        }
        if (chunkOverlap != null) {
            body.put("chunkOverlap", chunkOverlap);
        }

        JsonNode data = post("/rag/ingest", body, "文档解析入库");
        KbIngestResultVO vo = new KbIngestResultVO();
        vo.setDocumentId(documentId);
        vo.setPageCount(asInt(data, "pageCount"));
        vo.setChunkCount(asInt(data, "chunkCount"));
        vo.setVectorCount(asInt(data, "vectorCount"));
        vo.setChunkSize(asInt(data, "chunkSize"));
        vo.setChunkOverlap(asInt(data, "chunkOverlap"));
        vo.setEmbedSeconds(asDouble(data, "embedSeconds"));
        vo.setTotalSeconds(asDouble(data, "totalSeconds"));
        return vo;
    }

    @Override
    public void deleteVectors(Long documentId) {
        try {
            String raw = traceIdHeader(client().delete().uri("/rag/document/{id}", documentId))
                    .retrieve()
                    .body(String.class);
            log.info("已通知 Python 清理向量 documentId={} resp={}", documentId, raw);
        } catch (Exception e) {
            // 向量清理失败不能阻塞文档删除：向量残留只会浪费空间，
            // 而且检索时还会被 user_id 过滤，不会串数据
            log.warn("通知 Python 清理向量失败 documentId={}（不阻塞删除）: {}", documentId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 内部

    private JsonNode post(String path, Map<String, Object> body, String action) {
        try {
            String raw = traceIdHeader(
                    client().post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body))
                    .retrieve()
                    .body(String.class);

            JsonNode root = raw == null ? null : objectMapper.readTree(raw);
            if (root == null) {
                throw new BizException(Result.CODE_FAIL, action + "失败：Python 返回空响应");
            }
            int code = root.path("code").asInt(0);
            if (code != 0) {
                String msg = root.path("msg").asText("未知错误");
                log.error("{}失败: code={} msg={}", action, code, msg);
                throw new BizException(Result.CODE_FAIL, action + "失败：" + msg);
            }
            return root;
        } catch (BizException e) {
            throw e;
        } catch (org.springframework.web.client.RestClientResponseException e) {
            String detail = e.getResponseBodyAsString();
            log.error("{}失败: HTTP {} body={}", action, e.getStatusCode().value(), detail);
            throw new BizException(Result.CODE_FAIL,
                    action + "失败（Python HTTP " + e.getStatusCode().value() + "）：" + truncate(detail));
        } catch (Exception e) {
            log.error("{}异常", action, e);
            throw new BizException(Result.CODE_FAIL,
                    action + "失败：无法连接 Python 服务（" + internalProperties.getPythonBaseUrl() + "）——" + e.getMessage());
        }
    }

    /**
     * 给请求加 traceId 头，实现跨语言链路追踪。
     *
     * <p>泛型方法而不是固定类型：RestClient 的 {@code delete()} 返回
     * {@code RequestBodyUriSpec}，而带 body 的 POST 需要 {@code RequestBodySpec}，
     * 两者都实现 {@code RequestHeadersSpec}。用上界泛型一套代码通吃。</p>
     */
    private <T extends org.springframework.web.client.RestClient.RequestHeadersSpec<?>> T traceIdHeader(T spec) {
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            spec.header("X-Trace-Id", traceId);
        }
        return spec;
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...";
    }

    private Integer asInt(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private Double asDouble(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asDouble();
    }
}
