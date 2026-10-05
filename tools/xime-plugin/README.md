# xipm — Xime 插件工具链

Xime 输入法插件开发 CLI（Rust 实现）。负责 TypeScript 编译、xipk 打包、插件骨架生成与清单校验，
以及**免真机单测**（`xipm test`，内嵌 QuickJS + mock host）与**真机调试**（`xipm dev`/`xipm install`/`xipm logs`，adb）。

- **插件源码**：TypeScript（`main.ts` + 可选 `libs/*.ts`，相对 import 自动内联）
- **编译产物**：IIFE 单文件 `main.js`（无顶层 import/export，QuickJS 脚本模式直接执行）
- **插件包**：`<name>-<version>.xipk`（zip，Deflate 级别 9；宿主安装解压到 `files/plugins/<id>/`）
- **编译产物不进入仓库**：由 `xipm build` 输出到 `build/plugin-js/`（gitignore）

## 安装 / 构建 CLI

```bash
# 方式一：开发运行（无需安装）
cd tools/xime-plugin
cargo run -- --help

# 方式二：构建二进制（推荐，命令名 xipm）
cargo build --release
./target/release/xipm --help
# 提示：若设置了 CARGO_TARGET_DIR，二进制位于 $CARGO_TARGET_DIR/release/xipm

# 方式三：下载 Release 里的预编译二进制（每个平台一个包，文件名带 CLI 版本号）
#   xipm-<version>-<target>.tar.gz（Windows 为 .zip），target 如 x86_64-unknown-linux-gnu
xipm --version   # → xipm 0.1.1（版本号来自 tools/xime-plugin/Cargo.toml）
```

CLI 版本号独立于 App 版本号（在 `tools/xime-plugin/Cargo.toml` 的 `[package] version`），
发版时由 release workflow 读出来写进产物文件名。

## 最常用命令

```bash
# 仓库根一条命令：编译全部插件 + 打包 xipk（输出 build/plugin-release/*.xipk）
xipm pack

# 等价：编译全部插件（产物 build/plugin-js/，供测试/开发），不打包
xipm build

# 运行插件测试（内嵌 QuickJS，无需真机/Node；插件目录零参数）
xipm test

# 真机热调试（watch → 编译打包 → adb 推送 → 广播安装/重载 + 日志跟随）
xipm dev plugins/my-plugin

# 多设备/无线调试：-s 指定设备序列号（等价 adb -s，全局参数，子命令前后都可写）
xipm -s 192.168.1.10:5555 dev plugins/my-plugin

# 真机安装：把 xipk（或插件目录，先编译打包）装到手机
xipm install build/plugin-release/my-plugin-0.1.0.xipk

# 真机插件日志（实时跟随；--history 拉取历史错误）
xipm logs plugins/my-plugin --history
```

（零参数时自动识别：当前目录不是插件但存在 `plugins/` → 批量模式；
在插件目录内零参数运行 → 单插件模式）

## 快速开始（3 分钟）

```bash
# 1. 创建插件骨架（main.ts + manifest.json + xime-plugin.d.ts + tsconfig.json + resources/）
cd tools/xime-plugin
cargo run -- init my-plugin --type tool --parent /tmp/demo

# 2. 编辑 /tmp/demo/my-plugin/main.ts（契约见骨架内 xime-plugin.d.ts）

# 3. 编译（多文件 import 内联 → 单文件 main.js）
cargo run -- build /tmp/demo/my-plugin --out /tmp/demo/out

# 4. 写测试（骨架自带 main.test.ts 示例）并运行（
cargo run -- test /tmp/demo/my-plugin

# 5. 校验清单
cargo run -- check /tmp/demo/my-plugin

# 6. 打包 xipk（内部会先编译一遍）
cargo run -- pack /tmp/demo/my-plugin --out /tmp/demo/out --release-dir /tmp/demo/release
# → /tmp/demo/release/my-plugin-0.1.0.xipk
```

## 命令参考

### 全局参数

