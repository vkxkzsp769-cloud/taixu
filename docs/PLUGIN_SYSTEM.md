# TaiXu 宿主能力插件体系（Plugin System v2）

> 状态：接口与首个接管点已落地（`slot.floating.window`），SDK 打包工具链与接管中心 UI 待后续迭代。
> 代码位置：SPI 在 `core:common`，清单在 `core:model`，装载/授权/仲裁在 `tools`，装配与内置兜底在 `app`。

## 1. 这套体系解决什么

原有 `.txplugin`（schema v1）是**纯数据清单**模型：清单里不许出现可执行代码，宿主按工具 id
匹配自己写死的适配器，权限词表只有 `NETWORK / WORKSPACE_READ / WORKSPACE_WRITE / LOCAL_WEB`。
它安全，但插件**碰不到宿主内部**，更不可能改变软件自带功能。

schema v2 明确反转这一条：

1. **插件拥有宿主的一切权限** —— 插件代码由宿主进程内的 `DexClassLoader` 装载，
   运行在宿主 UID 下，拿到的是宿主 Application `Context`；宿主能做的系统调用它都能做。
2. **插件可以修改/顶掉软件自带的默认实现** —— 宿主在内置功能上预留**扩展点（槽位）**，
   插件注册同名槽位且获用户批准后即取代内置；内置实现以最低优先级常驻，永远可回退。
3. **宿主没有的权限可以通过插件申请** —— 见 §5 的三条通道（运行时权限 / 特殊访问页 / 伴生 APK）。

代价是风险面从「沙箱里的脚本」变成「宿主进程内的任意代码」，因此本体系把
**安装期同意、能力门禁、接管审计、崩溃自动降级**做成强制闸门（§6），缺一不可。

## 2. 分层与依赖

| 层 | 模块 | 内容 | 为什么放这里 |
|---|---|---|---|
| 清单数据 | `core:model` | `plugin/PluginManifest.kt` | 与 `ToolManifest` 同层；必须保持 JVM-only（不得 import `android.*`） |
| SPI 契约 | `core:common` | `plugin/`：`TaiXuPlugin` `PluginHost` `PluginSlot` `PluginCapability` `PluginRegistry` `PluginSafetyPolicy` `PluginGrantStore` | 约定插件已给**每个 feature 模块**注入 `:core:common`，因此 UI 侧接管点无需新增任何模块依赖边；同时它是 Android 库，可以出现 `Context` |
| 宿主实现 | `tools` | `plugin/`：`DexPluginLoader` `PluginInstaller` `DataStorePluginGrantStore` `PluginHostManager` `PluginArbitration` `PluginAndroidGates` `FloatingWindowDelegate` | 插件域本就归属 tools（`api(:core:common)` + `:core:datastore`） |
| 装配 | `app` | `plugin/PluginBootstrap.kt`：登记内置兜底 + 启动装载 | 只有 app 同时看得到 `feature:chat` 与 `tools` |

**零新增模块、零新增模块间依赖边**：`core:datastore` 只提供「插件 ID → 不透明 JSON」的门面
（`PluginStateRepository`），序列化在 `tools` 完成，避免反向依赖 `core:common`。

## 3. 清单 schema v2

```jsonc
{
  "schemaVersion": 2,
  "id": "overlay-pro",                 // [a-z0-9][a-z0-9-]{1,63}
  "name": "第三方可拖拽悬浮窗",
  "version": "1.0.0",
  "publisher": "someone",
  "runtime": "NATIVE_DEX",             // NATIVE_DEX | SANDBOX_TOOL | COMPANION_APK
  "entryClass": "com.example.OverlayPlugin",   // 实现 TaiXuPlugin，需无参构造
  "codeEntry": "plugin.jar",           // payload/ 下的 dex/jar/apk
  "codeSha256": "…64 位十六进制…",      // 带代码即强制校验
  "capabilities": ["overlay_window", "ui_slot_override"],
  "extensionPoints": ["slot.floating.window"],
  "overridePriority": 10,              // 内置为 Int.MIN_VALUE，任何插件都能高于它
  "companionPackage": null,            // COMPANION_APK 形态必填
  "minHostVersionCode": 0,
  "source": "LOCAL", "offlineOnly": true
}
```

校验规则集中在 `PluginSafetyPolicy.validateManifest()`（违规即拒绝安装，且不写入任何状态）：
schema 必须为 2、ID 正则、`runtime`/`source` 白名单、`NATIVE_DEX` 必须同时有 `entryClass`+`codeEntry`、
带代码必须有合法 SHA-256、能力与扩展点必须**全部**在宿主词表内、声明扩展点却没申请
`ui_slot_override` 直接拒、优先级不得占用内置保留值、`minHostVersionCode` 不得超过当前宿主。

## 4. 扩展点（槽位）

`PluginSlot<out T : PluginExtension>` 是类型化键；插件只能注册到**清单里声明过**的槽位，
`PluginHostFacade.register()` 会二次把关（未声明即抛异常）。

| 槽位 ID | 名称 | 独占 | 有内置 | 现状 |
|---|---|---|---|---|
| `slot.floating.window` | 悬浮窗 | ✅ | ✅ | **已接通**：`ChatTopBar` → `ChatFloatingWindow` → `FloatingWindowDelegate` |
| `slot.orb.bridge` | 表情球状态桥 | ✅ | ✅ | 词表已登记，待接第二个接管点 |
| `slot.notification.renderer` | 通知渲染器 | ✅ | ✅ | 同上 |
| `slot.agent.tool` | Agent 工具 | ❌（多播） | ❌ | 同上 |
| `slot.terminal.hook` | 终端输入钩子 | ❌（多播） | ❌ | 同上 |

