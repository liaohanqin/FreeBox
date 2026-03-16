package io.knifer.freebox;

import io.knifer.freebox.component.tray.TrayManager;
import io.knifer.freebox.context.Context;
import io.knifer.freebox.component.node.SplashScreen;
import io.knifer.freebox.constant.AppEvents;
import io.knifer.freebox.constant.Views;
import io.knifer.freebox.exception.GlobalExceptionHandler;
import io.knifer.freebox.helper.ToastHelper;
import io.knifer.freebox.ioc.IOC;
import io.knifer.freebox.service.LoadConfigService;
import io.knifer.freebox.util.FXMLUtil;
import io.knifer.freebox.util.catvod.SpiderInvokeUtil;
import javafx.application.Application;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class FreeBoxApplication extends Application {

    private static final Logger log = LoggerFactory.getLogger(FreeBoxApplication.class);

    private SplashScreen splashScreen;
    private Context context;
    private TrayManager trayManager;
    private static boolean headless = false;

    @Override
    public void start(Stage stage) throws IOException {
        LoadConfigService loadConfigService;

        headless = getParameters().getRaw().contains("--headless");
        if (headless) {
            ToastHelper.setHeadless(true);
            SpiderInvokeUtil.setHeadless(true);
            javafx.application.Platform.setImplicitExit(false);
        }

        try {
            // 启动画面（仅 GUI 模式）
            if (!headless) {
                splashScreen = new SplashScreen(0.8);
                splashScreen.show();
            }
            loadConfigService = new LoadConfigService();
            loadConfigService.setOnSucceeded(event -> {
                // 初始化IOC容器和上下文
                IOC.init(headless ? null : stage);
                context = IOC.getBean(Context.class);
                context.init(this, () -> {
                    if (!headless) {
                        setupGuiMode(stage);
                    } else {
                        setupHeadlessMode(stage);
                    }
                    context.postEvent(AppEvents.APP_INITIALIZED);
                    closeSplashScreen();
                });
            });
            loadConfigService.start();
        } catch (Exception e) {
            log.error("app start failed", e);
            closeSplashScreen();
            ToastHelper.showException(e);
        }
    }

    /**
     * GUI 模式：显示主界面，安装托盘，点 X 隐藏到托盘
     */
    private void setupGuiMode(Stage stage) {
        FXMLUtil.load(Views.HOME, stage);
        stage.setTitle("FreeBox");

        if (TrayManager.isSupported()) {
            trayManager = new TrayManager(stage);
            trayManager.install(false);

            // 点击窗口关闭按钮 -> 隐藏到托盘，而非退出
            stage.setOnCloseRequest(evt -> {
                evt.consume();
                trayManager.hideToTray();
            });
        }

        stage.show();
    }

    /**
     * 无头模式：直接进入系统托盘，不显示任何窗口。
     * 点击托盘"显示主界面"时，动态创建并显示主 Stage。
     */
    private void setupHeadlessMode(Stage stage) {
        if (!TrayManager.isSupported()) {
            log.warn("headless mode: system tray not supported, falling back to keep-alive thread");
            new KeepAliveThread().start();
            return;
        }

        trayManager = new TrayManager(null);

        // 无头模式下"显示主界面"的回调：按需创建 Stage
        trayManager.setShowStageCallback(() -> {
            // 此回调已在 JavaFX 线程中执行（由 TrayManager.showMainWindow 通过 Platform.runLater 调用）
            Stage mainStage = new Stage();
            FXMLUtil.load(Views.HOME, mainStage);
            mainStage.setTitle("FreeBox");

            // 关闭时同样隐藏到托盘
            mainStage.setOnCloseRequest(evt -> {
                evt.consume();
                trayManager.hideToTray();
            });

            // 告知 TrayManager 持有此 Stage，后续"显示"直接 show 即可
            trayManager.setPrimaryStage(mainStage);
            mainStage.show();
            mainStage.toFront();

            // HomeController.initialize() 注册了 APP_INITIALIZED 监听器来完成 UI 初始化
            // （隐藏转圈、加载客户端列表等）。无头模式下该事件在窗口创建前已经 post 过，
            // 因此需要在窗口显示后再 post 一次，让监听器得以执行。
            context.postEvent(AppEvents.APP_INITIALIZED);
        });

        trayManager.install(true);
        log.info("headless mode: system tray installed");
    }

    @Override
    public void stop() {
        closeSplashScreen();
        if (trayManager != null) {
            trayManager.uninstall();
        }
        if (context != null) {
            context.destroy();
        }
    }

    private void closeSplashScreen() {
        if (splashScreen != null) {
            splashScreen.close();
            splashScreen = null;
        }
    }

    public static void main(String[] args) {
        // 必须在 JavaFX 启动前设置，防止 JavaFX 将 AWT 置为 headless 模式，
        // 否则 SystemTray.isSupported() 会返回 false
        System.setProperty("java.awt.headless", "false");
        Thread.setDefaultUncaughtExceptionHandler(GlobalExceptionHandler.getInstance());
        // 必须传入 args，否则 getParameters().getRaw() 永远为空，--headless 参数无法被识别
        launch(args);
    }
}
