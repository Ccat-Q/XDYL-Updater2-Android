# 星灯云浪 Android

星灯云浪（StarWave）是 Minecraft 社区的 Android 伴侣应用，支持 Android 8.0 及以上。使用 Kotlin、Jetpack Compose、Material 3，包名 `com.ccatq.xdylupdater2`，初始版本 `2.1.9`。

提供账号与 QQ 登录、社区与帖子、资源下载、商城、任务、排行榜、通知、投票、意见箱、周目、皮肤与物品、称号、决斗场和开发者工具。资源下载完成后校验 SHA-256（清单提供时），通过 Android 文件选择器导出或分享。应用不负责启动电脑端 Minecraft 或同步电脑目录。

## 编译与验证

**仅使用 GitHub Actions 编译，不在本机安装或运行 Android SDK、JDK、Gradle 编译工具。**

[Android 工作流](.github/workflows/android.yml) 在 main 推送、PR 和手动运行时执行：

1. 静态检查、离线单元测试、Android Lint；生成 debug APK 与 instrumentation APK。
2. Android API 26、36 模拟器测试导航、登录契约和写操作取消；同密钥升级检查数据保留；保存浅色/深色大字体截图和日志。
3. main 推送、手动勾选 `signed` 或推送 `v*` 标签时，前述检查通过后构建并验证正式签名 APK。仅标签构建发布 GitHub Release。

下载 Actions 的 `StarWave-debug`、`Android-build-reports` 和 `Android-device-api*` 查看结果；正式 APK 在 `StarWave-signed`。debug 包使用 `.debug` 后缀，可与正式应用并存。

工具版本固定：AGP 9.0.1、Gradle 9.1.0、JDK 17、Android SDK 36、Build Tools 36.0.0。GitHub runner 缓存 Gradle 依赖、固定 SDK 和模拟器镜像；只有 main 的可信构建写入缓存。签名任务关闭构建缓存和配置缓存，密钥与 APK 不进入缓存。工作流另提供 `Gradle-wrapper-9.1.0` 产物；本轮不在本机下载 Wrapper 或构建工具。

## 正式签名

本轮已生成新的长期签名身份，存放在 Git 忽略的 `.local/signing/` 中。**务必单独安全备份 keystore 和 signing.properties；以后升级必须使用同一身份，不能重新生成替换。**

仓库 Settings → Secrets and variables → Actions 需要四个 Secrets：

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

`tools/export_signing_secrets.py` 可准备权限为 600 的本地 `github-secrets.json`，用于私下配置；内容不得进入 Git、日志或聊天。缺少 Secrets 时正式签名失败，不回退到 debug 或未签名 APK。runner 的临时密钥在任务结束时删除。

正式更新查看本 Android 仓库的 Release，比较版本后在浏览器打开 APK 下载页。发布版本标签需与 `app/build.gradle.kts` 的 `versionName` 一致，后续发布必须递增 `versionCode`。

## 网络与数据

登录令牌使用 Android Keystore 加密并存放在禁止备份的应用私有目录；账号与社区请求限制为官方 HTTPS 地址。资源清单保留官方旧 HTTP 端口，DownloadManager 只检查初始 URL，后续重定向由系统处理，不携带账号令牌。

按已确认需求，连续点击版本七次启用开发者工具，正式 APK 也允许自定义 HTTP 请求。该执行器独立于业务客户端；令牌默认关闭，写请求需确认，DELETE 需额外输入确认。会话持久化加密，默认导出脱敏，原始导出需明确确认。

## 文档与交付状态

- [接口契约与兼容性](docs/API_COMPATIBILITY.md)
- [Android UI 规范](docs/UI_DESIGN.md)
- [验证范围与待验收项](docs/VALIDATION.md)
- [领域术语](CONTEXT.md)
- [架构决策](docs/adr/0001-native-android.md)

iOS 源码、Xcode 配置、旧 IPA 工作流、旧产物与 EXE 已从当前工作树删除，原始内容保留在 Git 历史。本轮仅完成源码、工作流与静态验证，**尚未运行 GitHub Actions，尚无已验证 APK**。推送、上传 Secrets、运行 Actions 和发布 Release 需按本轮已确认范围另行授权。
