package io.knifer.freebox.component.tray;

import io.knifer.freebox.context.Context;
import io.knifer.freebox.ioc.IOC;
import javafx.application.Platform;
import javafx.stage.Stage;
import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 系统托盘管理器
 * <p>
 * 负责在系统托盘中创建 FreeBox 图标，右键菜单包含"显示主界面"和"退出"。
 * 支持两种场景：
 * <ul>
 *   <li>无头模式（--headless）：启动时直接进入托盘，点"显示"时按需创建主界面。</li>
 *   <li>GUI 模式：点击窗口关闭按钮时隐藏到托盘，点"显示"时恢复窗口。</li>
 * </ul>
 *
 * @author Knifer
 */
@Slf4j
public class TrayManager {

    private static final String TRAY_TOOLTIP = "FreeBox";

    private TrayIcon trayIcon;
    private Stage primaryStage;
    private final AtomicBoolean stageVisible = new AtomicBoolean(false);

    /**
     * 在"显示主界面"时，若 Stage 尚未创建（无头模式），通过此回调请求创建并显示。
     * GUI 模式下此回调不会被调用。
     */
    private Runnable showStageCallback;

    public TrayManager(Stage primaryStage) {
        this.primaryStage = primaryStage;
    }

    /**
     * 设置"按需显示主界面"的回调（无头模式专用）
     */
    public void setShowStageCallback(Runnable callback) {
        this.showStageCallback = callback;
    }

    /**
     * 检查当前系统是否支持系统托盘
     */
    public static boolean isSupported() {
        return SystemTray.isSupported();
    }

    /**
     * 初始化并安装系统托盘图标。
     * AWT 初始化在独立线程中进行，不阻塞 JavaFX 线程。
     *
     * @param headless 是否为无头模式
     */
    public void install(boolean headless) {
        if (!isSupported()) {
            log.warn("SystemTray is not supported on this platform, skip tray installation");
            return;
        }

        Thread awtThread = new Thread(() -> {
            try {
                doInstall(headless);
            } catch (Exception e) {
                log.error("Failed to install system tray", e);
            }
        }, "FreeBox-TrayInstaller");
        awtThread.setDaemon(true);
        awtThread.start();
    }

    private void doInstall(boolean headless) throws AWTException, IOException {
        SystemTray tray = SystemTray.getSystemTray();
        Image icon = loadTrayIcon(tray);
        PopupMenu popupMenu = buildPopupMenu();

        trayIcon = new TrayIcon(icon, TRAY_TOOLTIP, popupMenu);
        trayIcon.setImageAutoSize(true);
        trayIcon.setToolTip(TRAY_TOOLTIP + " - 后台服务运行中");

        tray.add(trayIcon);
        log.info("System tray installed (headless={})", headless);

        if (!headless) {
            // GUI 模式：主窗口已由 FreeBoxApplication 显示
            stageVisible.set(true);
        }
    }

    private Image loadTrayIcon(SystemTray tray) {
        try {
            URL logoUrl = TrayManager.class.getClassLoader()
                    .getResource("io/knifer/freebox/image/logo.png");
            if (logoUrl != null) {
                try (InputStream in = logoUrl.openStream()) {
                    java.awt.image.BufferedImage img = ImageIO.read(in);
                    Dimension traySize = tray.getTrayIconSize();
                    return img.getScaledInstance(traySize.width, traySize.height, java.awt.Image.SCALE_SMOOTH);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to load tray icon from resources, using default", e);
        }
        return new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    }

    /**
     * 构建右键菜单：两种模式统一提供"显示主界面"和"退出 FreeBox"
     */
    private PopupMenu buildPopupMenu() {
        PopupMenu menu = new PopupMenu();

        MenuItem showItem = new MenuItem("显示主界面");
        showItem.addActionListener(e -> showMainWindow());
        menu.add(showItem);

        menu.addSeparator();

        MenuItem exitItem = new MenuItem("退出 FreeBox");
        exitItem.addActionListener(e -> {
            log.info("Exit requested from system tray");
            Platform.runLater(() -> IOC.getBean(Context.class).destroy());
        });
        menu.add(exitItem);

        return menu;
    }

    /**
     * 显示主窗口：
     * - 若 Stage 已存在且可见：置顶
     * - 若 Stage 已存在但隐藏：show + 置顶
     * - 若 Stage 尚未创建（无头模式首次点击）：触发回调由 FreeBoxApplication 创建
     */
    private void showMainWindow() {
        Platform.runLater(() -> {
            if (primaryStage != null) {
                primaryStage.show();
                primaryStage.toFront();
                stageVisible.set(true);
            } else if (showStageCallback != null) {
                // 无头模式：首次点击"显示"，通过回调创建并显示主界面
                showStageCallback.run();
            }
        });
    }

    /**
     * 当主窗口被关闭（点 X）时，隐藏到托盘而非退出。
     * 应在 stage.setOnCloseRequest 中调用。
     */
    public void hideToTray() {
        Platform.runLater(() -> {
            if (primaryStage != null) {
                primaryStage.hide();
                stageVisible.set(false);
            }
        });
    }

    /**
     * 绑定已创建的 Stage（无头模式首次显示主界面后调用）
     */
    public void setPrimaryStage(Stage stage) {
        this.primaryStage = stage;
        stageVisible.set(stage != null && stage.isShowing());
    }

    /**
     * 卸载托盘图标（应用真正退出时调用）
     */
    public void uninstall() {
        if (trayIcon != null && SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(trayIcon);
            trayIcon = null;
            log.info("System tray uninstalled");
        }
    }
}