| 参数 | 说明 | 默认 |
|---|---|---|
| `-s, --device <SERIAL>` | 指定设备序列号（等价 `adb -s`；`dev`/`install`/`logs` 使用） | 自动采用唯一在线设备 |

```bash
xipm -s <serial> dev          # 与 adb -s 一致（写在子命令前）
xipm dev -s <serial>          # 等价写法（写在子命令后）
xipm dev --device <serial>    # 长参数同样可用
```

设备序列号会带进后续**所有** adb 调用（`-s <serial> shell` / `exec-out` / `push` …），
不再依赖 adb 的"默认设备"解析；未指定时从 `adb devices` 自动采用唯一的在线设备。

### `xipm build [DIR]`

编译插件：`main.ts`（多文件 import 内联）→ IIFE 单文件 `main.js`，并复制 `manifest.json` 与 `resources/`。

```bash
xipm build <插件目录> --out <输出根>          # 单插件 → <输出根>/<插件目录名>/
xipm build --all --plugins-dir plugins --out build/plugin-js   # 批量
```

| 参数 | 说明 | 默认 |
|---|---|---|
| `[DIR]` | 插件目录（含 main.ts + manifest.json） | 当前目录 |
| `--all` | 批量构建 `--plugins-dir` 下所有插件 | - |
| `--plugins-dir` | 批量模式插件根目录 | `plugins` |
| `--out` | 输出根目录（产物位于 `<out>/<plugin-name>/`） | `build/plugin-js` |

### `xipm pack [DIR]`

**最常用：在仓库根直接运行 `xipm pack`（零参数）** —— 自动批量打包 `plugins/` 下全部插件到
`build/plugin-release/*.xipk`。


编译 + 打包为 `<name>-<version>.xipk`（version 取自 manifest.json）。

```bash
xipm pack --all --plugins-dir plugins --out build/plugin-js --release-dir build/plugin-release
xipm pack <插件目录> --with-assets            # 单插件：打包并拷贝到 app assets
```

| 参数 | 说明 | 默认 |
|---|---|---|
| `--release-dir` | xipk 输出目录 | `build/plugin-release` |
| `--no-minify` | 不压缩产物（**pack 默认压缩**：compress + 局部变量 mangle，体积约 -40%） | 默认压缩 |
| `--with-assets` | 打包后拷贝到 app 内置资源目录（单插件模式用） | - |
| `--assets-dir` | app 内置资源目录 | `app/src/main/assets/plugins` |

### `xipm check [DIR]`

校验 `manifest.json`（宽松 JSON：支持 `//` 注释与尾逗号）字段与格式：id 命名空间、entry、version、类型 × 能力一致性、平台声明（`platforms` 空数组报错，未知平台标识提示）、入口源码存在性。

```bash
xipm check --all --plugins-dir plugins
```

### `xipm test [DIR]`

运行插件测试：`main.ts` 编译产物 + `main.test.ts` 在同一 QuickJS 引擎（与真机同引擎）
加载，注入符合 v3 契约的 mock host，逐个执行用例。

```bash
xipm test plugins/my-plugin          # 单插件（插件目录内可零参数）
xipm test                            # 仓库根零参数：批量（无测试文件的插件跳过）
xipm test plugins/my-plugin --smoke  # 无 main.test.ts 时仅做加载冒烟
```

| 参数 | 说明 | 默认 |
|---|---|---|
| `--all` / `--plugins-dir` | 批量模式 | 仓库根自动批量 |
| `--out` | 编译产物根目录（测试基于产物运行） | `build/plugin-js` |
| `--smoke` | 无测试文件时执行"加载 + 插件定义存在"冒烟 | 跳过 |

测试 API（`main.test.ts`，全局注入、无需 import）：

```ts
test('名称', async () => {                  // 用例可 async
  assert.equal(actual, expected, '说明');   // ok / equal / deepEqual / throws / rejects
  __ximeMock.addHttpResponse('GET', url, { status: 200, text: 'pong' });
  const resp = await host.http.request('GET', url, {});
  assert.equal(resp.text, 'pong');
  assert.equal(__ximeMock.httpRequests.length, 1);   // 请求记录
});

test('未 stub 的网络请求被拒绝', async () => {
  await assert.rejects(host.http.request('GET', otherUrl, {}));
});
```

