# FreeBox Backend Setup Guide

本文档介绍如何将 FreeBox 作为独立的无头（Headless）后台服务运行，供 Emacs 客户端或其他前端应用连接。

## 启动方式

得益于对 `--headless` 参数的支持，FreeBox 可以在不显示任何图形界面（JavaFX UI）的情况下启动，并在后台提供 WebSocket 和 HTTP 代理服务。

### 通过 Gradle 启动（开发模式）

在 FreeBox 源代码目录下运行：

```bash
./gradlew run --args="--headless"
```

### 通过打包后的可执行文件启动

如果您已经构建或下载了 FreeBox 的可执行程序（例如 Linux 下的 deb 安装或免安装版本），只需在命令后追加 `--headless` 参数即可。例如：

```bash
java -jar FreeBox.jar --headless
```

或使用系统自带路径（视系统或安装方式而定）：

```bash
/opt/freebox/bin/FreeBox --headless
```

**注意**：在 Linux 下运行仍然需要环境变量 `DISPLAY` 被设置（如在 Emacs 内部通过 `start-process` 调用时默认继承 X11 环境），但由于传入了 `--headless` 参数，应用将完全隐藏窗口。

## 配置与端口

FreeBox 在启动后会根据其配置文件决定监听的端口和启动的服务。默认情况下，FreeBox 的配置文件存放于操作系统的本地配置目录（例如 `~/.freebox/config/config.json` 或 `%USERPROFILE%\.freebox\config\config.json`）。

### 核心参数

您可以直接编辑 `config.json` 文件以修改关键配置，以下为默认示例及说明：

```json
{
  "serviceIPv4": "0.0.0.0",   // 监听的 IP，0.0.0.0 表示监听所有网卡
  "httpPort": 9978,           // HTTP 代理及静态文件服务端口
  "wsPort": 9977,             // WebSocket 服务端口
  "autoStartHttp": true,      // 启动时自动开启 HTTP 服务（必须设为 true）
  "autoStartWs": true         // 启动时自动开启 WebSocket 服务（必须设为 true）
}
```

- **WebSocket 服务**：用于 Emacs 客户端与 FreeBox 通信，包含发送指令、搜索、获取源列表等。
- **HTTP 服务**：主要用于提供视频资源的代理转发，以及部分本地图片的缓存服务。

## 连接测试

启动服务后，您可以通过以下方式确认后台是否存活：

1. **测试 HTTP 服务**：在浏览器或终端访问 `http://127.0.0.1:9978/`，或者查看日志是否有 `http service start successfully` 的字样。
2. **测试 WebSocket**：尝试通过 ws 工具（如 wscat）连接 `ws://127.0.0.1:9977/api/ws/keb`。

服务一旦启动成功，即可准备供前端客户端接入。
