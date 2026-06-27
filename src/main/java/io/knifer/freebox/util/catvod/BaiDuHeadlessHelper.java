package io.knifer.freebox.util.catvod;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * BaiDuYunHandler 在无头模式下的支持工具
 *
 * 问题描述：
 * - BaiDuYunHandler.startScan() 使用 javax.swing.JDialog 展示二维码扫描窗口
 * - 无头模式下 JDialog 初始化会失败，导致 BaiDuDrive.processShareLinks 异常
 *
 * 解决方案：
 * - 调用 BaiDuYunHandler.setHeadlessMode(true) 标记无头模式
 * - BaiDuYunHandler.startScan() 内部会跳过 GUI 流程，直接返回空 ByteArray
 *
 * 仿 QuarkHeadlessHelper 结构。
 */
@Slf4j
@UtilityClass
public class BaiDuHeadlessHelper {

    private static final String BAIDU_HANDLER_CLASS_NAME = "com.github.catvod.api.BaiDuYunHandler";
    private static final String BAIDU_SPIDER_CLASS_NAME = "com.github.catvod.spider.BaiDuPan";

    /**
     * 检查是否为百度网盘爬虫
     */
    public boolean isBaiDuSpider(Object spider) {
        if (spider == null) {
            return false;
        }
        String className = spider.getClass().getName();
        return className.equals(BAIDU_SPIDER_CLASS_NAME);
    }

    /**
     * 在无头模式下设置 BaiDuYunHandler 的无头标志
     * 通过反射调用 BaiDuYunHandler.setHeadlessMode(true)
     */
    public void setupHeadlessBaiDuEnvironment() {
        try {
            Class<?> handlerClazz = Class.forName(BAIDU_HANDLER_CLASS_NAME);

            // 调用 setHeadlessMode(true) 静态方法
            java.lang.reflect.Method setHeadlessModeMethod =
                    handlerClazz.getMethod("setHeadlessMode", boolean.class);
            setHeadlessModeMethod.invoke(null, true);

            log.info("BaiDuYunHandler headless mode enabled");
        } catch (Exception e) {
            log.warn("Failed to setup BaiDuYunHandler headless mode: {}", e.getMessage());
        }
    }
}