- **确定性**：网络/WS/SSE 必须显式 stub（`__ximeMock.addHttpResponse/addWs/addSse`），
  未注册即 reject `E_NETWORK`——绝不真实联网
- **可断言**：`httpRequests` / `sentWs` / `asrEvents` / `quickSend` / `clipboard` 调用记录
- **可控时钟**：`__ximeMock.setClock(epochSeconds)` 固定 `host.crypto.utcTime/epochSeconds`
- **日志透出**：插件 `console.log/error` 前缀化输出到终端；失败用例显示断言信息与堆栈
- **实现**：CLI 内嵌 QuickJS（rquickjs）+ mock host（async 服务 reject `XimeError`、
  字节为 `Uint8Array`、资源指向插件 `resources/` 真实文件）

### `xipm dev [DIR]`

真机热调试循环：watch 插件源码 → 编译 + 打包 → `adb` 推送 → 触发宿主
**覆盖安装 + 重载** → 同时跟随设备日志。推送/回执通道按宿主类型自动探测：

| 通道 | 判定 | 推送 | 回执 | 错误落盘跟随 |
|---|---|---|---|---|
| debug（默认） | `run-as` 可用 | `run-as` 管道写入内部 `files/xipm-dev/` | jsonl 落盘（不依赖 logcat） | ✓ |
| release | `run-as` 不可用 | `adb push` 到 `/data/local/tmp/xipm-dev/`（装后宿主删除） | logcat dump 解析 `XipmDev` | ✗ |

```bash
xipm dev plugins/my-plugin
xipm dev -s <serial> --no-logs                # 多设备/无线调试；只热更新不跟日志
```

| 参数 | 说明 | 默认 |
|---|---|---|
| `--package` | 应用包名 | `com.kingzcheung.xime` |
| `--adb` | adb 路径（缺省 `$ADB` / `$ANDROID_HOME/platform-tools/adb`（Windows 自动补 `.exe`）/ PATH） | - |
| `-s, --device` | 全局参数：`adb -s` 设备序列号（无线调试：先 `adb pair`/`connect`） | 自动采用唯一在线设备 |
| `--no-logs` | 不跟随设备日志 | 跟随 |
| `--out` | 构建产物根目录（xipk 暂存 `<out>/dist`） | `build/plugin-dev` |

- **宿主要求**：热安装组件 `DevPluginInstallActivity` 在 debug/release 构建**均包含**，
  但受"插件开发模式"开关门禁——**release 包**需在宿主 `设置 → 关于 → 连点设备信息
  7 次（1.5s 内）` 解锁并开启该开关；开关关闭时热安装入口秒退，adb 无法注入插件
- **触发方式**：`am start` 拉起宿主内无界面 Activity（透明主题、完成后立即结束、
  `exported=true`——Android 15 起 shell 已无法启动非导出组件，故需导出；普通应用
  虽可拉起，但开发模式关闭时入口秒退、无注入面）——相比广播不受 Android 后台执行
  限制（后台应用收自定义广播会被系统丢弃："Background execution not allowed"），
  宿主未使用键盘时也能可靠热更新
- **不会被内置覆盖**：宿主启动会同步内置 assets 插件，但采用"仅内置版本更高才覆盖"
  （版本守卫）——开发中的热更新版本不会被回滚；内置发版 bump 版本后仍会升级
- **流程回执**：安装/重载结果——
  1. debug 通道：CLI 经 `run-as` 读取 `files/logs/xipm-dev-result.jsonl`（默认，不依赖
     logcat，显示 `✓ 热安装成功` / `✗ 热安装失败：<原因>`；10s 无回执提示检查宿主）
  2. release 通道：CLI 轮询 `logcat -d -s XipmDev` 解析 `INSTALL_OK` / `INSTALL_FAIL`；
     错误落盘跟随与 `xipm logs --history` 在此通道不可用

### `xipm install [XIPK|DIR]`

