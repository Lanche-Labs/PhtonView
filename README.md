# PhtonView

一个适用于 Android 的专业相机遥控应用，通过 USB OTG 或实验性 WiFi 连接控制单反 / 无反相机。
> By lanche-furry

## 功能特性

- **实时取景**：在手机上查看相机实时画面（Live View）。
- **参数遥控**：曝光补偿、ISO、光圈、快门、测光模式、对焦模式等。
- **拍摄模式**：单拍、连拍、定时拍摄、B 门、间隔拍摄、包围曝光。
- **连接方式**：USB OTG（依赖 libgphoto2 / libusb）以及实验性 WiFi 配对。
- **界面模式**：简洁模式与专业模式，满足不同使用习惯。
- **主题与语言**：支持浅色 / 深色 / 跟随系统主题，以及多语言界面。

## 技术栈

- **UI**：Jetpack Compose + Material Design 3
- **架构**：MVVM、Hilt 依赖注入、Kotlin Coroutines / Flow
- **本地数据**：DataStore Preferences
- **原生层**：libgphoto2、libusb、CMake / JNI

## 构建要求

- Android Studio Jellyfish 或更高版本
- JDK 17+
- Android SDK 33+
- NDK（项目通过 CMake 编译原生库）

## 构建步骤

```bash
# 1. 克隆仓库
git clone https://github.com/Lanche-Labs/PhtonView.git
cd PhtonView

# 2. 使用 Android Studio 打开项目，或执行 Gradle 构建
./gradlew assembleDebug
```

> 首次构建会触发 libgphoto2 / libusb 等原生库的编译，可能需要较长时间。

## 使用说明

1. 使用 USB OTG 线连接相机与手机。
2. 授予 USB 访问权限。
3. 在应用中点击连接教程或等待自动识别设备。
4. 若使用 WiFi，请先在设置中开启「WiFi 连接（实验性）」并按提示配对。

## 许可证

**PhtonView 采用 MIT 协议开源。**

版权所有 © 2026 Lanche-Labs

特此免费授予任何获得本软件副本及相关文档文件的人不受限制地处理本软件的权利，包括但不限于使用、复制、修改、合并、发布、分发、再许可和/或出售本软件副本的权利，并允许向其提供本软件的人做出上述行为，但须符合以下条件：

上述版权声明和本许可声明应包含在本软件的所有副本或重要部分中。

本软件按“原样”提供，不提供任何形式的明示或暗示担保，包括但不限于对适销性、特定用途适用性和非侵权性的担保。在任何情况下，作者或版权持有人均不对任何索赔、损害或其他责任负责，无论是在合同诉讼、侵权诉讼或其他诉讼中，因本软件或本软件的使用或其他交易而引起、由本软件引起或与之相关。

---

本应用使用了以下第三方库，特此致谢：

| 库 | 许可证 | 版权持有者 |
|---|---|---|
| AndroidX / Jetpack Compose / Material 3 | Apache-2.0 | The Android Open Source Project |
| Kotlin Coroutines | Apache-2.0 | JetBrains s.r.o. |
| Hilt | Apache-2.0 | Google, Inc. |
| Android Gradle Plugin | Apache-2.0 | The Android Open Source Project |
| libgphoto2 | LGPL-2.1-or-later（含 GPL-2.0-only、BSD、MIT、IJG-short 组件） | The gPhoto Project and contributors |
| libusb | LGPL-2.1+ | The libusb Project and contributors |
| libltdl | LGPL-2.0-or-later WITH Libtool-exception | Free Software Foundation, Inc. |

> 详细的许可证声明、版权信息及各协议全文，请参见项目 [LICENSES/](LICENSES/) 目录。
