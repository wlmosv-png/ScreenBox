# 构建

ScreenBox 是标准的 Gradle Android 工程。本文说明如何从源码构建。

## 环境要求

- **JDK 17**（`sourceCompatibility` / `targetCompatibility` 为 Java 17）
- **Android SDK**，`compileSdk = 35`、`buildTools 35.0.0`
- **Android Gradle Plugin 8.9.2**、**Gradle 8.11.1**（仓库已附 Gradle Wrapper）

> JDK 版本注意：Kotlin 2.1.0 无法解析 JDK 25 的版本号。请使用 JDK 17 或 21，不要用 JDK 25。

> aarch64 宿主（部分 ARM 设备/容器）注意：Google 的 aapt2 只有 x86-64 版，ARM 上需要 qemu 包装。请在本机 `~/.gradle/gradle.properties` 里设置 `android.aapt2FromMavenOverride=/你的/aapt2`，**不要写进仓库的 gradle.properties**，否则 x86-64 的 CI 会因找不到该路径而失败。

## 配置 SDK

仓库根目录的 `local.properties` 指向 Android SDK：

```properties
sdk.dir=/workspace/android-sdk
```

请按你的实际 SDK 路径修改。`local.properties` 不入库。

## 构建命令

使用 Gradle Wrapper（推荐）：

```bash
./gradlew assembleDebug          # 构建 debug APK
./gradlew testDebugUnitTest      # 运行单元测试
./gradlew assembleRelease        # 构建 release（未配置签名，产物未签名）
```

debug APK 输出位置：

```
app/build/outputs/apk/debug/app-debug.apk
```

## 直接使用系统 Gradle

如果不用 Wrapper，确保 JDK 17 在 `PATH` 中：

```bash
gradle assembleDebug
gradle testDebugUnitTest
```

## 签名

当前工程只配置了 debug 签名（Android 调试密钥），用于预览发布。release 构建未配置独立签名，`assembleRelease` 产物为未签名 APK。

- debug 签名证书指纹见 [README 的安装说明](../README.md#安装)。
- 如需正式 release 签名，请在 `app/build.gradle.kts` 的 `signingConfigs` 中配置，并**不要将签名密钥提交到仓库**。

## 测试

单元测试位于 `app/src/test/`，覆盖：

- 领域判定逻辑（分类、来源、特征标签、布局）
- 搜索语法与筛选编码
- 权限版本矩阵
- SQLite 批量查询
- 任务状态机

运行全部测试：

```bash
./gradlew testDebugUnitTest
```

测试报告位于 `app/build/reports/tests/`。

## 目录结构

```
screenbox/
├── app/                     应用模块
│   └── src/
│       ├── main/            源码与资源
│       ├── debug/           调试用基准 / 测试接收器
│       └── test/            单元测试
├── assets/icon/             品牌图标资源
├── branding/                品牌视觉生成脚本
├── docs/                    文档与截图
├── .github/                 Issue 模板与 CI
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew                  Gradle Wrapper
```