把插件热安装到手机（与 `xipm dev` 同一热安装通道，但不 watch、不跟日志）：

```bash
xipm install build/plugin-release/volc-asr-3.0.1.xipk   # 安装现成 xipk
xipm install plugins/volc-asr                          # 插件目录：先编译打包再安装
xipm -s <serial> install plugins/volc-asr              # 多设备：全局 -s 指定设备
```

| 参数 | 说明 | 默认 |
|---|---|---|
| `--package` | 应用包名 | `com.kingzcheung.xime` |
| `--adb` | adb 路径（同 `xipm dev`） | - |

- 目标缺省为当前目录；是**插件目录**时按 `xipm pack` 的默认布局先编译打包
  （`build/plugin-js/` → `build/plugin-release/<name>-<version>.xipk`）再安装，
  是 `.xipk` 文件时直接安装
- 通道自动探测（同 `xipm dev`）：debug 包走 `run-as` 内部目录 + jsonl 回执；
  release 包走 `/data/local/tmp` + logcat 回执，且需在宿主开启"插件开发模式"
- 安装后等待设备回执（最长 10s）：`✓ 热安装成功：<插件 id> <信息>` /
  `✗ 热安装失败：<原因>`；未收到回执时给出排查提示（同 `xipm dev`）

### `xipm logs [DIR]`

真机插件日志回显（读 `manifest.id` 过滤）：

```bash
xipm logs plugins/my-plugin              # 实时跟随（Ctrl-C 退出）
xipm logs plugins/my-plugin --history    # 历史错误（宿主 errors.jsonl，run-as）
xipm logs plugins/my-plugin --history --lines 100 --json
xipm logs plugins/my-plugin -s <serial>  # 多设备：指定设备（同全局 -s）
```

- **实时**：三通道（前两者不依赖 logcat，ROM 后台日志限流时依然可靠）——
  1. **插件 console 落盘**（`dev-console.jsonl`，debug 宿主写入）：`console.log/error`
     实时回显 `HH:MM:SS [log] ...`（error 红色）；启动时已有历史静默、之后只显示新增
  2. **插件错误落盘**（`errors.jsonl`）：启动先提示最近 1 条，之后只输出新增；
     内容含分类 + 操作 + `main.js:行号` 堆栈
  3. logcat 跟随（宿主行为日志：加载/热更新/事件等含插件 id 的行）
- **历史**：`adb exec-out run-as <package> cat files/logs/plugins/errors.jsonl`
  解析展示（时间/分类/操作/消息/堆栈），`--json` 输出 JSON 行供 IDE/脚本集成
- **注意**：`--history` 需要 debug 包（`run-as` 限制）；release 包请使用实时日志

### `xipm init <NAME>`

生成插件骨架：

```
<parent>/<name>/
  main.ts            入口源码（含最小示例）
  main.test.ts       测试骨架（xipm test 开箱可跑）
  manifest.json      清单模板（含中文注释）
  xime-plugin.d.ts   SDK 类型定义（host API + 插件契约 + 环境 API）
  tsconfig.json      类型检查配置（ES2020 / strict）
  resources/         资源目录（宿主渲染图片等）
  .gitignore         dist/
```

```bash
xipm init my-plugin --type tool --parent .
# 类型可选：tool / emoji / speech / clipboard_sync / backup
```

## 插件结构

```
plugins/my-plugin/
  main.ts         源码（definePlugin + export default）
  main.test.ts    测试（xipm test；不进 xipk 产物）
  libs/*.ts       可选拆分（相对 import，编译时内联进 main.js）
  manifest.json   清单（宿主解析；宽松 JSON 支持注释与尾逗号）
  resources/      资源文件（图片由宿主渲染，插件只拿路径）
```

`manifest.json` 关键字段：

