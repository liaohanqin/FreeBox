package io.knifer.freebox.util.catvod;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * Quark API 在无头模式下的支持工具
 *
 * 问题描述：
 * - QuarkApi 使用 javax.swing.JDialog 展示二维码扫描窗口
 * - 无头模式下 JDialog 初始化会失败，导致 InitUserInfo 异常
 * - 异常后调用 stopService() 会中断后台线程，导致 InterruptedException
 *
 * 解决方案：
 * - 调用 QuarkApi.setHeadlessMode(true) 标记无头模式
 * - QuarkApi 内部会跳过 GUI 流程
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
     * 在无头模式下设置 QuarkApi 的无头标志
     * 通过反射调用 QuarkApi.setHeadlessMode(true)
     */
    public void setupHeadlessQuarkEnvironment() {
        try {
            Class<?> quarkApiClazz = Class.forName(QUARK_API_CLASS_NAME);

            // 调用 setHeadlessMode(true) 静态方法
            java.lang.reflect.Method setHeadlessModeMethod = quarkApiClazz.getMethod("setHeadlessMode", boolean.class);
            setHeadlessModeMethod.invoke(null, true);

            log.info("QuarkApi headless mode enabled");
        } catch (Exception e) {
            log.warn("Failed to setup QuarkApi headless mode: {}", e.getMessage());
        }
    }
}
