package io.knifer.freebox.net.http.handler;

import cn.hutool.http.HttpStatus;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpExchange;
import io.knifer.freebox.constant.BaseValues;
import io.knifer.freebox.context.Context;
import io.knifer.freebox.model.domain.ClientInfo;
import io.knifer.freebox.model.common.tvbox.SourceBean;
import io.knifer.freebox.model.common.tvbox.VodInfo;
import io.knifer.freebox.model.common.tvbox.AbsXml;
import io.knifer.freebox.model.common.tvbox.AbsSortXml;
import io.knifer.freebox.model.s2c.*;
import io.knifer.freebox.spider.template.SpiderTemplate;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Emacs 前端 HTTP API 处理器
 *
 * 提供 REST API 供 Emacs 客户端调用，实现搜索、分类、详情、播放等功能。
 * 提供的 API 端点：
 * - GET /api/clients   - 列出所有已保存的 CATVOD_SPIDER 客户端配置
 * - GET /api/sources   - 获取源列表（可选参数: clientId）
 * - GET /api/search    - 搜索视频（参数: sourceKey, keyword, 可选: clientId）
 * - GET /api/categories - 获取分类列表（参数: sourceKey, 可选: clientId）
 * - GET /api/category  - 获取分类内容（参数: sourceKey, tid, page, 可选: clientId）
 * - GET /api/detail    - 获取视频详情（参数: sourceKey, vodId, 可选: clientId）
 * - GET /api/play      - 获取播放 URL（参数: sourceKey, playFlag, vodId, 可选: clientId）
 *
 * @author Emacs Frontend
 */
@Slf4j
@RequiredArgsConstructor(onConstructor = @__(@Inject))
@Singleton
public class EmacsFrontendHandler implements HttpHandler {

    private final Provider<Context> contextProvider;

    @Override
    public boolean support(HttpExchange httpExchange) {
        String path = httpExchange.getRequestURI().getPath();
        return BaseValues.HTTP_GET.equalsIgnoreCase(httpExchange.getRequestMethod()) &&
                path.startsWith("/api/");
    }

    @Override
    public void handle(HttpExchange httpExchange) {
        try (httpExchange) {
            String path = httpExchange.getRequestURI().getPath();

            if (path.equals("/api/clients")) {
                handleListClients(httpExchange);
            } else if (path.equals("/api/sources")) {
                handleGetSources(httpExchange);
            } else if (path.equals("/api/search")) {
                handleSearch(httpExchange);
            } else if (path.equals("/api/categories")) {
                handleGetCategories(httpExchange);
            } else if (path.equals("/api/category")) {
                handleGetCategoryContent(httpExchange);
            } else if (path.equals("/api/detail")) {
                handleGetDetail(httpExchange);
            } else if (path.equals("/api/play")) {
                handleGetPlayUrl(httpExchange);
            } else {
                sendErrorResponse(httpExchange, 404, "API 端点不存在");
            }
        } catch (Exception e) {
            log.error("EmacsFrontendHandler error", e);
            try {
                sendErrorResponse(httpExchange, 500, "服务器内部错误: " + e.getMessage());
            } catch (IOException ioException) {
                log.error("Failed to send error response", ioException);
            }
        }
    }

    /**
     * GET /api/clients - 列出所有已保存的 CATVOD_SPIDER 客户端配置
     */
    private void handleListClients(HttpExchange httpExchange) throws IOException {
        List<ClientInfo> clients = contextProvider.get().listFreeBoxClients();
        List<JSONObject> clientList = clients.stream()
                .map(c -> new JSONObject()
                        .set("id", c.getId())
                        .set("name", c.getClientName())
                        .set("configUrl", c.getConfigUrl())
                        .set("type", c.getClientType().name()))
                .toList();
        JSONObject response = new JSONObject()
                .set("code", 200)
                .set("data", JSONUtil.parseArray(JSONUtil.toJsonStr(clientList)));
        sendJsonResponse(httpExchange, response);
    }