```jsonc
{
  "id": "com.example.my_plugin",   // 反域名命名空间（字母/数字/下划线/连字符/点号，≤64）
  "name": "我的插件",
  "version": "0.1.0",
  "type": "tool",                   // tool / emoji / speech / clipboard_sync / backup
  "entry": "main.js",               // 固定 main.js（编译产物）
  "platforms": ["android"],         // 目标平台（缺省视为 ["android"]；已知 android/ios/windows/macos/linux）
  "minHostVersion": "2.8.0",
  // "network": { "hosts": ["api.example.com"], "allowCustomHosts": false },
  // "capabilities": { "tool": { "display": "passive" } }
}
```

详细 schema：`docs/sdk/manifest.schema.json`。

## 产物压缩与"混淆"说明

- **默认策略**：`pack`（分发）**默认压缩**；`build`（测试/开发）默认不压缩（便于调试与错误定位）
  - `xipm build --minify`：手动压缩编译产物；`xipm pack --no-minify`：关闭打包压缩
  - 实测 ai-reply 6633→3935B（-41%）、volc-asr 7474→3878B（-48%）；压缩产物已在宿主测试全量验证
  - 压缩 = rolldown 的 compress + 局部变量 mangle（安全；属性名不混淆）
- **属性名混淆不可用**：宿主按 `plugin` 扩展点方法名（panel.state 等）、`host` API 属性名、
  事件/协议字段名访问，属性重命名会破坏契约（保留名单易漏，风险大于收益）
- 激进混淆（字符串加密/控制流平坦化，如 javascript-obfuscator）需引入 Node 工具链，且体积/性能恶化，
  当前不支持；如防逆向是硬需求，更正确的方向是 QuickJS 字节码加载（宿主改造，未实现）

## 插件入口范式（TS 模块 + definePlugin）

插件主文件是**标准 TypeScript 模块**：用 `definePlugin` 定义扩展点，`export default` 导出。

```ts
// plugins/my-plugin/main.ts
import { helper } from './libs/helper';   // 多文件拆分（构建时内联）

interface LocalState { /* 类型定义放模块顶层 */ }

const plugin = definePlugin({
  onLoad(): void {
    host.log('loaded');
  },

  // 下行事件（manifest capabilities.events 声明后投递；槽名 = on + 事件名 PascalCase）
  events: {
    onTextCommitted(e) {
      host.log(String(e.sessionTotalChars));   // payload 类型自动推断（camelCase）
    },
  },

  // 工具面板扩展点（manifest type=tool；宿主渲染 ui 节点树）
  panel: {
    async state(input): Promise<XimePanelState> {
      return { items: [], ui: [], loading: false };
    },
    async onAction(input): Promise<void> {
      const resp = await host.http.request('POST', url, headers, body);   // async 服务
    },
  },
});

export default plugin;
```

- **构建产物**：`var plugin = (function () { ... })();`——QuickJS 脚本模式下即 `globalThis.plugin`（宿主契约不变）
- **不要手写 IIFE**：作用域隔离由 bundler 完成
- **SDK 类型无需 import**：`definePlugin` / `host` / `XimeError` / `Xime*` 等均为全局声明
  （`xime-plugin.d.ts` 由 tsconfig `include` 引入，init 骨架已配好）；
  `host` 在运行时即宿主注入的全局对象（同 Node 的 `process`、浏览器的 `window`）
- **契约校验**：`definePlugin<T extends XimePluginSpec>` 对扩展点名与方法签名做编译期校验（拼错直接报错）
- **TS 范式（async/await）**：host 网络/IO 服务为 async（`await host.http.request(...)`），
  失败 throw `XimeError`（`code` + `message`；无 `lastError()`）；纯计算 API 同步返回；
  扩展点按需 async（`panel.state/onAction`、`speech.*`、`clipboardSync.*`、`backup.*`、`onLoad/onUnload`；
  `transform.candidates` 必须同步）

## TypeScript 开发

- **SDK 类型**：`tools/xime-plugin/templates/xime-plugin.d.ts`（`init` 生成时释放到插件目录）
  - `host`：`log` / `config` / `resource` / `bin` / `zlib` / `crypto` / `http` / `ws` / `asr` / `quickSend` / `clipboard` / `uuid`
  - `XimePluginSpec`：扩展点 `panel` / `emoji` / `speech` / `clipboardSync` / `backup` /
    `transform` / `settings` / `events` / `ws` / `sse`
  - `XimeError`：`code` / `message`（async 服务失败的抛出形态）
