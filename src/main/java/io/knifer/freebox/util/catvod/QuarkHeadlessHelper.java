package io.knifer.freebox.util.catvod;

import io.knifer.freebox.helper.ToastHelper;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Quark API 在无头模式下的支持工具
 *
 * 问题描述：
 * - QuarkApi 使用 javax.swing.JDialog 展示二维码扫描窗口
 * - 无头模式下 JDialog 初始化会失败，导致 InitUserInfo 异常
 * - 异常后调用 stopService() 会中断后台线程，导致 InterruptedException
 *
 * 解决方案：
 * - 在无头模式下，使用动态代理拦截 ScheduledExecutorService
 * - 禁止定时任务执行（避免触发二维码扫描流程）
 * - 或者直接禁用 QuarkApi 的后台服务启动
 *
 * @author Knifer
 */
@Slf4j
@UtilityClass
public class QuarkHeadlessHelper {

    private static final String QUARK_API_CLASS_NAME = "com.github.catvod.api.QuarkApi";
    private static final String QUARK_SPIDER_CLASS_NAME = "com.github.catvod.spider.Quark";

    /**
     * 检查是否为 Quark 爬虫
     */
    public boolean isQuarkSpider(Object spider) {
        if (spider == null) {
            return false;
        }
        String className = spider.getClass().getName();
        return className.equals(QUARK_SPIDER_CLASS_NAME);
    }

    /**
     * 检查是否为 Quark API 实例
     */
    public boolean isQuarkApi(Object obj) {
        if (obj == null) {
            return false;
        }
        String className = obj.getClass().getName();
        return className.equals(QUARK_API_CLASS_NAME);
    }

    /**
     * 在无头模式下禁用 QuarkApi 的后台服务
     * 通过反射获取 QuarkApi 单例，禁止其启动 ScheduledExecutorService
     */
    public void disableQuarkBackgroundService() {
        try {
            // 获取 QuarkApi 类
            Class<?> quarkApiClazz = Class.forName(QUARK_API_CLASS_NAME);

            // 获取 get() 静态方法获取单例
            Method getMethod = quarkApiClazz.getMethod("get");
            Object quarkApiInstance = getMethod.invoke(null);

            if (quarkApiInstance != null) {
                // 尝试获取 service 字段（ScheduledExecutorService）
                try {
                    Field serviceField = quarkApiClazz.getDeclaredField("service");
                    serviceField.setAccessible(true);

                    ScheduledExecutorService service = (ScheduledExecutorService) serviceField.get(quarkApiInstance);
                    if (service != null && !service.isShutdown()) {
                        service.shutdownNow();
                        log.info("QuarkApi background service has been disabled in headless mode");
                    }
                } catch (NoSuchFieldException e) {
                    // service 字段可能不存在或名称不同，继续
                    log.debug("Cannot find service field in QuarkApi, skipping...");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to disable QuarkApi background service: {}", e.getMessage());
            // 不严重，继续运行
        }
    }

    /**
     * 在无头模式下为 QuarkApi 的 startFlow 方法设置空操作
     * startFlow 会尝试弹出 GUI 窗口，无头模式下应该跳过
     */
    public void disableQuarkGUIFlow() {
        try {
            Class<?> quarkApiClazz = Class.forName(QUARK_API_CLASS_NAME);
            Method getMethod = quarkApiClazz.getMethod("get");
            Object quarkApiInstance = getMethod.invoke(null);

            if (quarkApiInstance != null) {
                // 尝试调用 stopService 来停止后台流程
                try {
                    Method stopServiceMethod = quarkApiClazz.getDeclaredMethod("stopService");
                    stopServiceMethod.setAccessible(true);
                    stopServiceMethod.invoke(quarkApiInstance);
                    log.info("QuarkApi service stopped in headless mode");
                } catch (NoSuchMethodException e) {
                    log.debug("Cannot find stopService method in QuarkApi");
                }
            }
        } catch (Exception e) {
            log.warn("Failed to disable QuarkApi GUI flow: {}", e.getMessage());
        }
    }

    /**
     * 获取 QuarkApi 单例
     */
    public Object getQuarkApiInstance() {
        try {
            Class<?> quarkApiClazz = Class.forName(QUARK_API_CLASS_NAME);
            Method getMethod = quarkApiClazz.getMethod("get");
            return getMethod.invoke(null);
        } catch (Exception e) {
            log.debug("Failed to get QuarkApi instance: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 为 Quark Spider 设置特殊的初始化参数（无头模式）
     * 包括禁用 GUI 流程等
     */
    public void setupHeadlessQuarkEnvironment() {
        log.info("Setting up headless environment for Quark spider...");

        // 1. 停止已有的后台服务
        disableQuarkBackgroundService();

        // 2. 禁用 GUI 流程
        disableQuarkGUIFlow();

        log.info("Headless Quark environment setup completed");
    }
}
