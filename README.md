<<<<<<< HEAD
# Watch SCP

一个运行在 **Wear OS 圆形屏手表** 上的 SFTP 文件管理器，支持远程文件浏览、本地上传下载、传输队列与传输记录，界面针对小尺寸圆形屏幕做了适配。

- 版本：`1.8`
- 平台：Wear OS（Android 9.0 / API 28 及以上）
- 作者：张杰 · QQ 35352826514
- 声明：仅供学习交流

---

## 功能特性

### 1. SFTP 连接

- 输入服务器地址、端口、用户名、密码即可建立 SFTP 连接
- 支持保存多台服务器，连接时可直接选择已保存的服务器
- 连接成功后可随时从 ⋮ 菜单断开连接；断开后再次进入远程页需重新输入凭据，避免误用旧登录态

### 2. 远程文件管理

- 目录浏览：进入子目录、返回上级、下拉/滚动列表、刷新
- 文件操作：新建文件夹、重命名、删除、复制、剪切、粘贴
- 多选模式：批量删除、批量下载、批量复制/剪切
- 文件信息：查看路径、大小、修改时间
- 上传：直接调用应用内统一的本地文件浏览页选择文件，选完自动回到原远程目录并加入上传队列

### 3. 本地文件管理

- 浏览本机全部文件（Android 11 及以上需「所有文件访问权限」，见下方权限说明）
- 本地文件同样支持新建文件夹、重命名、删除、复制、剪切、粘贴、多选批量操作
- 支持直接安装本地 APK，也支持「下载并安装」

### 4. 传输任务

- 统一的传输队列，实时展示上传/下载进度
- 支持暂停、继续、取消单个任务，可一键删除全部任务
- 可设置最大并发传输数
- 下载文件默认保存到 `Download/watch_scp_install` 目录，可在设置中修改下载路径

### 5. 传输记录

- 记录已完成 / 失败的传输任务及时间
- 支持清空全部传输记录

### 6. 设置

- 主题模式：深色 / 浅色（全局生效并持久化）
- 字体大小、DPI 调节
- 最大并发传输数
- 下载目录选择（复用目录选择器）
- 所有设置项全局持久化，退出应用不会丢失

### 7. 圆形屏适配

- 基于 `BoxInsetLayout` 处理圆形屏幕的安全区域，底部控件不会被圆弧裁切
- 列表与内容区支持滚动到底后继续回弹，不会遮挡底部操作按钮
- 支持边缘右滑返回上一页
- ⋮ 菜单为半透明圆角卡片，背景透明不遮挡、不压暗底层页面

---

## 界面导航

| 页面 | 入口 |
| --- | --- |
| 主页 | 应用启动 |
| 远程文件 | 主页「远程文件」按钮（未连接时进入 SFTP 连接页） |
| 本地文件 | 主页「本地文件」按钮 |
| 传输任务 | 主页「传输任务」按钮 |
| 传输记录 | 主页「传输记录」按钮 |
| 设置 | 主页右上角 ⚙ |

## 首次使用

1. 打开应用，授予文件访问权限（见下方说明）
2. 点击「远程文件」，在连接页填写 SFTP 服务器信息并连接
3. 进入远程目录后，可下载文件到本机，或点「上传」从本地选择文件上传
4. 在「传输任务」中查看进度，完成后可在「传输记录」查看结果

---

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `INTERNET` / `ACCESS_NETWORK_STATE` | SFTP 网络连接 |
| `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` | 旧版存储模式下读写本地文件 |
| `MANAGE_EXTERNAL_STORAGE` | Android 11+ 浏览全部文件 |
| `REQUEST_INSTALL_PACKAGES` | 安装下载的 APK |

项目刻意使用 `targetSdk = 29` 并开启 `requestLegacyExternalStorage`，因为多数手表 ROM 没有「所有文件访问」设置页，旧版存储模式只需运行时读权限即可浏览全部文件。

