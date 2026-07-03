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
git clone https://github.com/lanche-furry/PhtonView.git
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

### 使用了 libgphoto2 库，该库采用了LGPL-2.1-or-later 及其他许可证的组合
### 使用了 libusb 库，该库的核心库为LGPL-2.1+
### 使用了 libltdl 库，该库采用了LGPL-2.0-or-later WITH Libtool-exception  
### 其他依赖采用了Apache-2.0
### 详见 NOTICE 文件
