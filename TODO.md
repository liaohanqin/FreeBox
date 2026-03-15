# FreeBox 迁移计划：任务清单

## 阶段 1：后端服务改造（基于现有 FreeBox）
- [x] **任务 1.1：调查 FreeBox 启动模式**
  - 了解如何让 FreeBox 以"无头服务"模式运行。
- [x] **任务 1.2：打包 FreeBox 为后端服务**
  - 引入 `--headless` 参数，绕过 JavaFX 渲染与弹窗逻辑。已在 FreeBoxApplication.java 中实现，AppImage 打包已完成。
- [x] **任务 1.3：编写启动和配置指南**
  - 编写 `BACKEND_SETUP.md`（已修正 wsPort 为 9898，补充 AppImage 启动说明）。

## 阶段 2：Emacs 客户端开发（核心）
- [x] **任务 2.1：搭建开发分支及环境 (Emacs 端)**
  - `/home/lynx/git/freebox.el/` 目录已建立，包含全部核心文件。
- [x] **任务 2.2：实现 WebSocket 客户端**
  - `freebox-ws-client.el` 已实现，修复了 `require` 顺序、`push` 改为 splicing 方式构建 alist。
- [x] **任务 2.3：实现数据模型与解析**
  - `freebox-model.el` 已实现 `freebox-source`、`freebox-vod-info`、`freebox-series` 等访问器。
- [x] **任务 2.4：实现核心业务函数**
  - `freebox-api.el` 已实现：获取源列表、搜索、获取详情、获取播放 URL、获取分类。
- [x] **任务 2.5：实现 UI 界面**
  - `freebox-ui.el` 已实现：源选择、搜索、详情、选集流程。
  - 修复：`flags` 从 symbol 转为 string，搜索流程改为回调链式以避免异步竞态。
- [x] **任务 2.6：集成 empv 播放器**
  - `freebox-empv.el` 已实现，调用 `empv-play` 播放 URL。
- [x] **任务 2.7：主要命令注册与集成**
  - `freebox-commands.el` 已实现并注册 `;;;###autoload` 命令。
  - 已在 `oremacs/setup-files/setup-freebox.el` 中完成 use-package 集成，快捷键前缀 `C-c v`。

## 阶段 3：功能增强（历史与收藏等）
- [ ] **任务 3.1：历史记录功能**
- [ ] **任务 3.2：收藏管理功能**
- [ ] **任务 3.3：文档和测试**

*(注：每完成一项任务请将 `[ ]` 更新为 `[x]`)*