- **类型检查**（可选，`xipm` 编译只做类型剥离不做检查）：

```bash
npx -p typescript tsc -p tsconfig.json --noEmit             # 仓库内全部插件（根配置）
npx -p typescript tsc -p <插件目录>/tsconfig.json --noEmit   # 单插件（插件目录也有就近配置）
```

- **语言基线**：ES2020（QuickJS 原生支持；class fields / async / 可选链 / 空值合并 / BigInt / Set / Map / TypedArray 均可）
- **宿主补齐的环境 API**：`console`（转发 host.log）、`TextEncoder` / `TextDecoder`、`atob` / `btoa`
- **不可用的 API**：`setTimeout` / `setInterval`、`URL` / `URLSearchParams`、`fetch`、`Intl` —— 网络与 IO 一律走 `host` 白名单
- **JSON**：用原生 `JSON.parse` / `JSON.stringify`（注意 `JSON.parse` 对非法输入**抛异常**，需 `try/catch`）
- **字节**：一律 `Uint8Array`；注意 `subarray()` 产生的子视图跨宿主桥会变 null，需 `new Uint8Array(view)` 复制

## 仓库内工作流（Xime 主仓库开发）

```bash
# 构建全部插件（测试前置：插件测试从 build/plugin-js 加载产物）
cd tools/xime-plugin && cargo run -- build --all --plugins-dir ../../plugins --out ../../build/plugin-js

# 打包全部 xipk + 同步 4 个内置插件到 app assets
bash scripts/build-plugins.sh --with-assets

# 校验全部插件清单
cd tools/xime-plugin && cargo run -- check --all --plugins-dir ../../plugins

# 跑宿主测试（需先构建插件）
./gradlew :plugin-core:testDebugUnitTest :app:testDebugUnitTest
```

## 常见问题

| 现象 | 原因 / 解决 |
|---|---|
| 测试报"找不到 build/plugin-js/&lt;name&gt;/main.js" | 先运行 `xipm build --all`（见上） |
| `JSON.parse('')` 抛异常 | 原生语义与旧 `host.json.decode`（失败返回 null）不同，需 `try/catch` 或保证输入合法 |
| 请求体传给 `host.http.request` 后为空 | body 必须是 `Uint8Array`（字符串不识别），用 `new TextEncoder().encode(...)` |
| 插件加载失败 "未定义全局对象 plugin" | `main.ts` 必须定义 `globalThis.plugin = { ... }` |
| 修改宿主主源码后测试行为未更新 | Gradle 中间产物缓存问题：删除 `plugin-core/build/intermediates/runtime_library_classes_dir` 后重跑 |
| 中文文件名在 `unzip` 中乱码 | 条目已带 UTF-8 flag，实际解码正常（macOS `ditto` / Java `ZipFile` 验证通过） |
| `xipm test` 报"未注册该请求的 stub" | 测试环境禁止真实联网：用 `__ximeMock.addHttpResponse` / `addWs` / `addSse` 显式 stub |
| `xipm dev` 报"未检测到在线设备" | `adb devices` 确认授权；无线调试先 `adb pair <ip:port>` + `adb connect <ip:port>` |
| `xipm dev` 报"不包含插件热安装组件" | 设备上不是最新 debug 包：`./gradlew installDebug` |
| `xipm dev` 报"未收到设备回执" | 宿主为旧 debug 包（无回执落盘）；或安装线程未执行，用 `xipm logs <dir>` 查看设备日志 |
| 插件 `console.log` 真机看不到 | 已由 console 落盘通道兜底（需最新 debug 宿主）；若仍无输出，确认插件确实执行到该行（如 `onTextCommitted` 需真实上屏触发） |
| 广播触发无效（"Background execution not allowed"） | Android 后台执行限制会丢弃后台应用的自定义广播；`xipm dev` 已改用 `am start` 无界面 Activity，无需前台 |
