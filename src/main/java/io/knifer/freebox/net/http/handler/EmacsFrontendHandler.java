package io.knifer.freebox.net.http.handler;

import cn.hutool.http.HttpStatus;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.sun.net.httpserver.HttpExchange;
import io.knifer.freebox.constant.BaseValues;
import io.knifer.freebox.context.Context;
import io.knifer.freebox.model.domain.ClientInfo;
import io.knifer.freebox.model.common.tvbox.SourceBean;
import io.knifer.freebox.model.common.tvbox.VodInfo;
import io.knifer.freebox.model.common.tvbox.AbsXml;
import io.knifer.freebox.model.common.tvbox.AbsSortXml;
import io.knifer.freebox.model.s2c.*;
import io.knifer.freebox.spider.SpiderJarLoader;
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
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    private final SpiderJarLoader spiderJarLoader;

    // ── QR 码登录 session 管理 ──

    private static class QrSession {
        final String token;
        volatile String status; // "pending" | "success" | "failed"
        volatile String cookie;
        final long createdAt;

        QrSession(String token) {
            this.token = token;
            this.status = "pending";
            this.createdAt = System.currentTimeMillis();
        }
    }

    private static final ConcurrentHashMap<String, QrSession> QR_SESSIONS = new ConcurrentHashMap<>();
    private static final long QR_SESSION_TTL = 5 * 60 * 1000L; // 5 分钟超时

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
            } else if (path.equals("/api/resolve-share")) {
                handleResolveShare(httpExchange);
            } else if (path.equals("/api/qr-login")) {
                handleQrLogin(httpExchange);
            } else if (path.equals("/api/qr-status")) {
                handleQrStatus(httpExchange);
            } else if (path.equals("/api/qr-image")) {
                handleQrImage(httpExchange);
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

    /**
     * GET /api/resolve-share - 延迟解析网盘分享链接的选集
     * 参数: sourceKey, flag, shareLink, 可选: clientId
     */
    private void handleResolveShare(HttpExchange httpExchange) throws IOException {
        Map<String, String> params = parseQueryParams(httpExchange);
        String sourceKey = params.get("sourceKey");
        String flag = params.get("flag");
        String shareLink = params.get("shareLink");
        String clientId = params.get("clientId");

        if (sourceKey == null || sourceKey.isEmpty() ||
                flag == null || flag.isEmpty() ||
                shareLink == null || shareLink.isEmpty()) {
            sendErrorResponse(httpExchange, 400, "缺少必要参数: sourceKey, flag, shareLink");
            return;
        }
        SpiderTemplate spiderTemplate = contextProvider.get().getFreeBoxSpiderTemplate(clientId);
        if (spiderTemplate == null) {
            sendErrorResponse(httpExchange, 503, "没有可用的客户端配置");
            return;
        }

        try {
            CountDownLatch latch = new CountDownLatch(1);
            List<String> result = new ArrayList<>();

            spiderTemplate.resolveShare(sourceKey, flag, shareLink, urls -> {
                if (urls != null) {
                    result.add(urls);
                }
                latch.countDown();
            });

            if (!latch.await(30, TimeUnit.SECONDS)) {
                sendErrorResponse(httpExchange, 504, "解析网盘分享超时");
                return;
            }

            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject().set("urls", result.isEmpty() ? "" : result.get(0)));
            sendJsonResponse(httpExchange, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendErrorResponse(httpExchange, 500, "请求被中断");
        }
    }

    /**
     * GET /api/qr-login - 获取二维码登录 URL
     * 参数: type, 可选: clientId
     */
    private void handleQrLogin(HttpExchange httpExchange) throws IOException {
        Map<String, String> params = parseQueryParams(httpExchange);
        String type = params.get("type");

        if (type == null || type.isEmpty()) {
            sendErrorResponse(httpExchange, 400, "缺少必要参数: type");
            return;
        }

        try {
            if ("quark".equals(type)) {
                handleQuarkQrLogin(httpExchange);
            } else if ("uc".equals(type)) {
                handleUcQrLogin(httpExchange);
            } else if ("bd".equals(type)) {
                handleBdQrLogin(httpExchange);
            } else {
                sendErrorResponse(httpExchange, 400, "不支持的网盘类型: " + type);
            }
        } catch (Exception e) {
            log.error("qr-login error", e);
            sendErrorResponse(httpExchange, 500, "获取二维码失败: " + e.getMessage());
        }
    }

    /**
     * GET /api/qr-status - 轮询二维码登录状态
     * 参数: type, token, 可选: clientId
     */
    private void handleQrStatus(HttpExchange httpExchange) throws IOException {
        Map<String, String> params = parseQueryParams(httpExchange);
        String type = params.get("type");
        String token = params.get("token");

        if (type == null || type.isEmpty() || token == null || token.isEmpty()) {
            sendErrorResponse(httpExchange, 400, "缺少必要参数: type, token");
            return;
        }

        try {
            if ("quark".equals(type)) {
                handleQuarkQrStatus(httpExchange, token);
            } else if ("uc".equals(type)) {
                handleUcQrStatus(httpExchange, token);
            } else if ("bd".equals(type)) {
                handleBdQrStatus(httpExchange, token);
            } else {
                sendErrorResponse(httpExchange, 400, "不支持的网盘类型: " + type);
            }
        } catch (Exception e) {
            log.error("qr-status error", e);
            sendErrorResponse(httpExchange, 500, "查询状态失败: " + e.getMessage());
        }
    }

    /**
     * 夸克网盘二维码登录 - 获取二维码 token 和 URL
     */
    private void handleQuarkQrLogin(HttpExchange httpExchange) throws IOException {
        // 调用夸克 API 获取二维码 token
        String requestId = UUID.randomUUID().toString();
        String url = "https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin"
                + "?client_id=386&v=1.2&request_id=" + requestId;
        String respBody = HttpUtil.get(url);

        JSONObject json = JSONUtil.parseObj(respBody);
        if (!"ok".equals(json.getStr("message"))) {
            sendErrorResponse(httpExchange, 502, "获取夸克二维码 token 失败");
            return;
        }
        JSONObject data = json.getJSONObject("data");
        JSONObject members = data.getJSONObject("members");
        String qrToken = members.getStr("token");
        if (qrToken == null || qrToken.isEmpty()) {
            sendErrorResponse(httpExchange, 502, "夸克二维码 token 为空");
            return;
        }

        // 保存 session
        QrSession session = new QrSession(qrToken);
        QR_SESSIONS.put(qrToken, session);

        // 构建二维码 URL
        String qrUrl = "https://su.quark.cn/4_eMHBJ?uc_param_str=&token=" + qrToken
                + "&client_id=532&uc_biz_str=S%3Acustom%7COPT%3ASAREA%400%7COPT%3AIMMERSIVE%401%7COPT%3ABACK_BTN_STYLE%400";

        JSONObject response = new JSONObject()
                .set("code", 200)
                .set("data", new JSONObject()
                        .set("url", qrUrl)
                        .set("token", qrToken)
                        .set("image", false));
        sendJsonResponse(httpExchange, response);
    }

    /**
     * 夸克网盘二维码登录 - 轮询扫码状态
     */
    private void handleQuarkQrStatus(HttpExchange httpExchange, String token) throws IOException {
        QrSession session = QR_SESSIONS.get(token);
        if (session == null) {
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "expired")
                            .set("message", "二维码已过期，请重新获取"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        // 如果已经成功，直接返回
        if ("success".equals(session.status)) {
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "success")
                            .set("message", "登录成功"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        // 检查是否超时
        if (System.currentTimeMillis() - session.createdAt > QR_SESSION_TTL) {
            QR_SESSIONS.remove(token);
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "expired")
                            .set("message", "二维码已超时，请重新获取"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        // 调用夸克 API 轮询扫码状态
        String requestId = UUID.randomUUID().toString();
        String pollUrl = "https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken"
                + "?client_id=532&v=1.2&request_id=" + requestId + "&token=" + token;
        String respBody = HttpUtil.get(pollUrl);

        JSONObject json = JSONUtil.parseObj(respBody);
        if (json == null) {
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "pending")
                            .set("message", "等待扫码"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        // 检查状态码：2000000 表示已扫码
        Object statusObj = json.getObj("status");
        boolean scanned = (statusObj instanceof Number && ((Number) statusObj).intValue() == 2000000);

        if (!scanned) {
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "pending")
                            .set("message", "等待扫码"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        // 已扫码，获取 serviceTicket
        try {
            JSONObject pollData = json.getJSONObject("data");
            JSONObject pollMembers = pollData.getJSONObject("members");
            String serviceTicket = pollMembers.getStr("service_ticket");
            if (serviceTicket == null || serviceTicket.isEmpty()) {
                session.status = "failed";
                JSONObject response = new JSONObject()
                        .set("code", 200)
                        .set("data", new JSONObject()
                                .set("status", "failed")
                                .set("message", "获取 serviceTicket 失败"));
                sendJsonResponse(httpExchange, response);
                return;
            }

            // 用 serviceTicket 获取用户 cookie
            String infoUrl = "https://pan.quark.cn/account/info?st=" + serviceTicket + "&lw=scan";
            cn.hutool.http.HttpRequest infoReq = cn.hutool.http.HttpRequest.get(infoUrl);
            cn.hutool.http.HttpResponse infoResp = infoReq.execute();
            String infoBody = infoResp.body();

            JSONObject infoJson = JSONUtil.parseObj(infoBody);
            if (infoJson == null || !Boolean.TRUE.equals(infoJson.getBool("success"))) {
                session.status = "failed";
                JSONObject response = new JSONObject()
                        .set("code", 200)
                        .set("data", new JSONObject()
                                .set("status", "failed")
                                .set("message", "获取用户信息失败"));
                sendJsonResponse(httpExchange, response);
                return;
            }

            // 提取 set-Cookie 头
            List<String> cookies = infoResp.headers().get("Set-Cookie");
            if (cookies == null || cookies.isEmpty()) {
                // 尝试不区分大小写
                for (Map.Entry<String, List<String>> entry : infoResp.headers().entrySet()) {
                    if (entry.getKey().equalsIgnoreCase("set-cookie")) {
                        cookies = entry.getValue();
                        break;
                    }
                }
            }

            if (cookies == null || cookies.isEmpty()) {
                session.status = "failed";
                JSONObject response = new JSONObject()
                        .set("code", 200)
                        .set("data", new JSONObject()
                                .set("status", "failed")
                                .set("message", "获取 cookie 失败"));
                sendJsonResponse(httpExchange, response);
                return;
            }

            // 拼接 cookie 字符串
            StringBuilder cookieBuilder = new StringBuilder();
            for (String c : cookies) {
                if (cookieBuilder.length() > 0) cookieBuilder.append(";");
                cookieBuilder.append(c.split(";")[0]);
            }
            String cookieStr = cookieBuilder.toString();
            session.cookie = cookieStr;
            session.status = "success";

            // 保存到夸克缓存文件
            saveQuarkCache(cookieStr);

            log.info("Quark QR login success: cookie length={}", cookieStr.length());

            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "success")
                            .set("message", "登录成功"));
            sendJsonResponse(httpExchange, response);

        } catch (Exception e) {
            log.error("quark qr status poll error", e);
            session.status = "failed";
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "failed")
                            .set("message", "处理扫码结果失败: " + e.getMessage()));
            sendJsonResponse(httpExchange, response);
        }
    }

    /**
     * 保存夸克 cookie 到缓存文件（与 CatVodSpider 的 Path.tv("quark") 路径一致）
     * 实际路径: ~/TV/.quark
     */
    private void saveQuarkCache(String cookie) {
        try {
            String home = System.getProperty("user.home");
            java.nio.file.Path tvDir = Paths.get(home, "TV");
            if (!Files.exists(tvDir)) {
                Files.createDirectories(tvDir);
            }
            java.nio.file.Path cacheFile = tvDir.resolve(".quark");
            // 构建与 QuarkApi Cache/User 兼容的 JSON 格式: {"user":{"cookie":"..."}}
            JSONObject userJson = new JSONObject();
            userJson.set("cookie", cookie);
            JSONObject cacheJson = new JSONObject();
            cacheJson.set("user", userJson);
            Files.writeString(cacheFile, cacheJson.toString(), StandardCharsets.UTF_8);
            log.info("quark cache saved to {}", cacheFile);
        } catch (Exception e) {
            log.error("save quark cache error", e);
        }
    }

    // ── UC 网盘二维码登录 ──

    private static final String UC_CLIENT_ID = "5acf882d27b74502b7040b0c65519aa7";
    private static final String UC_SIGN_KEY = "l3srvtd7p42l0d0x1u8d7yc8ye9kki4d";
    private static final String UC_API_URL = "https://open-api-drive.uc.cn";
    private static final String UC_CODE_API_URL = "http://api.extscreen.com/ucdrive";
    private static final String UC_DEVICE_ID = "07b48aaba8a739356ab8107b5e230ad4";

    /**
     * UC 网盘二维码登录 - 获取二维码
     */
    private void handleUcQrLogin(HttpExchange httpExchange) throws IOException {
        String pathname = "/oauth/authorize";
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000 + 1) + "000";
        String deviceID = UC_DEVICE_ID;
        String reqId = DigestUtil.md5Hex(deviceID + timestamp).substring(0, 16);
        String xPanToken = DigestUtil.sha256Hex("GET&" + pathname + "&" + timestamp + "&" + UC_SIGN_KEY);

        Map<String, String> params = new HashMap<>();
        params.put("req_id", reqId);
        params.put("access_token", "");
        params.put("app_ver", "1.6.8");
        params.put("device_id", deviceID);
        params.put("device_brand", "Xiaomi");
        params.put("platform", "tv");
        params.put("device_name", "M2004J7AC");
        params.put("device_model", "M2004J7AC");
        params.put("build_device", "M2004J7AC");
        params.put("build_product", "M2004J7AC");
        params.put("device_gpu", "Adreno (TM) 550");
        params.put("activity_rect", java.net.URLEncoder.encode("{}", "UTF-8"));
        params.put("channel", "UCTVOFFICIALWEB");
        params.put("auth_type", "code");
        params.put("client_id", UC_CLIENT_ID);
        params.put("scope", "netdisk");
        params.put("qrcode", "1");
        params.put("qr_width", "460");
        params.put("qr_height", "460");

        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("User-Agent", "Mozilla/5.0 (Linux; U; Android 13; zh-cn; M2004J7AC Build/UKQ1.231108.001) AppleWebKit/533.1 (KHTML, like Gecko) Mobile Safari/533.1");
        headers.put("x-pan-tm", timestamp);
        headers.put("x-pan-token", xPanToken);
        headers.put("x-pan-client-id", UC_CLIENT_ID);
        headers.put("Host", "open-api-drive.uc.cn");

        String queryString = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
        String fullUrl = UC_API_URL + pathname + "?" + queryString;

        String respBody;
        try {
            cn.hutool.http.HttpRequest req = cn.hutool.http.HttpRequest.get(fullUrl);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                req.header(h.getKey(), h.getValue());
            }
            respBody = req.timeout(15000).execute().body();
        } catch (Exception e) {
            log.error("uc qr login http error", e);
            sendErrorResponse(httpExchange, 502, "获取UC二维码失败: " + e.getMessage());
            return;
        }

        JSONObject json;
        try {
            json = JSONUtil.parseObj(respBody);
        } catch (Exception e) {
            log.error("uc qr login parse error: {}", respBody, e);
            sendErrorResponse(httpExchange, 502, "解析UC二维码响应失败");
            return;
        }

        String queryToken = json.getStr("query_token");
        String qrData = json.getStr("qr_data");
        if (queryToken == null || qrData == null) {
            sendErrorResponse(httpExchange, 502, "UC二维码数据为空");
            return;
        }

        // 保存 session
        JSONObject state = new JSONObject();
        state.set("query_token", queryToken);
        state.set("request_id", reqId);
        String sessionKey = "uc_" + queryToken;
        QR_SESSIONS.put(sessionKey, new QrSession(queryToken));

        // 将 base64 二维码保存到临时文件
        String imageUrl = saveUcQrImage(sessionKey, qrData);

        JSONObject response = new JSONObject()
                .set("code", 200)
                .set("data", new JSONObject()
                        .set("url", imageUrl)
                        .set("token", queryToken)
                        .set("image", true));
        sendJsonResponse(httpExchange, response);
    }

    /**
     * 保存 UC 二维码图片，返回可访问的 URL
     */
    private String saveUcQrImage(String sessionKey, String base64Data) {
        try {
            // 移除可能的 data URL 前缀
            String raw = base64Data;
            if (raw.contains(",")) {
                raw = raw.substring(raw.indexOf(",") + 1);
            }
            byte[] imageBytes = Base64.getDecoder().decode(raw);
            return saveQrImage(sessionKey, imageBytes);
        } catch (Exception e) {
            log.error("save UC QR image error", e);
            return base64Data; // fallback: return raw base64
        }
    }

    /**
     * 通用：保存二维码图片到 ~/.freebox/qrcode/{sessionKey}.png，返回可访问的 URL
     */
    private String saveQrImage(String sessionKey, byte[] imageBytes) {
        try {
            String home = System.getProperty("user.home");
            java.nio.file.Path qrDir = Paths.get(home, ".freebox", "qrcode");
            if (!Files.exists(qrDir)) {
                Files.createDirectories(qrDir);
            }
            java.nio.file.Path imagePath = qrDir.resolve(sessionKey + ".png");
            Files.write(imagePath, imageBytes);
            return "http://127.0.0.1:9978/api/qr-image?key=" + sessionKey;
        } catch (Exception e) {
            log.error("save QR image error", e);
            return null;
        }
    }

    /**
     * GET /api/qr-image - 提供二维码图片
     */
    private void handleQrImage(HttpExchange httpExchange) throws IOException {
        Map<String, String> params = parseQueryParams(httpExchange);
        String key = params.get("key");
        if (key == null || key.isEmpty()) {
            sendErrorResponse(httpExchange, 400, "缺少参数: key");
            return;
        }

        String home = System.getProperty("user.home");
        java.nio.file.Path imagePath = Paths.get(home, ".freebox", "qrcode", key + ".png");
        if (!Files.exists(imagePath)) {
            sendErrorResponse(httpExchange, 404, "二维码图片不存在或已过期");
            return;
        }

        byte[] imageBytes = Files.readAllBytes(imagePath);
        httpExchange.getResponseHeaders().set("Content-Type", "image/png");
        httpExchange.sendResponseHeaders(200, imageBytes.length);
        httpExchange.getResponseBody().write(imageBytes);
    }

    /**
     * UC 网盘二维码登录 - 轮询扫码状态
     */
    private void handleUcQrStatus(HttpExchange httpExchange, String token) throws IOException {
        QrSession session = QR_SESSIONS.get("uc_" + token);
        if (session == null) {
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "expired")
                            .set("message", "二维码已过期，请重新获取"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        if ("success".equals(session.status)) {
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "success")
                            .set("message", "登录成功"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        // 检查超时
        if (System.currentTimeMillis() - session.createdAt > 5 * 60 * 1000L) {
            QR_SESSIONS.remove("uc_" + token);
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "expired")
                            .set("message", "二维码已超时，请重新获取"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        // 获取 session state
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000 + 1) + "000";
        String deviceID = UC_DEVICE_ID;
        String reqId = DigestUtil.md5Hex(deviceID + timestamp).substring(0, 16);
        String xPanToken = DigestUtil.sha256Hex("GET&/oauth/code&" + timestamp + "&" + UC_SIGN_KEY);

        Map<String, String> params = new HashMap<>();
        params.put("req_id", reqId);
        params.put("access_token", "");
        params.put("app_ver", "1.6.8");
        params.put("device_id", deviceID);
        params.put("device_brand", "Xiaomi");
        params.put("platform", "tv");
        params.put("device_name", "M2004J7AC");
        params.put("device_model", "M2004J7AC");
        params.put("build_device", "M2004J7AC");
        params.put("build_product", "M2004J7AC");
        params.put("device_gpu", "Adreno (TM) 550");
        params.put("activity_rect", java.net.URLEncoder.encode("{}", "UTF-8"));
        params.put("channel", "UCTVOFFICIALWEB");
        params.put("client_id", UC_CLIENT_ID);
        params.put("scope", "netdisk");
        params.put("query_token", token);

        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("User-Agent", "Mozilla/5.0 (Linux; U; Android 13; zh-cn; M2004J7AC Build/UKQ1.231108.001) AppleWebKit/533.1 (KHTML, like Gecko) Mobile Safari/533.1");
        headers.put("x-pan-tm", timestamp);
        headers.put("x-pan-token", xPanToken);
        headers.put("x-pan-client-id", UC_CLIENT_ID);

        String queryString = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "&" + b)
                .orElse("");
        String fullUrl = UC_API_URL + "/oauth/code?" + queryString;

        try {
            cn.hutool.http.HttpRequest req = cn.hutool.http.HttpRequest.get(fullUrl);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                req.header(h.getKey(), h.getValue());
            }
            cn.hutool.http.HttpResponse resp = req.timeout(15000).execute();
            int httpStatus = resp.getStatus();

            // HTTP 400 = not scanned yet
            if (httpStatus == 400) {
                JSONObject response = new JSONObject()
                        .set("code", 200)
                        .set("data", new JSONObject()
                                .set("status", "pending")
                                .set("message", "等待扫码"));
                sendJsonResponse(httpExchange, response);
                return;
            }

            // HTTP 404 or other = expired
            if (httpStatus != 200) {
                JSONObject response = new JSONObject()
                        .set("code", 200)
                        .set("data", new JSONObject()
                                .set("status", "expired")
                                .set("message", "二维码已失效 (HTTP " + httpStatus + ")"));
                sendJsonResponse(httpExchange, response);
                return;
            }

            // HTTP 200 = scanned, parse body for auth code
            String body = resp.body();
            JSONObject json = JSONUtil.parseObj(body);
            String authCode = json.getStr("code");

            if (authCode == null || authCode.isEmpty()) {
                JSONObject response = new JSONObject()
                        .set("code", 200)
                        .set("data", new JSONObject()
                                .set("status", "pending")
                                .set("message", "等待扫码"));
                sendJsonResponse(httpExchange, response);
                return;
            }

            // 交换 token
            String accessToken = exchangeUcCode(authCode);
            if (accessToken == null || accessToken.isEmpty()) {
                session.status = "failed";
                JSONObject response = new JSONObject()
                        .set("code", 200)
                        .set("data", new JSONObject()
                                .set("status", "failed")
                                .set("message", "获取UC访问令牌失败"));
                sendJsonResponse(httpExchange, response);
                return;
            }

            // 保存到 ~/TV/.uctoken
            saveUcToken(accessToken);
            session.status = "success";
            session.cookie = accessToken;

            log.info("UC QR login success");

            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "success")
                            .set("message", "登录成功"));
            sendJsonResponse(httpExchange, response);

        } catch (Exception e) {
            log.error("uc qr status poll error", e);
            sendErrorResponse(httpExchange, 500, "查询UC扫码状态失败: " + e.getMessage());
        }
    }

    /**
     * 交换 UC 授权码为访问令牌
     */
    private String exchangeUcCode(String code) {
        String timestamp = String.valueOf(System.currentTimeMillis() / 1000 + 1) + "000";
        String deviceID = UC_DEVICE_ID;
        String reqId = DigestUtil.md5Hex(deviceID + timestamp).substring(0, 16);

        JSONObject postData = new JSONObject();
        postData.set("req_id", reqId);
        postData.set("app_ver", "1.6.8");
        postData.set("device_id", deviceID);
        postData.set("device_brand", "Xiaomi");
        postData.set("platform", "tv");
        postData.set("device_name", "M2004J7AC");
        postData.set("device_model", "M2004J7AC");
        postData.set("build_device", "M2004J7AC");
        postData.set("build_product", "M2004J7AC");
        postData.set("device_gpu", "Adreno (TM) 550");
        try {
            postData.set("activity_rect", java.net.URLEncoder.encode("{}", "UTF-8"));
        } catch (Exception ignored) {}
        postData.set("channel", "UCTVOFFICIALWEB");
        postData.set("code", code);

        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("User-Agent", "Mozilla/5.0 (Linux; U; Android 13; zh-cn; M2004J7AC Build/UKQ1.231108.001) AppleWebKit/533.1 (KHTML, like Gecko) Mobile Safari/533.1");

        try {
            cn.hutool.http.HttpRequest postReq = cn.hutool.http.HttpRequest.post(UC_CODE_API_URL + "/token");
            for (Map.Entry<String, String> h : headers.entrySet()) {
                postReq.header(h.getKey(), h.getValue());
            }
            String respBody = postReq.body(postData.toString()).timeout(15000).execute().body();
            JSONObject json = JSONUtil.parseObj(respBody);
            if (json.getInt("code", 0) == 200) {
                JSONObject tokenData = json.getJSONObject("data");
                if (tokenData != null) {
                    return tokenData.getStr("access_token");
                }
            }
            log.warn("exchange UC code failed: {}", respBody);
        } catch (Exception e) {
            log.error("exchange UC code error", e);
        }
        return null;
    }

    /**
     * 保存 UC token 到缓存文件 ~/TV/.uctoken
     */
    private void saveUcToken(String accessToken) {
        try {
            String home = System.getProperty("user.home");
            java.nio.file.Path tvDir = Paths.get(home, "TV");
            if (!Files.exists(tvDir)) {
                Files.createDirectories(tvDir);
            }
            java.nio.file.Path tokenFile = tvDir.resolve(".uctoken");
            JSONObject userJson = new JSONObject();
            userJson.set("access_token", accessToken);
            JSONObject cacheJson = new JSONObject();
            cacheJson.set("user", userJson);
            Files.writeString(tokenFile, cacheJson.toString(), StandardCharsets.UTF_8);
            log.info("UC token saved to {}", tokenFile);
        } catch (Exception e) {
            log.error("save UC token error", e);
        }
    }

    // ── 百度网盘二维码登录 ──

    private static final String BD_HEADERS_UA =
            "Mozilla/5.0 (Linux; Android 12; SM-X800) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/101.0.4951.40 Safari/537.36";

    /**
     * 百度网盘二维码登录 - 获取二维码
     * 参考 BaiDuYunHandler.kt:98-127 loginByQRCode()
     */
    private void handleBdQrLogin(HttpExchange httpExchange) throws IOException {
        // 1. 调百度 getqrcode API
        long timestamp = System.currentTimeMillis();
        String url = "https://passport.baidu.com/v2/api/getqrcode?lp=pc&_=" + timestamp;

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", BD_HEADERS_UA);
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Origin", "https://pan.baidu.com");
        headers.put("Referer", "https://pan.baidu.com/");

        String respBody;
        try {
            cn.hutool.http.HttpRequest req = cn.hutool.http.HttpRequest.get(url);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                req.header(h.getKey(), h.getValue());
            }
            respBody = req.timeout(15000).execute().body();
        } catch (Exception e) {
            log.error("bd qr login http error", e);
            sendErrorResponse(httpExchange, 502, "获取百度二维码失败: " + e.getMessage());
            return;
        }

        JSONObject json;
        try {
            json = JSONUtil.parseObj(respBody);
        } catch (Exception e) {
            log.error("bd qr login parse error: {}", respBody, e);
            sendErrorResponse(httpExchange, 502, "解析百度二维码响应失败");
            return;
        }

        if (json.getInt("errno", -1) != 0) {
            sendErrorResponse(httpExchange, 502, "获取百度二维码失败, errno: " + json.getInt("errno", -1));
            return;
        }

        String sign = json.getStr("sign");
        String imgurl = json.getStr("imgurl");  // 形如 "passport.baidu.com/xxx.png"
        if (sign == null || sign.isEmpty() || imgurl == null || imgurl.isEmpty()) {
            sendErrorResponse(httpExchange, 502, "百度二维码数据为空");
            return;
        }
        String qrCodeImageUrl = "https://" + imgurl;

        // 2. 下载二维码图片字节，存到 ~/.freebox/qrcode/bd_{sign}.png
        byte[] imgBytes;
        try {
            cn.hutool.http.HttpRequest imgReq = cn.hutool.http.HttpRequest.get(qrCodeImageUrl);
            imgReq.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/99.0.4844.51 Safari/537.36");
            imgBytes = imgReq.timeout(15000).execute().bodyBytes();
        } catch (Exception e) {
            log.error("bd qr image download error", e);
            sendErrorResponse(httpExchange, 502, "下载百度二维码图片失败: " + e.getMessage());
            return;
        }

        String sessionKey = "bd_" + sign;
        String imageUrl = saveQrImage(sessionKey, imgBytes);
        if (imageUrl == null) {
            sendErrorResponse(httpExchange, 500, "保存百度二维码图片失败");
            return;
        }

        // 3. 存 session
        QR_SESSIONS.put(sessionKey, new QrSession(sign));

        log.info("BD QR login started: sign={}", sign);

        // 4. 返回（image=true 表示前端应作为图片显示）
        JSONObject response = new JSONObject()
                .set("code", 200)
                .set("data", new JSONObject()
                        .set("url", imageUrl)
                        .set("token", sign)
                        .set("image", true));
        sendJsonResponse(httpExchange, response);
    }

    /**
     * 百度网盘二维码登录 - 轮询扫码状态
     * 参考 BaiDuYunHandler.kt:129-184 checkQRLoginStatus()
     *
     * 注意：百度 unicast 是长轮询接口（约 30 秒返回），HTTP 超时设为 40 秒。
     * 前端 BD 轮询间隔应为 30 秒，避免并发长请求。
     */
    private void handleBdQrStatus(HttpExchange httpExchange, String sign) throws IOException {
        QrSession session = QR_SESSIONS.get("bd_" + sign);
        if (session == null) {
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "expired")
                            .set("message", "二维码已过期，请重新获取"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        if ("success".equals(session.status)) {
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "success")
                            .set("message", "登录成功"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        // 检查超时
        if (System.currentTimeMillis() - session.createdAt > QR_SESSION_TTL) {
            QR_SESSIONS.remove("bd_" + sign);
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "expired")
                            .set("message", "二维码已超时，请重新获取"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", BD_HEADERS_UA);
        headers.put("Accept", "application/json, text/plain, */*");
        headers.put("Origin", "https://pan.baidu.com");
        headers.put("Referer", "https://pan.baidu.com/");

        // 1. 长轮询 unicast（超时 40 秒）
        long ts = System.currentTimeMillis();
        String checkUrl = "https://passport.baidu.com/channel/unicast?channel_id=" + sign
                + "&tpl=netdisk&callback=&apiver=v3&tt=" + ts + "_=" + ts;

        String resp;
        try {
            cn.hutool.http.HttpRequest req = cn.hutool.http.HttpRequest.get(checkUrl);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                req.header(h.getKey(), h.getValue());
            }
            resp = req.timeout(40000).execute().body();
        } catch (Exception e) {
            log.error("bd qr status poll error", e);
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "pending")
                            .set("message", "等待扫码"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        // 去 JSONP 包裹（带 () 包裹）
        String clean = resp.trim();
        if (clean.startsWith("(")) clean = clean.substring(1);
        if (clean.endsWith(")")) clean = clean.substring(0, clean.length() - 1);
        clean = clean.trim();

        JSONObject json;
        try {
            json = JSONUtil.parseObj(clean);
        } catch (Exception e) {
            log.warn("bd unicast parse error: {}", resp);
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "pending")
                            .set("message", "等待扫码"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        // errno != 0 表示未扫码
        if (json.getInt("errno", -1) != 0) {
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "pending")
                            .set("message", "等待扫码"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        String channelVStr = json.getStr("channel_v");
        if (channelVStr == null || channelVStr.isEmpty()) {
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "pending")
                            .set("message", "等待扫码"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        JSONObject channelV;
        try {
            channelV = JSONUtil.parseObj(channelVStr);
        } catch (Exception e) {
            log.warn("bd channel_v parse error: {}", channelVStr);
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "pending")
                            .set("message", "等待扫码"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        if (channelV.getInt("status", -1) != 0) {
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "pending")
                            .set("message", "等待扫码"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        String bduss = channelV.getStr("v");
        if (bduss == null || bduss.isEmpty()) {
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "pending")
                            .set("message", "等待扫码"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        // 2. 用 bduss 换 cookie（qrbdusslogin）
        long loginTs = System.currentTimeMillis();
        String loginUrl = "https://passport.baidu.com/v3/login/main/qrbdusslogin?"
                + "v=" + loginTs + "&bduss=" + bduss
                + "&u=&loginVersion=v4&qrcode=1&tpl=netdisk&apiver=v3"
                + "&tt=" + loginTs + "&traceid=&callback=bd__cbs__cupstt";

        cn.hutool.http.HttpResponse loginResp;
        String loginBody;
        try {
            cn.hutool.http.HttpRequest loginReq = cn.hutool.http.HttpRequest.get(loginUrl);
            for (Map.Entry<String, String> h : headers.entrySet()) {
                loginReq.header(h.getKey(), h.getValue());
            }
            loginResp = loginReq.timeout(15000).execute();
            loginBody = loginResp.body();
        } catch (Exception e) {
            log.error("bd qrbdusslogin error", e);
            session.status = "failed";
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "failed")
                            .set("message", "百度登录请求失败: " + e.getMessage()));
            sendJsonResponse(httpExchange, response);
            return;
        }

        // 清理 JSONP 包裹 + HTML 反转义
        String cleanLogin = loginBody;
        int parenStart = cleanLogin.indexOf("(");
        int parenEnd = cleanLogin.lastIndexOf(")");
        if (parenStart >= 0 && parenEnd > parenStart) {
            cleanLogin = cleanLogin.substring(parenStart + 1, parenEnd);
        }
        cleanLogin = cn.hutool.http.HtmlUtil.unescape(cleanLogin);

        JSONObject loginJson;
        try {
            loginJson = JSONUtil.parseObj(cleanLogin);
        } catch (Exception e) {
            log.warn("bd login parse error: {}", cleanLogin);
            session.status = "failed";
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "failed")
                            .set("message", "解析百度登录响应失败"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        // 检查 errInfo.no == "0"
        JSONObject errInfo = loginJson.getJSONObject("errInfo");
        if (errInfo == null || !"0".equals(errInfo.getStr("no"))) {
            session.status = "failed";
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "failed")
                            .set("message", "百度登录失败: " + cleanLogin));
            sendJsonResponse(httpExchange, response);
            return;
        }

        // 3. 拼 cookie（generateCooike 逻辑：取每个 set-cookie 第一段用 ; 连接）
        List<String> setCookies = loginResp.headers().get("Set-Cookie");
        if (setCookies == null || setCookies.isEmpty()) {
            // 不区分大小写查找
            for (Map.Entry<String, List<String>> entry : loginResp.headers().entrySet()) {
                if (entry.getKey().equalsIgnoreCase("set-cookie")) {
                    setCookies = entry.getValue();
                    break;
                }
            }
        }

        if (setCookies == null || setCookies.isEmpty()) {
            session.status = "failed";
            JSONObject response = new JSONObject()
                    .set("code", 200)
                    .set("data", new JSONObject()
                            .set("status", "failed")
                            .set("message", "获取百度 cookie 失败"));
            sendJsonResponse(httpExchange, response);
            return;
        }

        StringBuilder cookieBuilder = new StringBuilder();
        for (String c : setCookies) {
            if (cookieBuilder.length() > 0) cookieBuilder.append(";");
            cookieBuilder.append(c.split(";")[0]);
        }
        String cookie = cookieBuilder.toString();
        session.cookie = cookie;
        session.status = "success";

        // 4. 写 ~/TV/.bd（格式 {"user":{"cookie":"..."}}）
        saveBdCache(cookie);

        // 5. 反射热刷新 spider 内存
        refreshBaiDuSpiderCookie(cookie);

        log.info("BD QR login success: cookie length={}", cookie.length());

        JSONObject response = new JSONObject()
                .set("code", 200)
                .set("data", new JSONObject()
                        .set("status", "success")
                        .set("message", "登录成功"));
        sendJsonResponse(httpExchange, response);
    }

    /**
     * 保存百度 cookie 到缓存文件 ~/TV/.bd
     * 格式与 CatVodSpider 的 Cache.java 一致: {"user":{"cookie":"..."}}
     */
    private void saveBdCache(String cookie) {
        try {
            String home = System.getProperty("user.home");
            java.nio.file.Path tvDir = Paths.get(home, "TV");
            if (!Files.exists(tvDir)) {
                Files.createDirectories(tvDir);
            }
            java.nio.file.Path cacheFile = tvDir.resolve(".bd");
            JSONObject userJson = new JSONObject();
            userJson.set("cookie", cookie);
            JSONObject cacheJson = new JSONObject();
            cacheJson.set("user", userJson);
            Files.writeString(cacheFile, cacheJson.toString(), StandardCharsets.UTF_8);
            log.info("BD cache saved to {}", cacheFile);
        } catch (Exception e) {
            log.error("save BD cache error", e);
        }
    }

    /**
     * 反射热刷新 BaiduDrive 的 cookie，让当前 JVM 进程内的 BD spider 立即生效。
     * 调用 BaiduDrive.setCookie(cookie)（Kotlin object 的静态方法）。
     * 参考 BaiduDrive.kt:42 setCookie(extend: String)
     *
     * 注意：spider.jar 通过 URLClassLoader 加载，不能用 Class.forName（系统类加载器
     * 看不到 spider.jar 内的类），必须用 SpiderJarLoader 的类加载器。
     */
    private void refreshBaiDuSpiderCookie(String cookie) {
        try {
            ClassLoader loader = spiderJarLoader.getRecentClassLoader();
            if (loader == null) {
                log.warn("Failed to hot-refresh BaiDu cookie: no spider class loader available");
                return;
            }
            Class<?> baiduDriveClass = loader.loadClass("com.github.catvod.api.BaiduDrive");
            // BaiduDrive 是 Kotlin object，通过 INSTANCE 字段拿单例
            Object baiduDriveInstance = baiduDriveClass.getField("INSTANCE").get(null);
            java.lang.reflect.Method setCookieMethod = baiduDriveClass.getMethod("setCookie", String.class);
            setCookieMethod.invoke(baiduDriveInstance, cookie);
            log.info("BaiDu spider cookie hot-refreshed");
        } catch (Exception e) {
            log.warn("Failed to hot-refresh BaiDu cookie: {}", e.getMessage());
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
