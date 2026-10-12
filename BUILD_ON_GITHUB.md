# 自动生成 MyFilmTV.apk

当前源码附带 GitHub Actions 构建配置 `.github/workflows/build-apk.yml`。

1. 创建一个 GitHub 仓库（可以设为 Private）。
2. 将此目录下所有文件和 `.github` 隐藏目录一起上传到仓库根目录（不要只上传 ZIP）。
3. 进入仓库的 **Actions** 页面，选择 **Build MyFilmTV APK**，点击 **Run workflow**。
4. 构建成功后，在该工作流执行页面的 **Artifacts** 中下载 `MyFilmTV-apk`；解压后得到 `MyFilmTV.apk`。
5. 安装到 Android TV（例如使用 `adb install -r MyFilmTV.apk`）。

注意：这是调试签名 APK，仅用于测试。构建依赖 GitHub-hosted runner 的联网下载；目前没有实际完成编译或 TV 实机验证。