如果系统设置中没有「所有文件访问」开关，可连接电脑执行：

```bash
adb shell appops set com.jay.watch_scp MANAGE_EXTERNAL_STORAGE allow
```

---

## 技术栈

- 语言：Kotlin（Coroutines）
- UI：ViewBinding + Material 3 + `androidx.wear.widget.BoxInsetLayout`
- 网络：JSch（SFTP）
- 持久化：DataStore Preferences
- 构建：Gradle Kotlin DSL（AGP 8.9 / Kotlin 2.0）
- minSdk 28、targetSdk 29、compileSdk 35

## 项目结构

```
app/src/main/java/com/jay/watch_scp/
├── MainActivity.kt          # 页面切换、转场、右滑返回、上传下载调度
├── WatchScpApp.kt           # Application，全局初始化与连接状态缓存
├── ui/
│   ├── home/                # 主页
│   ├── connect/             # SFTP 连接页
│   ├── browse/              # 远程 / 本地文件浏览（含目录选择、文件选择模式）
│   ├── transfer/            # 传输任务队列
│   ├── history/             # 传输记录
│   └── settings/            # 设置
├── data/
│   ├── network/SftpClient.kt      # SFTP 封装
│   ├── transfer/TransferManager.kt# 传输队列与并发控制
│   ├── model/                     # 文件、服务器、传输记录模型
│   └── local/preferencesmanager.kt# DataStore 配置
├── adapter/                 # 文件列表 / 传输记录适配器
└── util/                    # 对话框、文件工具、字体、圆形屏辅助
```

## 构建与打包

环境要求：JDK 11+、Android SDK 35。

```bash
# Debug
./gradlew :app:assembleDebug

# Release
./gradlew :app:assembleRelease
```

Release 产物输出为：

```
app/build/outputs/apk/release/WatchSCP-1.8.apk
```

安装到手表：

```bash
adb install -r app/build/outputs/apk/release/WatchSCP-1.8.apk
```

也可在 Android Studio 中点击 Run 直接部署到已连接的 Wear OS 设备。

## 已知说明

- SFTP 连接信息保存在本机，仅用于连接所填写的服务器
- 应用不上架 Google Play，`targetSdk 29` 相关的 lint 检查已在构建脚本中关闭
- 当前主题仅提供深色 / 浅色两种模式

## 联系作者

张杰 · QQ：35352826514

> 本项目仅供学习交流使用。
=======
# Watch_SCP

#### 介绍
一个运行在 Wear OS 圆形屏手表 上的 SFTP 文件管理器，支持远程文件浏览、本地上传下载、传输队列与传输记录，界面针对小尺寸圆形屏幕做了适配。

#### 软件架构
软件架构说明


#### 安装教程

1.  xxxx
2.  xxxx
3.  xxxx

#### 使用说明

1.  xxxx
2.  xxxx
3.  xxxx

#### 参与贡献

1.  Fork 本仓库
2.  新建 Feat_xxx 分支
3.  提交代码
4.  新建 Pull Request


#### 特技

1.  使用 Readme\_XXX.md 来支持不同的语言，例如 Readme\_en.md, Readme\_zh.md
2.  Gitee 官方博客 [blog.gitee.com](https://blog.gitee.com)
3.  你可以 [https://gitee.com/explore](https://gitee.com/explore) 这个地址来了解 Gitee 上的优秀开源项目
4.  [GVP](https://gitee.com/gvp) 全称是 Gitee 最有价值开源项目，是综合评定出的优秀开源项目
5.  Gitee 官方提供的使用手册 [https://gitee.com/help](https://gitee.com/help)
6.  Gitee 封面人物是一档用来展示 Gitee 会员风采的栏目 [https://gitee.com/gitee-stars/](https://gitee.com/gitee-stars/)
>>>>>>> f103e349340909e7dd7eb3a3cff5ccf4c2e04733
