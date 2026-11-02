# ADB / Shizuku 使用指南（vivo 原子随身听白名单场景）

本手册面向「把本应用（com.guga.music）接入 vivo 原子随身听（com.vivo.musicwidgetmix）协作通道」这一具体需求，
系统讲解 **ADB** 与 **Shizuku** 的安装、连接、激活，以及如何用它们把应用追加进系统的常驻音乐 App 白名单。
如果你只想快速搞定，直接跳到 [四、一键脚本](#四一键脚本-toolsvivo-whitelistsh)。

---

## 一、背景：为什么要 ADB / Shizuku

vivo 原子随身听判断某个音乐 App 是否「准入」协作通道时，会读取系统设置：

```
Settings.Secure["music_app_white_list"]   // 云控下发的 JSON 名单
```

- 普通 App（没有系统签名、没有 root）**没有权限写入** `Settings.Secure`。
- `adb shell` 和 Shizuku 都是以 **shell 身份（uid 2000）** 运行，**具备写入该系统设置的权限**。
- 因此：只要能拿到 shell 权限（adb 或 Shizuku），**不需要 root** 就能完成白名单写入。

> ⚠️ 关键约束：**只能「追加」，绝不能「覆盖」**。
> 一旦往 `music_app_white_list` 写了内容，widget APK 里硬编码的 61 个音乐 App（QQ音乐、网易云、酷狗、波点音乐……）
> 就会被屏蔽。如果只写自己一个包名，其它音乐 App 会从小部件里全部消失。
> 本仓库的脚本已处理这一约束（云控为空时先重建 61 个系统 App 再追加本应用）。

---

## 二、ADB 的安装与连接

ADB（Android Debug Bridge）是 Android SDK 的 `platform-tools` 组件，是一行命令行工具。

### 2.1 安装

- **方式 A（推荐，零环境变量）**：直接用本机已装 SDK 的 `platform-tools/adb`。
  本项目的构建脚本会探测 SDK 路径；你也可手动定位：
  - Windows 常见：`%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`
  - macOS/Linux：`~/Android/Sdk/platform-tools/adb`
- **方式 B**：单独下载 [SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools) 解压即可，无需安装、无需配置环境变量。
- **验证安装**：

  ```bash
  adb version
  # Android Debug Bridge version 1.0.41 ...
  ```

  Windows 如果提示「adb 不是内部或外部命令」，说明没进 PATH。两种办法：
  1. 把 `platform-tools` 目录加进系统 PATH；
  2. 调用时写全路径，或在脚本里用 `ADB=/绝对路径/adb` 指定（见 [四](#四一键脚本-toolsvivo-whitelistsh)）。

### 2.2 在手机上开启开发者选项与调试

1. 设置 → 关于手机 → 连续点击「版本号 / 软件版本」**7 次**，提示「已进入开发者模式」。
2. 返回 → 系统 / 更多设置 → **开发者选项**：
   - 打开 **USB 调试**；
   - （Android 11+）打开 **无线调试**（用于不插线的 adb / Shizuku）。

### 2.3 连接方式

#### 方式 A：USB 有线（最稳）

1. 用数据线连电脑，手机通知栏选「**文件传输 (MTP)**」或「仅充电」。
2. 首次连接会弹「是否允许 USB 调试」，勾选「一律允许」并确认。
3. 验证：

   ```bash
   adb devices
   # List of devices attached
   # 8AXXXXXXXX    device      <- 出现 device 即成功
   ```

#### 方式 B：无线调试（Android 11+，最方便）

适合不想插线、或像本机这样用 Shizuku 的场景。

1. 开发者选项 → **无线调试** → 打开。
2. 点「**无线调试**」条目进入详情，能看到「IP 地址和端口」，例如 `192.168.1.20:39877`。
3. **配对（只需一次）**：

   ```bash
   # 先配对（用「配对码」对应的端口，不是上面那个连接端口）
   adb pair 192.168.1.20:39877
   # 提示 Enter pairing code: 输入手机上显示的 6 位配对码
   ```

4. **连接（每次重连都可能需要）**：

   ```bash
   # 用「无线调试」主界面显示的「IP 地址和端口」连接
   adb connect 192.168.1.20:39877
   adb devices
   # 192.168.1.20:39877   device
   ```

> 注意：手机 Wi-Fi 断开 / 重启后，无线调试端口会变，需要重新 `adb connect`。
> 配对信息一般只要配对过一次就会保留，重连时直接 `adb connect` 即可。

---

## 三、Shizuku 的安装与激活

Shizuku 是一个开源工具（作者 RikkaW，[GitHub: RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku)），
它把「shell 权限」通过本地 socket 安全地提供给其它 App，**让你在手机上就能执行需要 shell 权限的命令**，不必每次连电脑。

为什么在本场景有用：本仓库的 `tools/vivo-whitelist.sh` 本质就是执行
`adb shell settings put secure music_app_white_list ...`。如果你不想开电脑连 adb，
可以在手机上用 Shizuku + 终端 App（如 Termux）完成等价操作（见 [五](#五手动方式不用脚本)）。

### 3.1 安装

- 酷安 / Google Play / GitHub Releases 搜索安装 **Shizuku**。
- 需要系统 **Android 6.0+**；推荐 Android 11+ 走「无线调试」激活，最省事。

### 3.2 激活方式

#### 方式 A：通过无线调试（Android 11+，推荐）

1. 手机：开发者选项 → **无线调试** → 打开。
2. 打开 Shizuku → 选「**通过无线调试启动**」→ 按引导点击。
3. Shizuku 会自动拉起系统配对界面 / 读取无线调试信息，完成后 Shizuku 状态变为「**正在运行**」。
4. 之后需要用到 shell 权限的 App（如终端、文件管理器）在首次调用时会向 Shizuku 请求授权，点允许即可。

#### 方式 B：通过 adb 命令激活（不需要 root）

Shizuku 的「通过无线调试启动」失败时，可用电脑 adb 手动拉起。Shizuku 应用内「通过 adb 启动」页面会给出一行完整命令，形如：

```bash
adb shell shizuku -c "...长串启动参数..."
```

把它整行复制到电脑终端执行即可。本质是用 adb 帮 Shizuku 起一个具备 shell 权限的常驻进程。

#### 方式 C：通过 root（已 root 设备）

已 root 的手机，Shizuku 直接点「启动」授权即可，无需 adb / 无线调试。

### 3.3 验证 Shizuku 可用

打开 Shizuku，确认状态为「正在运行」。在终端 App（需已向 Shizuku 申请权限）里执行：

```bash
# 能查到 uid 2000 的 shell，说明 Shizuku 通道正常
whoami        # 输出 shell
id            # uid=2000(shell) ...
```

---

## 四、一键脚本（tools/vivo-whitelist.sh）

仓库自带脚本，自动完成「读取 → 重建或保留系统名单 → 追加本应用 → 重启小部件 → 验证」。

### 4.1 前置

- `adb` 在 PATH，或设置 `ADB=/路径/adb`；
- 已 `adb connect` 连上手机（见 [2.3](#23-连接方式)）；
- `python` / `python3` 可用（脚本用它解析/拼装 JSON 名单）。

> Windows 用户：在 **Git Bash** 里运行（仓库根目录执行）：
> ```bash
> bash tools/vivo-whitelist.sh
> ```
> 如果 `adb` 不在 PATH，用 `ADB=/c/Users/你/.../platform-tools/adb.exe bash tools/vivo-whitelist.sh`。

### 4.2 用法

```bash
# 1) 追加本应用（com.guga.music）到白名单，并重启小部件
bash tools/vivo-whitelist.sh

# 2) 只查看当前生效名单，不改写
bash tools/vivo-whitelist.sh --status

# 3) 还原到脚本写入前的状态（并清掉派生缓存，回到系统默认名单）
bash tools/vivo-whitelist.sh --restore

# 4) 给别的包名用同样的机制（例如你想帮另一个 App 加进去）
PKG=com.example.app bash tools/vivo-whitelist.sh
```

### 4.3 脚本做了什么（透明说明）

1. 备份当前 `music_app_white_list` 到 `tools/.vivo-whitelist.bak`（已被 `.gitignore` 忽略）。
2. 读取现有名单：
   - **非空** → 原样保留，仅把本应用追加到末尾；
   - **为空** → 用 `tools/vivo-system-apps.json` 里的 61 个系统 App 完整重建，再追加本应用。
3. `adb shell settings put secure music_app_white_list '<新JSON>'`。
4. `am force-stop com.vivo.musicwidgetmix` 重启小部件（它只在启动时读一次名单）。
5. 检查 `resident_music_app_white_list` 生效名单里是否还含 QQ音乐 / 网易云 / 波点音乐 / 本应用，缺了立刻提示 `--restore`。

### 4.4 跑完之后

```
用Apollo放一首歌 → 打开原子随身听 → 设置 → 播放诊断 看协作状态
```

也可在电脑侧验证服务是否被小部件发现：

```bash
adb shell dumpsys activity services com.guga.music | grep VivoMusicBrowserService
```

---

## 五、手动方式（不用脚本）

适合没有电脑、只用 Shizuku + Termux 的场景，或想理解底层原理。

### 5.1 查看当前名单

```bash
# adb
adb shell settings get secure music_app_white_list

# Shizuku + Termux（等价于 shell）
settings get secure music_app_white_list
```

### 5.2 构造「系统 61 个 + 本应用」名单

云控为空时，必须先把系统 61 个 App 补全，否则其它音乐 App 会失效。
名单结构是一个 JSON 数组，每项形如：

```json
{
  "packageName": "com.tencent.qqmusic",
  "appName": "com.tencent.qqmusic",
  "colorType": "",
  "dataStatus": 1,
  "isCooperate": 1,
  "showLockScreen": 1
}
```

- `isCooperate=1` 表示走协作控制器（深度协作通道）；
- 本应用（com.guga.music）必须 `isCooperate=1` 且 `showLockScreen=1`；
- 完整的 61 个系统 App 包名见 `tools/vivo-system-apps.json` 的 `main` 字段。

最稳妥的办法：直接用仓库脚本（它内置了这份名单和拼接逻辑）。纯手搓容易漏 App。

### 5.3 写入并重启小部件

```bash
# adb：把构造好的 new.json 写入
adb shell settings put secure music_app_white_list "$(cat new.json)"

# 重启小部件让它重新读名单
adb shell am force-stop com.vivo.musicwidgetmix
adb shell am start -n com.vivo.musicwidgetmix/com.vivo.musicwidgetmix.activity.FlipActivity

# Shizuku + Termux 等价写法
settings put secure music_app_white_list "$(cat new.json)"
am force-stop com.vivo.musicwidgetmix
```

> ⚠️ 用 Shizuku 时，命令直接以 shell 身份运行，不需要 `adb shell` 前缀。

### 5.4 验证

```bash
# 1) 小部件实际生效名单
settings get secure resident_music_app_white_list
# 应能看到 "com.guga.music" 以及 QQ音乐/网易云/波点 等

# 2) 本应用服务被发现
dumpsys activity services com.guga.music | grep VivoMusicBrowserService

# 3) 系统里记录的音源
settings get secure music_app_white_list
```

---

## 六、还原与排错

### 6.1 还原

- 用脚本：`bash tools/vivo-whitelist.sh --restore`（还原备份 + 清派生缓存）。
- 手动彻底回退（回到系统默认，即不写云控名单）：

  ```bash
  adb shell settings delete secure music_app_white_list
  adb shell settings delete secure resident_music_app_white_list
  adb shell settings delete secure lock_music_app_white_list
  adb shell am force-stop com.vivo.musicwidgetmix
  ```

### 6.2 常见问题

| 现象 | 原因 / 处理 |
| --- | --- |
| `adb devices` 看不到设备 | USB 线只充电模式、未授权弹窗、驱动缺失。换线/换模式，重看授权弹窗。 |
| 无线调试 `adb connect` 失败 | 端口是临时随机的，按手机「无线调试」详情页当前端口重连；手机换了 Wi-Fi/重启要重新连。 |
| 写入后其它音乐 App 消失 | 你用了「覆盖式」写入。立刻 `--restore` 或 `settings delete`，然后用脚本「追加」。 |
| 小部件里看不到本应用 | 写完必须 `am force-stop` 重启小部件；确认 `VivoMusicBrowserService` 已随 APK 安装（见 4.4 验证命令）。 |
| 固件升级后又失效 | vivo 云控会覆盖 `music_app_white_list`。升级后重跑一次脚本即可。 |
| Shizuku 显示未运行 | 重新走「通过无线调试启动」；或 adb 手动拉起（见 3.2 方式 B）。 |

---

## 七、权限与安全小结

- **不需要 root**：本方案只用 shell（uid 2000）权限写 `Settings.Secure`，adb 与 Shizuku 都能提供。
- **暴露面极小**：写入的只是「哪些音乐 App 可被小部件当作音源」的名单，不涉及文件、账号、其它系统设置。
- **可逆**：随时 `settings delete` 或脚本 `--restore` 回到系统默认。
- **云控会覆盖**：这是 vivo 系统行为，非本仓库缺陷；重跑脚本即可恢复。