    /**
     * GET /api/sources - 获取可用的视频源列表
     * 可选参数: clientId
     */
    private void handleGetSources(HttpExchange httpExchange) throws IOException {
        Map<String, String> params = parseQueryParams(httpExchange);
        String clientId = params.get("clientId");
        SpiderTemplate spiderTemplate = contextProvider.get().getFreeBoxSpiderTemplate(clientId);

        if (spiderTemplate == null) {
            sendErrorResponse(httpExchange, 503, "没有可用的客户端配置，请先在 FreeBox 中添加视频源");
            return;
        }
        try {
            CountDownLatch latch = new CountDownLatch(1);
            List<SourceBean> result = new ArrayList<>();

            spiderTemplate.getSourceBeanList(sources -> {
                result.addAll(sources);
                latch.countDown();
            });

            if (!latch.await(10, TimeUnit.SECONDS)) {
                sendErrorResponse(httpExchange, 504, "获取源列表超时");
                return;
            }

            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", JSONUtil.parseArray(JSONUtil.toJsonStr(result)));
            sendJsonResponse(httpExchange, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendErrorResponse(httpExchange, 500, "请求被中断");
        }
    }

    /**
     * GET /api/search - 搜索视频
     * 参数: sourceKey, keyword, 可选: clientId
     */
    private void handleSearch(HttpExchange httpExchange) throws IOException {
        Map<String, String> params = parseQueryParams(httpExchange);
        String sourceKey = params.get("sourceKey");
        String keyword = params.get("keyword");
        String clientId = params.get("clientId");

        if (sourceKey == null || sourceKey.isEmpty() || keyword == null || keyword.isEmpty()) {
            sendErrorResponse(httpExchange, 400, "缺少必要参数: sourceKey, keyword");
            return;
        }
        SpiderTemplate spiderTemplate = contextProvider.get().getFreeBoxSpiderTemplate(clientId);
        if (spiderTemplate == null) {
            sendErrorResponse(httpExchange, 503, "没有可用的客户端配置");
            return;
        }

        try {
            CountDownLatch latch = new CountDownLatch(1);
            List<Object> result = new ArrayList<>();

            GetSearchContentDTO dto = GetSearchContentDTO.of(sourceKey, keyword);
            spiderTemplate.getSearchContent(dto, xml -> {
                if (xml != null) {
                    result.add(xmlToJson(xml));
                }
                latch.countDown();
            });

            if (!latch.await(30, TimeUnit.SECONDS)) {
                sendErrorResponse(httpExchange, 504, "搜索超时");
                return;
            }

            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", result.isEmpty() ? new JSONObject() : result.get(0));
            sendJsonResponse(httpExchange, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendErrorResponse(httpExchange, 500, "请求被中断");
        }
    }

    /**
     * GET /api/categories - 获取首页分类（即分类列表）
     * 参数: sourceKey, 可选: clientId
     */
    private void handleGetCategories(HttpExchange httpExchange) throws IOException {
        Map<String, String> params = parseQueryParams(httpExchange);
        String sourceKey = params.get("sourceKey");
        String clientId = params.get("clientId");

        if (sourceKey == null || sourceKey.isEmpty()) {
            sendErrorResponse(httpExchange, 400, "缺少必要参数: sourceKey");
            return;
        }
        SpiderTemplate spiderTemplate = contextProvider.get().getFreeBoxSpiderTemplate(clientId);
        if (spiderTemplate == null) {
            sendErrorResponse(httpExchange, 503, "没有可用的客户端配置");
            return;
        }

        try {
            // 先获取完整的 SourceBean（含 api/ext/jar 字段），不能只设 key
            CountDownLatch sourceLatch = new CountDownLatch(1);
            List<SourceBean> allSources = new ArrayList<>();
            spiderTemplate.getSourceBeanList(sources -> {
                allSources.addAll(sources);
                sourceLatch.countDown();
            });
            if (!sourceLatch.await(10, TimeUnit.SECONDS)) {
                sendErrorResponse(httpExchange, 504, "获取源列表超时");
                return;
            }
            SourceBean source = allSources.stream()
                    .filter(s -> sourceKey.equals(s.getKey()))
                    .findFirst()
                    .orElse(null);
            if (source == null) {
                sendErrorResponse(httpExchange, 404, "未找到源: " + sourceKey);
                return;
            }

            CountDownLatch latch = new CountDownLatch(1);
            List<Object> result = new ArrayList<>();

            spiderTemplate.getHomeContent(source, sortXml -> {
                if (sortXml != null) {
                    result.add(xmlToJson(sortXml));
                }
                latch.countDown();
            });

            if (!latch.await(30, TimeUnit.SECONDS)) {
                sendErrorResponse(httpExchange, 504, "获取分类超时");
                return;
            }

            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", result.isEmpty() ? new JSONObject() : result.get(0));
            sendJsonResponse(httpExchange, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendErrorResponse(httpExchange, 500, "请求被中断");
        }
    }

    /**
     * GET /api/category - 获取分类内容（分页）
     * 参数: sourceKey, tid, page, 可选: clientId
     */
    private void handleGetCategoryContent(HttpExchange httpExchange) throws IOException {
        Map<String, String> params = parseQueryParams(httpExchange);
        String sourceKey = params.get("sourceKey");
        String tid = params.get("tid");
        String pageStr = params.getOrDefault("page", "1");
        String clientId = params.get("clientId");

        if (sourceKey == null || sourceKey.isEmpty() || tid == null || tid.isEmpty()) {
            sendErrorResponse(httpExchange, 400, "缺少必要参数: sourceKey, tid");
            return;
        }
        SpiderTemplate spiderTemplate = contextProvider.get().getFreeBoxSpiderTemplate(clientId);
        if (spiderTemplate == null) {
            sendErrorResponse(httpExchange, 503, "没有可用的客户端配置");
            return;
        }

        try {
            int page = Integer.parseInt(pageStr);

            CountDownLatch latch = new CountDownLatch(1);
            List<Object> result = new ArrayList<>();

            GetCategoryContentDTO dto = new GetCategoryContentDTO();
            dto.setSourceKey(sourceKey);
            dto.setTid(tid);
            dto.setPage(String.valueOf(page));
            dto.setFilter(false);
            dto.setExtend(new java.util.HashMap<>());

            spiderTemplate.getCategoryContent(dto, xml -> {
                if (xml != null) {
                    result.add(xmlToJson(xml));
                }
                latch.countDown();
            });

            if (!latch.await(30, TimeUnit.SECONDS)) {
                sendErrorResponse(httpExchange, 504, "获取分类内容超时");
                return;
            }

            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", result.isEmpty() ? new JSONObject() : result.get(0));
            sendJsonResponse(httpExchange, response);
        } catch (NumberFormatException e) {
            sendErrorResponse(httpExchange, 400, "page 参数必须是整数");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendErrorResponse(httpExchange, 500, "请求被中断");
        }
    }

    /**
     * GET /api/detail - 获取视频详情
     * 参数: sourceKey, vodId, 可选: clientId
     */
    private void handleGetDetail(HttpExchange httpExchange) throws IOException {
        Map<String, String> params = parseQueryParams(httpExchange);
        String sourceKey = params.get("sourceKey");
        String vodId = params.get("vodId");
        String clientId = params.get("clientId");

        if (sourceKey == null || sourceKey.isEmpty() || vodId == null || vodId.isEmpty()) {
            sendErrorResponse(httpExchange, 400, "缺少必要参数: sourceKey, vodId");
            return;
        }
        SpiderTemplate spiderTemplate = contextProvider.get().getFreeBoxSpiderTemplate(clientId);
        if (spiderTemplate == null) {
            sendErrorResponse(httpExchange, 503, "没有可用的客户端配置");
            return;
        }

        try {
            CountDownLatch latch = new CountDownLatch(1);
            List<Object> result = new ArrayList<>();

            GetDetailContentDTO dto = new GetDetailContentDTO();
            dto.setSourceKey(sourceKey);
            dto.setVodId(vodId);

            spiderTemplate.getDetailContent(dto, xml -> {
                if (xml != null) {
                    result.add(xmlToJson(xml));
                }
                latch.countDown();
            });

            if (!latch.await(30, TimeUnit.SECONDS)) {
                sendErrorResponse(httpExchange, 504, "获取详情超时");
                return;
            }

            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", result.isEmpty() ? new JSONObject() : result.get(0));
            sendJsonResponse(httpExchange, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendErrorResponse(httpExchange, 500, "请求被中断");
        }
    }

    /**
     * GET /api/play - 获取播放 URL
     * 参数: sourceKey, playFlag, vodId, 可选: clientId
     */
    private void handleGetPlayUrl(HttpExchange httpExchange) throws IOException {
        Map<String, String> params = parseQueryParams(httpExchange);
        String sourceKey = params.get("sourceKey");
        String playFlag = params.get("playFlag");
        String vodId = params.get("vodId");
        String clientId = params.get("clientId");

        if (sourceKey == null || sourceKey.isEmpty() ||
                playFlag == null || playFlag.isEmpty() ||
                vodId == null || vodId.isEmpty()) {
            sendErrorResponse(httpExchange, 400, "缺少必要参数: sourceKey, playFlag, vodId");
            return;
        }
        SpiderTemplate spiderTemplate = contextProvider.get().getFreeBoxSpiderTemplate(clientId);
        if (spiderTemplate == null) {
            sendErrorResponse(httpExchange, 503, "没有可用的客户端配置");
            return;
        }

        try {
            CountDownLatch latch = new CountDownLatch(1);
            List<Object> result = new ArrayList<>();

            GetPlayerContentDTO dto = new GetPlayerContentDTO();
            dto.setSourceKey(sourceKey);
            dto.setPlayFlag(playFlag);
            dto.setVodId(vodId);
            dto.setVipParseFlags(new ArrayList<>());

            spiderTemplate.getPlayerContent(dto, jsonObj -> {
                if (jsonObj != null) {
                    result.add(JSONUtil.parseObj(jsonObj.toString()));
                }
                latch.countDown();
            });

            if (!latch.await(30, TimeUnit.SECONDS)) {
                sendErrorResponse(httpExchange, 504, "获取播放 URL 超时");
                return;
            }

            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", result.isEmpty() ? new JSONObject() : result.get(0));
            sendJsonResponse(httpExchange, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendErrorResponse(httpExchange, 500, "请求被中断");
        }
    }

    // ──────────────────────── 辅助方法 ────────────────────────

    /**
     * 解析 HTTP 查询参数
     */
    private Map<String, String> parseQueryParams(HttpExchange httpExchange) {
        Map<String, String> params = new HashMap<>();
        String query = httpExchange.getRequestURI().getRawQuery();

        if (query != null && !query.isEmpty()) {
            String[] pairs = query.split("&");
            for (String pair : pairs) {
                int idx = pair.indexOf("=");
                if (idx > 0) {
                    String key = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8);
                    String value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
                    params.put(key, value);
                }
            }
        }

        return params;
    }

    /**
     * 将 AbsXml 对象转为 JSON
     */
    private Object xmlToJson(AbsXml xml) {
        return JSONUtil.parseObj(JSONUtil.toJsonStr(xml));
    }

    /**
     * 将 AbsSortXml 对象转为 JSON
     */
    private Object xmlToJson(AbsSortXml xml) {
        return JSONUtil.parseObj(JSONUtil.toJsonStr(xml));
    }

    /**
     * 发送 JSON 响应
     */
    private void sendJsonResponse(HttpExchange httpExchange, JSONObject jsonObject) throws IOException {
        byte[] responseData = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
        httpExchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        httpExchange.sendResponseHeaders(HttpStatus.HTTP_OK, responseData.length);
        httpExchange.getResponseBody().write(responseData);
    }

    /**
     * 发送错误响应
     */
    private void sendErrorResponse(HttpExchange httpExchange, int statusCode, String errorMsg) throws IOException {
        JSONObject errorResponse = new JSONObject()
                .set("code", statusCode)
                .set("message", errorMsg);
        byte[] responseData = errorResponse.toString().getBytes(StandardCharsets.UTF_8);
        httpExchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        httpExchange.sendResponseHeaders(statusCode, responseData.length);
        httpExchange.getResponseBody().write(responseData);
    }
}
