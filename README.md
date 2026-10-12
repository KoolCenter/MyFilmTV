# MyFilm TV - Android TV MVP v0.1

一个电视遥控器优先的 Android TV 客户端。电影数据来自用户指定的 HTTPS JSON URL 中列出的 MacCMS v10 兼容 API；播放器直接从第三方合法授权视频地址播放，不需要中转服务器。

## 云端 JSON

仓库根目录 `sources.json` 已提供完整示例，含 19 条公开列于第三方资源清单的 API，其中默认启用 4 条以减少首次请求耗时。**这些来源仅在公开清单中出现，没有对可达性、播放有效性或内容授权作出保证；也不能认定为 GoFilm 默认预置来源。** 公开清单： https://github.com/cms-player/vod-source/blob/main/source.json

上传 `sources.json` 至你自己的 GitHub 仓库后，在文件页面点 Raw，复制 `https://raw.githubusercontent.com/<user>/<repo>/<branch>/sources.json` 至电视设置。云端 JSON 必须是标准 JSON，不是带尾随逗号的 JSONC。APK 只接受 HTTPS 配置和 HTTPS API；视频播放 URL 同时接受 http/https（如需 HTTP 播放，需要按目标安卓系统网络策略进一步明确允许范围，当前 Manifest 禁止明文，故 HTTP 线路很可能失败）。

## 编译

1. Android Studio 打开本目录，使用 JDK 17 和安装 Android SDK 35。
2. Gradle Sync 后 Build > Build APK(s)，或在配置了 Gradle 的本机运行 `gradle assembleDebug`。
3. 使用 `adb install -r app/build/outputs/apk/debug/app-debug.apk` 安装。
4. 首次打开输入 GitHub Raw 配置 URL，使用电视遥控器操作。

## 遥控器

* 上下左右：焦点导航；当前海报有蓝色高亮边框。
* OK：进入电影线路选择；在播放器中播放/暂停。
* 返回：关闭线路弹窗 / 退出播放界面。
* 播放时 左/右：后退/前进 10 秒。
* MENU：随时打开配置 URL 设置。
* 搜索、设置 URL、刷新都是可聚焦按钮；输入依赖 Android TV 系统键盘。

## 能力边界

v0.1 每个启用站请求首页第一页或搜索第一页（`ac=detail&pg=1`），不是全站数据库同步。只有返回 `vod_play_from` / `vod_play_url` 以及可直链的 MP4 / HLS / DASH URL 才可以直接播放；需要网页解析、特殊签名、Referer、DRM、Cookie 的线路暂不支持。电影合并仅按去空格/标点后片名和年份，存在误合并或漏合并可能。网络工作在后台线程，不阻塞 UI，但 v0.1 聚合请求顺序执行，下一版可改成并发分页。

自动刷新：v0.1 **每次进入应用加载远程配置**，并在「刷新」按钮手动刷新；`refreshMinutes` 作为保留字段，尚未实现应用运行期间的定时轮询。断网时使用最后成功下载的 JSON 缓存。

本压缩包提供完整源码，没有预构建 APK：当前构建环境缺少 Android SDK/Gradle，因此尚未进行真实编译和 TCL Android TV 实机测试。