需要 Compose 类型的界面槽位由装配层自行声明（`core:common` 不依赖 Compose），复用同一注册表。

**内置实现如何被顶掉而不写死 if/else**：`PluginBootstrap` 把内置悬浮窗以 `pluginId = "builtin"`、
`priority = Int.MIN_VALUE` 注册进同一槽位。仲裁只看「优先级 + 授权」，因此装一个获批插件就自然接管，
撤回授权后不改一行代码就回到内置。

## 5. 权限：三条申请通道

Android 的清单在安装期已冻结，**宿主无法动态新增 `<uses-permission>`**。所以「插件申请宿主没有的权限」
被拆成三条真实可行的路径，由 `PluginCapability.grantMode` 标注：

| 通道 | 适用 | 机制 |
|---|---|---|
| `HOST_RUNTIME_PERMISSION` | 宿主已声明的运行时权限（如通知） | 宿主门面 `requestCapability()` 代发；本迭代以设置页兜底，Activity 弹窗待接管中心 UI 接入 |
| `HOST_SPECIAL_ACCESS` | 悬浮窗 / 无障碍 / 使用情况 | `SettingsPermissionRequester` 跳系统设置页（`FLAG_ACTIVITY_NEW_TASK`，处处可用），`SystemCapabilityProbe` 只读探测真实状态 |
| `HOST_INTERNAL` | 沙箱执行、顶掉内置、提权桥等 | 不依赖 Android 权限，纯宿主门禁：用户批准即生效，可随时撤回 |
| `COMPANION_ONLY` | 宿主清单确实没有的权限 | 插件自带**伴生 APK**（独立包名与清单），由伴生 APK 申请并代为执行；宿主通过 `companionPackage` 检测其安装状态 |

能力状态是**双条件**：`用户已批准` ∧ `系统已授予`，见 `resolveGrantState()`。只满足前者不算 granted。

## 6. 安全闸门

1. **安装期同意分级**（`PluginSafetyPolicy.consentLevel`）：
   `NONE` / `GENERAL`（含 HIGH） / `EXPLICIT_FULL_PRIVILEGE`（含 CRITICAL，必须逐条勾选）。
   `PluginInstaller.approve()` 对 CRITICAL 能力做**硬校验**：未逐条勾选直接拒绝启用，杜绝「一键全选」。
2. **两段式启用**：安装只落库为 `enabled = false`，用户批准能力后才装载代码。
3. **四条件仲裁**（`isRegistrationApproved`，纯函数、单测覆盖）：
   已启用 ∧ 未被崩溃停用 ∧ 该槽位获批 ∧（顶掉内置时）已授予 `ui_slot_override`。
4. **崩溃自动降级**（`PluginSafetyPolicy.shouldAutoDisable`）：5 分钟窗口内 3 次异常即
   `autoDisabled` + `detach` + 回退内置 + 写审计，插件炸了不会拖垮宿主。
5. **接管审计**（`PluginAuditAction`）：安装/授权/接管获批/生效/回退/自动停用/装载失败全程留痕，
   存 `plugin_audit_log`（环形 200 条）。
6. **完整性**：带代码的插件必须有 SHA-256，解包时逐字节校验，失败即删文件并拒绝安装；
   zip 条目名含 `..` 直接拒（zip-slip）。

## 7. 写一个插件

```kotlin
class OverlayPlugin : TaiXuPlugin {
    override val id = "overlay-pro"

    override fun onAttach(host: PluginHost) {
        host.register(
            slot = PluginSlots.FLOATING_WINDOW,
            impl = object : FloatingWindowExtension {
                override val pluginId = "overlay-pro"
                override fun show(context: Context, h: PluginHost?) = /* 建自己的图层 */ true
                override fun hide() { /* 释放图层 */ }
                override val isShowing get() = /* 状态 */ false
            },
            priority = 10,
        )
    }

    override fun onDetach() { /* 必须幂等地释放一切资源 */ }
}
```

打包：编译成 `classes.jar`（或 dex），与 `manifest.json` 一起放进 `.txplugin` ZIP 的 `payload/` 下，
填好 `codeEntry` 与 `codeSha256`。宿主装载后，`onAttach` 拿到的 `PluginHost.context` 即宿主
Application Context——这就是「拥有宿主一切权限」的字面含义。

## 8. 测试与门禁

- `tools` 模块 JVM 单测：`PluginSafetyPolicyTest`（15 项，安装期闸门）、`PluginOverrideTest`
  （12 项，优先级/手选/冲突/四条件仲裁）。云端 `Test TaiXuDev Iteration` workflow 默认任务已含
  `:tools:testDebugUnitTest`。
- 架构合规：新增文件均在 `maxFileLines = 400` 之内，未新增模块依赖，未改 `settings.gradle.kts`。

## 9. 已知边界（下一迭代）

- 接管中心 UI（能力勾选、槽位开关、审计列表）尚未实现，目前只能经 `PluginInstaller` 编程调用。
- `HOST_RUNTIME_PERMISSION` 的 Activity 弹窗申请未接（现走设置页）。
- 伴生 APK 的 IPC 契约（AIDL）只定义了清单字段，未实现绑定与转发。
- 其余 4 个槽位待接；签名注册表（publisher 公钥验签）未接，当前依赖 SHA-256 完整性。
