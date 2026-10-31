# ShRunner

在 Android 上直接导入、编辑并运行 `.sh` 脚本，实时查看输出。自带执行引擎，不依赖 Termux。

## 当前状态

Pre-release（自动构建）：每次推送到 `main`、推送 `v*` 标签或手动触发工作流，都会构建 release APK 并发布为 GitHub Pre-release。

## 功能

- **脚本管理**：导入本地 `.sh`、新建、重命名、删除、收藏、记录上次运行时间与退出码
- **执行引擎**：`/system/bin/sh` 直接执行，支持参数、工作目录、自定义环境变量、超时终止、手动停止；可选 root（`su -c`）模式
- **实时输出**：等宽字体终端视图、ANSI 颜色解析、自动滚屏、复制输出
- **编辑器**：Shell 关键字/注释/字符串/变量高亮，保存即写回脚本文件
- **安全**：执行前扫描 `rm -rf`、`dd`、`mount`、`su` 等高风险命令并二次确认
- **快捷方式**：把常用脚本固定到桌面，一键运行
- **前台服务保活**：脚本运行期间显示常驻通知，避免后台被系统回收

## 技术栈

| 组件 | 版本 |
| --- | --- |
| Android Gradle Plugin | 9.4.0 |
| Gradle | 9.6.0（JDK 17 运行） |
| Kotlin | 2.2.21 |
| Compose BOM | 2026.09.00 |
| compileSdk / targetSdk | 37（Android 17） |
| minSdk | 26（Android 8.0） |

## CI 依赖版本

工作流使用的 action 均固定大版本并跟进 Node 运行时（Node 20 已弃用）：

| Action | 版本 |
| --- | --- |
| `actions/checkout` | v7 |
| `actions/setup-java` | v6（Temurin JDK 17） |
| `gradle/actions/setup-gradle` | v6.4.1 |
| `softprops/action-gh-release` | v3.0.3（node24） |

预发行策略：每次成功构建后自动删除历史预发行（含对应 git tag），**仓库里始终只保留最新一个**。

Runner 固定为 `ubuntu-24.04`：`ubuntu-latest` 自 2026-10-19 起逐步迁移到 Ubuntu 26.04，锁定版本可避免迁移警告与系统库变化影响构建。

## 编译

```bash
# 需要 JDK 17
./gradlew :app:assembleRelease
```

仓库已带 Gradle Wrapper（9.6.0），直接用 `./gradlew` 即可；也可使用本地 Gradle 9.6.0：`gradle :app:assembleRelease`。

## 签名

签名信息只放在**本地** `local.properties`（已在 `.gitignore` 中，不会入库）：

```properties
KEYSTORE_FILE=星觅海.jks
KEYSTORE_PASSWORD=你的库密码
KEY_ALIAS=星觅海
KEY_PASSWORD=你的别名密码
```

把 keystore 放到项目根目录。配好之后 debug 与 release 使用同一签名，反复安装不会因签名不一致而失败；未配置时打未签名包。

CI 侧通过仓库 Secrets 注入等价的四个值：`KEYSTORE_BASE64`（keystore 的 base64）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`，工作流会在构建前解码还原。

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `INTERNET` | 脚本可联网（curl/wget/ping 等） |
| `ACCESS_NETWORK_STATE` | 执行前判断网络可用性 |
| `ACCESS_LOCAL_NETWORK` | Android 17（API 37）起默认禁止本地网络访问，脚本访问局域网需此权限 |
| `USE_LOOPBACK_INTERFACE` | Android 17（API 37）起访问 `127.0.0.1` / `::1` 需要此安装时权限 |
| `FOREGROUND_SERVICE`（`specialUse`） | 脚本运行期间保活并显示通知 |
| `POST_NOTIFICATIONS` | 显示运行状态通知 |

## Android 17（targetSdk 37）适配要点

1. **大屏自适应不可退出**：`screenOrientation`、`resizeableActivity`、宽高比限制在 `sw >= 600dp` 设备上被系统忽略，UI 使用自适应布局，不锁定方向。
2. **配置变更默认不重建**：`CONFIG_KEYBOARD`、`CONFIG_UI_MODE`、`CONFIG_COLOR_MODE` 等不再默认重启 Activity，已在 Manifest 中显式声明 `configChanges` 并用 Compose 状态驱动 UI。
3. **本地网络与环回受限**：已声明 `ACCESS_LOCAL_NETWORK` 与 `USE_LOOPBACK_INTERFACE`。
4. **无锁 MessageQueue、static final 不可反射修改、Parcel 校验增强**：代码不使用反射修改平台字段。

## 排错（常见报错）

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 退出码 127 | 命令或解释器不存在 | 检查脚本首行 shebang（写 `#!/system/bin/sh`），路径是否存在 |
| 退出码 126 | 没有执行权限或被 SELinux 拦截 | 需要 root 才能执行的命令请打开 root 开关 |
| `gzip: not gzip` / `Cannot decompress` | 设备上的 `su` 是占位或损坏的一键 root 实现 | 应用启动 root 模式时会先跑 `su ... id` 探测，探测不到 uid=0 会自动退回非 root 并在输出里提示 |
| 中文名脚本执行异常 | su 对非 ASCII 参数转码不一致 | 执行前会暂存为 `.stage/<ascii>.sh`，输出里会打印「已暂存到」 |
| 脚本明明存在却 not found | Windows 换行符 `\r\n` | 暂存时统一转成 LF |

每次运行都会在输出里打印一行 `[ShRunner] 实际命令：...`，把它复制出来用 `adb shell` 复现即可快速定位。

## 已知限制

- 脚本存放在应用私有目录（`filesDir/scripts`），Android 10+ 分区存储限制下其他应用无法直接读取，需通过本应用导入。
- 非 root 模式受 Android 应用沙箱与 SELinux 限制，部分命令（如访问 `/data/data/其他包`、`iptables`）会失败。
- 长时间后台执行依赖前台服务，Android 12+ 不允许从后台启动前台服务，请在应用前台点击运行。
- root 模式依赖设备上可用的 `su`。

## 目录结构

```
app/src/main/kotlin/com/xingmihai/shrunner
├── MainActivity.kt          入口，edge-to-edge
├── engine/                  ShellEngine（执行）/ ShellEnv（探测）/ RunnerService（保活通知）
├── data/                    ScriptRepository：脚本文件与元数据
├── ui/                      ViewModel + 三个页面（脚本/编辑/输出）
└── util/                    ANSI 解析、语法高亮、危险命令扫描、快捷方式
```

## 许可

MIT
