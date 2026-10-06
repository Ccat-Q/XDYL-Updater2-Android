# 使用 Kotlin 与 Compose 构建独立 Android 应用

本仓库使用 Kotlin、Jetpack Compose、Material 3 和单 app 模块按功能分包，完整迁移 iOS 的业务契约。相较 Flutter 和多 Gradle 功能模块，原生方案更直接对接 Android 下载、存储、生命周期及调试能力，也减少当前单应用的构建与维护成本；iOS 与 Android 分别维护。

Actions 首次验证发现旧 KSP 仍向 kotlin.sourceSets 注册生成代码，与 AGP 9 内置 Kotlin 冲突。升级到修复该问题的 KSP 2.3.4，继续使用内置 Kotlin，不通过关闭源码集校验掩盖冲突。
