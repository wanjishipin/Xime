# 剪贴板图片支持 · 技术方案

> 状态：方案待评审（未写实现代码）。目标功能：剪贴板可复制/粘贴图片，
> 并在多地同步（WebDAV 等插件）中承载图片附件。
> 基准：当前工作区 `main` 分支，行号对应当前文件内容。

---

## 1. 结论摘要

**可行，且基础设施已具备大半。** 三条关键结论：

1. **回写路径已经存在**：`ClipboardManager.copyImageToSystemClipboard()`（`ClipboardManager.kt:358-388`，用 `ClipData.newUri`）已被 Emoji 表情图片在用；
   `ImeTextCommit.commitImage()`（`ImeTextCommit.kt:49-106`）已实现「宿主支持图片 MIME → `commitContent` 直插；否则降级剪贴板」的完整探测与降级逻辑（`ImeKeyboardCallbacks.kt:152-162`）。
   → 图片点选可以直接复用这条链路，**不需要新造轮子**。
2. **采集路径是缺口**：`ClipboardItem`/`ClipboardEntry` 只有 `text` 字段，`readClipboard()` 对图片型 ClipData 会把 `item.uri.toString()` 当**文本**存进历史（`ClipboardManager.kt:69-76`）——
   即现状「复制图片 → 剪贴板历史多一条 `content://...` 无用文本」。这是本功能必须一并修掉的既存缺陷。
3. **同步的传输层二进制能力不缺，缺的是数据模型与协议路径**：`host.http` 请求体/响应体原生支持 `Uint8Array`
   （`xime-plugin.d.ts:498-520`，桥实现 `JsScriptRuntime.kt:718`、`:752`），`ByteArray ↔ Uint8Array` 跨桥已用于备份归档
   （`JsBackupPluginAdapter.kt:27-45`）。硬阻塞只有 4 处：`ClipboardProfile` 无字节字段（`ClipboardSyncPlugin.kt:28-35`）、
   JS 适配器 pull 以 `text` 非空判空（`JsClipboardSyncPluginAdapter.kt:50`）、宿主桥纯文本驱动
   （`ClipboardSyncBridge.kt:99/145-155/180-194`）、插件只写单个 JSON 文件（`plugins/webdav-clipboard-sync/main.ts:198-216`）。

**建议拆四个阶段**（每阶段独立端到端验收后进入下一阶段，符合 AGENTS.md「每次只做一个功能点」）：
① 本地图片剪贴板闭环 → ② 导入入口与设置 → ③ 同步附件 → ④ 生态扩展（可选）。

---

## 2. 现状盘点（事实，含行号）

### 2.1 数据层

| 事实 | 位置 |
|---|---|
| `ClipboardEntry` 仅 6 列：`text/code/timestamp/isPinned/isQuickSend/consumed`；`text` 非空 | `clipboard/db/ClipboardEntry.kt:12-21` |
| Room `version = 3`，已有 1→2、2→3 迁移范式 | `clipboard/db/ClipboardDatabase.kt:14-41` |
| 去重/淘汰按 `text` 唯一（`upsertAndTrim`），上限 `MAX_ITEMS = 1000` | `ClipboardDao.kt:73-85`、`ClipboardManager.kt:42` |
| 删除均为按 id / 清空，**不涉及任何文件** | `ClipboardDao.kt:37-50` |

### 2.2 采集层

| 事实 | 位置 |
|---|---|
| `OnPrimaryClipChangedListener` → `readClipboard()`（3 次重试、100ms） | `ClipboardManager.kt:60-90` |
| 取值优先级 `item.text` → `item.uri.toString()` → `item.intent.toUri(0)` | `ClipboardManager.kt:69-74` |
| 回写文本 `copyToSystemClipboard(text)`；读当前文本 `getCurrentClipboardText()` | `ClipboardManager.kt:326-336` |
| 回写图片 `copyImageToSystemClipboard(imagePath, label)`：FileProvider 优先，失败降级 MediaStore（API 29+） | `ClipboardManager.kt:358-442` |
| FileProvider paths 已覆盖 `files-path path="."`（files 目录任意子路径可用） | `res/xml/filepaths.xml`、`AndroidManifest.xml:57-65` |
| 服务启动时初始化 + 订阅 `clipboardItems` / `quickSendItems` | `XimeInputMethodService.kt:764-793` |

### 2.3 UI 层

| 事实 | 位置 |
|---|---|
| `ClipboardItem` 只有 `text`（+ id/code/时间/置顶/快捷/已消费） | `ClipboardManager.kt:27-36` |
| 面板两 Tab：剪贴板（2 列网格卡片）/ 快捷发送；卡片只渲染文本，固定高 62dp | `ui/menubar/ClipboardView.kt:583-648` |
| 长按菜单：文本 = 拆词/快捷/多选/删除；快捷发送 = 置顶/编辑/删除 | `ClipboardView.kt:374-445` |
| 多选删除、清空确认、同步拉取按钮均已存在 | `ClipboardView.kt:189-243`、`:325-370`、`:447-460` |
| 点选回调 `onSelectItem(text)` → `callbacks.onClipboardSelect(text)` | `KeyboardView.kt:1365-1368` |
| 点选实现：标 consumed + 上屏 + 回写系统剪贴板 | `ImeTextCommit.selectClipboardItem()` `ImeTextCommit.kt:119-133` |
| 面板内已有「大图预览」范式（黑底全屏、点击关闭、1~5x 缩放）可复用 | `ui/settings/LayoutDetailScreen.kt` 的 `ScreenshotPreviewDialog` |
| Coil 2.7.0 已引入（emoji 图片已在用 `AsyncImage`） | `app/build.gradle.kts:223`、`EmojiKeyboardLayout.kt:582-600` |

### 2.4 同步层

| 事实 | 位置 |
|---|---|
| 引擎纯文本驱动：`filter { it.text.isNotBlank() }` → `pushLocal(text, hash)` → `ClipboardProfile.fromText` | `clipboard/sync/ClipboardSyncBridge.kt:98-115`、`:145-168` |
| 拉取写回只写文本：`copyToSystemClipboard(remote.text)` | `ClipboardSyncBridge.kt:170-195` |
| 去重/回声抑制全部基于文本 SHA-256 | `ClipboardSyncBridge.kt:71-77`、`:101`、`:180-186` |
| `ClipboardProfile` 有 `hasData`/`dataName`（为附件预留）但**无字节字段**，`fromText` 硬编码 false/null | `plugin-core/.../api/ClipboardSyncPlugin.kt:28-56` |
| JS 适配器 push 只映射 6 个标量；**pull 以 `text` 非空判空**（图片-only 会被丢） | `JsClipboardSyncPluginAdapter.kt:25-66`（关键 `:50`） |
| `clipboard_sync` 能力声明只有 `protocols` | `plugin-core/.../model/PluginCapabilities.kt:115-118` |
| 插件启动前校验 `protocols` 非空 | `XimeInputMethodService.kt:810-817` |
| webdav：单文件 `{dav}/…/clipboard/current.json`，PUT 全量 JSON，blob/附件零支持 | `plugins/webdav-clipboard-sync/main.ts:182-251`、`libs/dav-url.ts:8` |
| webdav pull：ETag + If-None-Match 判 304；JSON 分支要求 `text` 键存在，否则落「纯文本兼容」分支（会把整段 JSON 当文本） | `plugins/webdav-clipboard-sync/main.ts:254-334`（关键 `:298-324`） |
| webdav 503 计数退避（pull 跳 20 / push 跳 5）；ximed 无退避 | `main.ts:35-43`、`:217-222`、`:326-331` |
| ximed：单端点 `{server}/api/clipboard`，同样只有 JSON、无附件；服务端代码不在本仓库 | `plugins/ximed-clipboard-sync/main.ts:26`、`:118-192` |
| 宿主 HTTP：连接 10s / 读写 30s，请求响应**全量进内存**，无字节上限 | `plugin/http/HttpHostApiImpl.kt:39-41`、`:100`、`:147` |

### 2.5 可直接复用的既有能力（不要重复造）

| 能力 | 位置 | 用途 |
|---|---|---|
| `commitContent` + MIME 探测 + 剪贴板降级 | `ImeTextCommit.kt:49-116`、`ImeKeyboardCallbacks.kt:152-162` | 图片点选「能直插则直插，否则复制到剪贴板」 |
| `copyImageToSystemClipboard` | `ClipboardManager.kt:358-442` | 图片回写系统剪贴板 |
| FileProvider（`files-path .`） | `res/xml/filepaths.xml` | 把本地图片文件暴露给粘贴方 |
| Coil `AsyncImage` | `EmojiKeyboardLayout.kt:582-600` | 面板缩略图（自动按目标尺寸降采样） |
| `ByteArray ↔ Uint8Array` 跨桥 | `JsScriptRuntime.kt:655-661`、`JsBackupPluginAdapter.kt:27-45` | 图片字节进插件 |
| `host.http` 二进制请求体/响应体 | `xime-plugin.d.ts:498-520`、`JsScriptRuntime.kt:718/:752` | 插件上传/下载 blob |
| `host.crypto.sha256/hex`、`TextEncoder` | `xime-plugin.d.ts:550-559` | 插件侧一致性校验（可选） |

---

## 3. 目标与非目标

**目标（本方案覆盖）**
- 系统剪贴板中的**图片**能被 Xime 采集为剪贴板历史条目（本地持久化，含缩略图展示）。
- 面板中可**预览 / 点选 / 删除 / 多选删除 / 清空**图片条目；点选后可在微信、Telegram 等目标应用粘贴发送。
- 图片条目可经 WebDAV/ximed 等同步插件多设备同步（可选、默认压缩、可关闭）。
- 全程**不新增运行时权限**、不引入外部依赖。

**非目标（明确排除，避免范围蔓延）**
- 不实现「IME 内截屏」；不申请相册读取权限（如需选图走系统 Photo Picker）。
- 不做视频/音频/文件（非 `image/*`）的剪贴板历史。
- 不改变现有文本剪贴板语义（去重、上限、快捷发送、候选栏）。
- 不自建云端；同步仍由插件承载传输。

---

## 4. 总体设计

```
              ┌──────────────── 系统剪贴板（唯一外部入口） ────────────────┐
              │ ClipData(image)                        ClipData(text)     │
              └───────┬────────────────────────────────────────┬──────────┘
                      │ OnPrimaryClipChangedListener            │
                 ┌────▼────────────────────────────────────────▼─────┐
                 │ ClipboardManager.readClipboard()                  │
                 │  · 敏感剪贴板跳过（API33+ EXTRA_IS_SENSITIVE）      │
                 │  · image/* → ClipboardImageStore.save(bytes)      │
                 │  · text    → 现有 addItem（行为不变）              │
                 └────┬──────────────────────────────────────────────┘
                      │ ClipboardEntry(type=image, imageHash, imagePath, …)
              ┌───────▼────────┐    clipboardChanged(ClipboardItem)
              │ Room v4        │───────────────┬──────────────────────────┐
              └───────┬────────┘               │                          │
                      │ observeAll             │                          │
        ┌─────────────▼───────────┐   ┌────────▼─────────────┐   ┌────────▼──────────────┐
        │ ClipboardView（面板）    │   │ 候选栏 recentItems    │   │ ClipboardSyncBridge   │
        │ · 缩略图卡片 AsyncImage  │   │ · 仅文本（图片排除）   │   │ · 文本 push/pull 不变  │
        │ · 预览/多选/删除/清空    │   └──────────────────────┘   │ · 图片 → 压缩变体 + blob│
        └─────────────┬───────────┘                              └────────┬──────────────┘
                      │ 点选图片                                          │ plugin.push/pull
        ┌─────────────▼────────────────────────────┐            ┌────────▼─────────────┐
        │ onClipboardImageSelect(id)               │            │ ClipboardSyncPlugin  │
        │ ① commitImage(path) 成功 → 直插宿主       │            │ （webdav / ximed）    │
        │ ② 失败 → copyImageToSystemClipboard(path) │            │ · blobs/<hash> GET/PUT│
        │    + Toast「已复制图片，长按输入框粘贴」    │            └──────────────────────┘
        └──────────────────────────────────────────┘
```

---

## 5. 详细设计

### 5.1 数据模型（Room v3 → v4）

`ClipboardEntry` 新增 7 列，全部带 DEFAULT，迁移为纯 `ALTER TABLE`：

```kotlin
@ColumnInfo(defaultValue = "text") val type: String = "text",       // "text" | "image"
@ColumnInfo(defaultValue = "")     val imagePath: String = "",      // 相对 files/ 的路径（"clipboard_images/<hash>.<ext>"）
@ColumnInfo(defaultValue = "")     val imageHash: String = "",      // sha256(bytes)，图片去重键
@ColumnInfo(defaultValue = "")     val mimeType: String = "",       // image/png 等
@ColumnInfo(defaultValue = "0")    val sizeBytes: Long = 0,
@ColumnInfo(defaultValue = "0")    val width: Int = 0,
@ColumnInfo(defaultValue = "0")    val height: Int = 0,
```

```kotlin
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override suspend fun migrate(connection: SQLiteConnection) {
        listOf(
            "ALTER TABLE clipboard_entries ADD COLUMN type TEXT NOT NULL DEFAULT 'text'",
            "ALTER TABLE clipboard_entries ADD COLUMN imagePath TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE clipboard_entries ADD COLUMN imageHash TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE clipboard_entries ADD COLUMN mimeType TEXT NOT NULL DEFAULT ''",
            "ALTER TABLE clipboard_entries ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE clipboard_entries ADD COLUMN width INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE clipboard_entries ADD COLUMN height INTEGER NOT NULL DEFAULT 0",
        ).forEach { connection.prepare(it).step() }
        // 新增索引必须显式建（Room 校验期望 schema，缺失会抛 "Migration didn't properly handle"）
        connection.prepare(
            "CREATE INDEX IF NOT EXISTS index_clipboard_entries_imageHash " +
                "ON clipboard_entries (imageHash)"
        ).step()
    }
}
```
`@Database(version = 4)` + `addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)`；
新增索引 `Index("imageHash")`（`@Entity` 上叠加，Room 会自动建索引）。

`ClipboardItem` 同步新增 `type/imagePath/imageHash/mimeType/sizeBytes/width/height`，并提供
`val isImage get() = type == TYPE_IMAGE` 便捷判定。

DAO 新增（与现有文本版本同构，保证"最小侵入"）：

```kotlin
@Query("SELECT * FROM clipboard_entries WHERE imageHash = :hash AND isQuickSend = 0 LIMIT 1")
suspend fun findImageByHash(hash: String): ClipboardEntry?

@Query("SELECT * FROM clipboard_entries WHERE id IN (:ids)")
suspend fun findByIds(ids: List<Long>): List<ClipboardEntry>   // 删除前取文件路径用

@Transaction
suspend fun upsertImageAndTrim(entry: ClipboardEntry, now: Long, maxImages: Int) { … }
```

**决策点 D-A（去重语义）**：图片按 `imageHash` 去重（同图重复复制 = 更新时间戳并置顶），与文本按 `text` 去重语义一致。

### 5.2 图片存储 `ClipboardImageStore`（新文件）

位置：`app/src/main/java/com/kingzcheung/xime/clipboard/ClipboardImageStore.kt`

- 目录：`filesDir/clipboard_images/`（**不加 `.nomedia`**：不影响相册，因为不在 MediaStore 扫描路径；FileProvider 已覆盖该路径）。
- 命名：`<sha256>.<ext>`（内容寻址 → 天然去重、天然幂等，也便于同步 blob 命名对齐）。
- 契约（全部纯函数化以便 JVM 单测，目录/DAO 以接口注入）：
  - `save(bytes, hash, ext): File`
  - `delete(path): Boolean`
  - `cleanupOrphans(knownPaths: Set<String>): Int`（启动时清理 DB 无记录的残留文件）
  - `enforceQuota(entries, protectedPaths): List<String>`（返回被淘汰的文件路径，交给调用方删行 + 删文件）
- 配额（常量集中在 companion，便于单测）：
  - `MAX_IMAGE_BYTES = 5 MB`：单张超过**不采集**（日志 + Toast 提示；不报错、不阻塞）
  - `MAX_IMAGE_EDGE = 4096 px`：单张宽或高超过**不采集**（同上；本应用不压缩，见决策 D8）
  - `MAX_TOTAL_BYTES = 100 MB`
  - `MAX_IMAGES = 200`（与文本 `MAX_ITEMS = 1000` 独立）
- 淘汰顺序：`consumed = 1` → 非置顶 → `timestamp ASC`；**置顶（isPinned）永不淘汰**。
  置顶与保护项**占用名额**：上限扣减只能由其余条目承担，因此「不可淘汰项数 > 上限」时不再淘汰任何条目
  （已在 `ClipboardImageStoreTest` 中以不变式锁定）。
- **保护集**：当前系统剪贴板上仍是「本应用 FileProvider URI」的图片路径必须排除在淘汰之外
  （否则粘贴方后续读取会失败）——采集/回写时记录 `systemClipboardImagePath`，每次读取 `primaryClip` 时刷新。

### 5.3 采集（`ClipboardManager.readClipboard()` 改造）

```kotlin
val clip = androidClipboardManager.primaryClip
if (clip != null && clip.itemCount > 0) {
    val desc = clip.description
    if (isSensitive(desc)) return                       // API33+ ClipDescription.EXTRA_IS_SENSITIVE
    val uri = imageUriOf(clip)                          // desc.hasMimeType("image/*") 且 item.uri != null
    if (uri != null) { captureImage(uri, desc); return } // 失败只记日志，不再退回文本分支
    // ↓ 文本分支保持原样（text → uri 字符串 → intent）
}
```

`captureImage(uri, desc)` 要点：
1. `contentResolver.openInputStream(uri)`：`SecurityException` / `FileNotFoundException` → 记日志后**直接返回**
   （**不再**把 `content://…` 存成文本条目——修掉 2.2 的既存污染行为）。
2. 流式读取并在 `MAX_IMAGE_BYTES + 1` 处截断判定（避免大图整包进内存）；截断与超限都要能
   **区分「读取失败」与「体积超限」**（前者静默，后者需提示用户），故读取结果用
   `ImageReadResult(bytes, tooLarge)` 而非裸 `ByteArray?`。
3. MIME 三级兜底：`desc.getMimeType(0)` → `resolver.getType(uri)` → 魔数嗅探（PNG/JPEG/GIF/WebP/BMP/HEIC）；
   非 `image/*` 或不在白名单 → 跳过。
4. `sha256`（宿主已有 `ClipboardProfile.sha256Hex`）→ `imageStore.save()`；同 hash 已存在则跳过写盘。
5. `BitmapFactory.decodeByteArray(..., inJustDecodeBounds)` 取 `width/height`（不解码像素，零 OOM 风险；
   直接从字节解，省掉一次落盘后读文件）。
6. 准入判定 `ClipboardImageStore.rejectReason(size, width, height)`（纯函数）：字节 > 5MB 或边长 > 4096px
   → 拒收 + Toast（10s 去重，避免连续复制连环提示）；尺寸解不出（0×0）时只按字节判定，不误杀。
7. `dao.upsertImageAndTrim(...)` + `_clipboardChanged.emit(item)`（携带图片字段，供同步订阅）。
7. **自写回声**：记录 `selfWrittenImageHash`；采集命中同 hash 时只更新时间戳，不重复落盘与推送。

### 5.4 UI（`ClipboardView.kt`）

- `GridItemCard` → 拆为 `ClipboardItemCard(item, …)`：
  - `text` 分支：**字节不动**（保持现状渲染与多选高亮）。
  - `image` 分支：`AsyncImage(model = File(item.imagePath), contentScale = Crop, modifier = aspectRatio(...))`
    + 右下角小角标（尺寸/「图」）；失败态显示占位图标（文件已被清理的兜底）。
- 长按菜单按类型分流（`ClipboardView.kt:374-445`）：
  - 文本：拆词 / 快捷 / 多选 / 删除（不变）。
  - 图片：**预览 / 保存到相册（可选）/ 多选 / 删除**（「拆词」「快捷」对图片无意义，隐藏而非置灰）。
- 预览：新增 `ImagePreviewDialog`（全屏黑底 + 点击关闭 + 缩放拖动），直接借用 `ScreenshotPreviewDialog` 的实现范式。
- 多选删除 / 清空 / 单删：沿用按 id 的通用逻辑；`ClipboardManager.removeItem/removeItems/clearClipboard/clearAll`
  内部改为「先取 `imagePath` → 删行 → 删文件」，并加 `imageStore.enforceQuota`。
- 点选图片：新回调 `onClipboardImageSelect(item)`：
  ```
  commitImage(item.imagePath)   // ① 宿主支持 image/* → 直插（commitContent）
      ?: run {                  // ② 否则
          copyImageToSystemClipboard(item.imagePath)   // 写系统剪贴板
          Toast「已复制图片，长按输入框粘贴」
      }
  markConsumedById(item.id); closeOverlay()
  ```
  **注意**：既有 `KeyboardView.kt:1365-1368` 的点选实现是「调回调 → `closeOverlay()`」，而 `closeOverlay` 只关面板、
  **不收起键盘**——正合图片需求（用户需要在键盘仍在时于输入框长按粘贴），无需改这段。
- 快捷发送 Tab：图片不参与（`addToQuickSend` 对图片隐藏入口），`QuickSendHostApi` 保持文本快照。

### 5.5 候选栏与其它消费者

- `getRecentItems()` / `XimeInputMethodService.kt:1963-1999` 的候选栏投递：**文本与图片一并返回**
  （决策 D6 已改判为 D9：图片候选与文本候选在候选栏混排，图片条目 `text` 为空串占位）。
  候选栏把图片位渲染成 **Gboard 风格芯片**（大圆角矩形底 + 圆形缩略图 + 「图片」二字）：
  - `CandidateBarState.ClipboardDisplay` 增加与 `candidates` **等长同序**的 `images: List<ClipboardItem?>`
    （非图片位 null）；`CandidateBar.kt` 按位分支渲染 `ClipboardImageCandidateItem`。
  - 索引不变式：候选栏点选回调只传 index，服务层用该 index 取 `recentClipboardItemsState`，
    因此**任何过滤/截断都必须对 `candidates` 与 `images` 同步**（已由 `ClipboardBarStateTest` 锁定）。
  - 点选图片候选走与面板一致的 `selectClipboardImage`（commitContent 直插 → 失败落系统剪贴板 + 提示），
    并同样标记 `consumed` 使其从候选栏消失。
  - 紧凑/浮空候选栏（`HardwareKeyboardCandidateBar`）只渲染文本：图片位替换为「图片」标签，
    长度与顺序不变以保持索引对齐。
  - 候选行 `verticalAlignment` 改为居中对齐：图片芯片比文字候选高，否则同排文字会被顶到上沿。
- `QuickSendHostApiImpl`：不变（text 字段照旧）。
- `typing-stats` 等插件：粘贴不上屏（`commitPastedText` 不投 `text_committed`），图片路径不受影响。

### 5.6 设置（Phase 2 ✅ 已实现）

新增「剪贴板」设置页（`SettingsRoutes.Clipboard = "clipboard"`，
`ui/settings/ClipboardSettingsScreen.kt`），作为剪贴板的**容器页**，「剪贴板同步」收为其内页
（返回回到剪贴板页；插件管理页的直达入口不变）：

```
设置主页
└ 数据与同步（原「同步与备份」分组改名：现在同时罩住本地剪贴板与词典/配置的同步备份）
  ├ 剪贴板（本页）
  │  ├ 图片记录：收集复制的图片（开关，默认开；关闭后图片一律不采集，文字不受影响）
  │  ├ 图片上限：单张最大体积（1–20 MB，默认 5）+ 图片占用（N 张 · 实际目录占用，调上限时的参照）
  │  └ 剪贴板同步 →（内页）
  └ 同步与备份（词典互通与配置备份，未改动）
```

**可见项刻意做少**（用户拍板）：只有"需要用户决定"的才暴露——采集开关、单张上限，
外加调上限时的参照数字（图片占用）。最长边（4096px）/ 保留数量（200 张）/ 总容量（100MB）是
**内置限制**，既不可配、也不在界面上罗列（产品视角：没必要把每一项内部参数都摊给用户看），
文案只留一句"超过上限的图片不会被保存"。
**清理不设在本页**：剪贴板历史（文字 + 图片文件 + 残留）只在 关于 → 存储空间 → 「剪贴板历史」
清理，一个功能一个入口，设置页不重复放按钮。

- **只有单张上限可配**：`ClipboardImageStore.MAX_IMAGE_BYTES` 降级为默认值，纯函数
  `rejectReason(..., maxBytes = 默认值)` 与 `save(..., maxBytes = 默认值)` 接受传入上限
  （默认参数保持既有调用/测试不变）；`SettingsPreferences.get/setClipboardImageMaxMb` 读写并对
  区间 `coerceIn`（防脏值越界）；`ClipboardManager.imageLimits()` 采集时读取，
  `maxTotalBytes` 取 `maxOf(内置总容量, 单张上限)`（否则新图会因容量超限被立即淘汰，
  表现为"复制了却什么都没发生"）。最长边/数量/总容量直接取内置常量，无偏好项。
- 拒收 Toast 文案按当前阈值生成（原先写死 5MB，改上限后会与实际判定不一致）。
- 清理唯一入口：关于 → 存储空间 → 「剪贴板历史」，走
  `StorageStats.clearCategory(ID_CLIPBOARD)`（等待 `clearClipboardAndWait()` 完成后返回，
  含图片文件与孤儿残留）；剪贴板设置页**不再**放清理按钮。
- 「忽略敏感剪贴板」固定生效（API 33+ 系统标记的敏感剪贴板不入库），文案说明，不给关闭开关。
- 状态管理：滑块值为本地 `mutableFloatStateOf`，`onValueChangeFinished` 才落盘；
  占用统计在 `LaunchedEffect` 里切 IO 读取（`ClipboardManager.imageUsage()`），清理后重新读取。

### 5.6.1 解码尺寸与"是否需要真缩略图"（决策 D10）

**现状（已实现）**：三处渲染都显式声明解码上限方框（`ui/ClipboardImageRequests.kt`），
与控件尺寸无关地钉死最坏位图：

| 使用面 | 控件尺寸 | 解码上限 | 最坏位图 |
|---|---|---|---|
| 候选栏芯片 | 28dp 圆 | 128px 方框 | ~64KB（极端 256² ≈ 256KB） |
| 面板卡片 | ~165×62dp | 512px 方框 | ~1MB（极端 1024² ≈ 4MB） |
| 全屏预览 | 整屏 | 屏幕 px（显式） | ~10MB（单张，仅预览时） |

Coil 2 的 `AsyncImage` 本就用 `coil.compose.ConstraintsSizeResolver` 按控件像素降采样
（已解包 aar 核实），显式上限是**兜底**：一旦某处父容器变成无界约束，Coil 会退回
`Size.ORIGINAL`（4096² ARGB_8888 ≈ **64MB**）→ 输入法进程 OOM。项目自定义 `ImageLoader`
内存缓存 16MB / 磁盘 32MB（`XimeApplication.kt:27`）；采集端另有 `inJustDecodeBounds`
（只读头部，零像素分配）与默认 5MB/4096px 的采集上限（Phase 2 起可在设置页调整）。三层叠加后**崩溃路径已封死**。

**结论：暂不生成真缩略图文件（决策见 D10）**。理由：

1. 真缩略图优化的是**解码耗时**（首帧 ~30–80ms → ~1–3ms），不是"会不会崩"——后者已由上限解决；
   失败面只是首帧略空白，且解码在 IO 线程、Coil 4 路并发 + 16MB 内存缓存。
2. 成本落在**刚写完、尚未真机验收**的存储层：配额统计（多 ~200 个文件）、孤儿清理的已知路径集、
   删除级联、旧条目回填、编码失败降级；且缩略图生成是 Android-only API，纯 JVM 测不了。
3. **不能复用为同步载荷**：256px 缩略图在另一台设备上粘贴可用性差，Phase 3 传的是**原图**
   （D12 改判，撤销 D3 的压缩副本），尺寸差一个量级——"顺带铺路"不成立。

**重新评估的触发条件**（任一满足就把"惰性生成 + 独立 thumb 目录"补上）：
面板滚动明显掉帧（低端机/图文长截图多）；Phase 2 把保留数量调到 500+ 或放开单张上限
（原图变大 ⇒ 解码成本上升）；平板/横屏面板同屏可见图片数显著增加。

### 5.7 同步（Phase 3 · 图片附件）

#### 5.7.0 决策（D12）

| 项 | 决定 | 理由 |
|---|---|---|
| 远端布局 | **沿用 ximed 布局**：`clipboard/current.json`（Profile JSON）+ 新增 `clipboard/blobs/<hash>.<ext>` | 与 ximed 的 WebDAV 直连保持互通；附件只需一个并列目录 |
| 协议来源 | 借 SyncClipboard 的**字段语义**：`has_data` / `data_name` / `size` + **内容寻址命名** | 不引它的 `SyncClipboard.json` + `file/` 布局，避免与 ximed 布局二选一 |
| `hash` 语义（图片） | **= 原图字节 SHA-256**（小写 hex，与本地 `imageHash`、磁盘文件名一致） | SyncClipboard 里承担这一角色的是 `transferDataHash`；内容寻址命名让它同时就是"文件名决定式" |
| `data_name` | `<hash>.<ext>`（如 `9f86d081….png`） | PUT 幂等；同一图片跨设备 hash 稳定 ⇒ 不会来回乒乓 |
| 载荷形态 | **原图直传**，宿主不做压缩；受现有「单张最大体积」约束 | D3 的"压缩副本"改判：原画质跨设备可用，且省掉宿主侧编解码与一组设置项 |
| 新增协议字段 | **无**（不引 `mime_type` / `transfer_data_hash`） | 扩展名已在 `data_name` 中，MIME 由宿主按扩展名反查；字段越少越不易破坏兼容 |
| 图片同步闸门 | 由插件能力 `attachments` 决定（不新增用户设置） | 用户唯一可调项仍是「单张最大体积」 |

**为什么不用 SyncClipboard 的"文件名参与 hash"**（`SHA256("name\|contentHash")`）：它的文件名是
时间戳式（`Image_2026-….png`），所以需要二次哈希把名字纳入语义；Xime 的文件名**由内容决定**，
语义 hash 直接等于内容 hash，二次哈希只会把"同图不同名"的问题换个位置再出现一次。

#### 5.7.1 分层与职责

| 层 | 职责 | 不做什么 |
|---|---|---|
| 宿主 `ClipboardSyncBridge` | 本地图片事件分派、读原图字节、去重、写回本地 + 剪贴板 | 不碰协议；**不做图像编解码**（D12：原图直传） |
| 插件 | 只做 blob 文件的 PUT/GET 与 JSON 元数据 | 不做图像编解码 |

#### 5.7.2 契约扩展（向后兼容，纯新增字段）

```kotlin
// ClipboardSyncPlugin.kt
data class ClipboardProfile(
    val type: String = "text",        // "text" | "image"
    val hash: String,
    val text: String,                 // 图片条目为 ""（**不再**用"text 非空"判空）
    val hasData: Boolean = false,
    val dataName: String? = null,
    val data: ByteArray? = null,      // 新增：附件字节（原图，未压缩）
    val size: Long = 0,
    val source: String? = null,
)
```
> ⚠️ `data class` 带 `ByteArray` 会退化为引用相等：需手写 `equals/hashCode`。
> 现有 `JsClipboardSyncPluginAdapter` 的字段映射补齐 `data`（**不新增 `mimeType`**：
> 扩展名已在 `data_name` 里，需要时由宿主按扩展名反查 MIME）。

```ts
// xime-plugin.d.ts
interface XimeClipboardProfile {
  type: string; hash: string; text: string;
  hasData: boolean; dataName: string | null;
  data?: Uint8Array | null;      // 新增（原图字节，未压缩）
  size: number; source: string | null;
}
```

**空值判定的修正（关键）**：`JsClipboardSyncPluginAdapter.pull():50` 的
`map["text"]?.toString()?.takeIf { it.isNotEmpty() } ?: return null` 必须改为
`if (text.isEmpty() && !hasData) return null`，否则图片-only profile 永远被判为「无变更」。

**能力声明**（宿主消费能力的唯一来源）：

```json
"capabilities": { "clipboard_sync": { "protocols": ["webdav"], "attachments": true } }
```
```kotlin
data class ClipboardSyncCapabilities(
    val protocols: List<String> = emptyList(),
    val attachments: Boolean = false,   // 新增：支持附件 blob
)
```
- 未声明 `attachments` 的插件：图片**不参与同步**，文本同步不受影响；设置页对选中插件显示
  「该插件不支持图片同步」提示（避免用户以为坏了）。
- 老版本宿主 + 新插件声明该字段（`ignoreUnknownKeys = true`）→ 静默忽略，兼容。

#### 5.7.3 宿主桥改造（`ClipboardSyncBridge.kt`）

- 订阅不再 `filter { it.text.isNotBlank() }`，改为按 `item.type` 分派 `pushLocalText` / `pushLocalImage`。
- 能力闸门：当前插件未声明 `attachments` → 图片条目**不推送**（记一次日志，文本路径不受影响）。
- `pushLocalImage(item)`：
  1. `bytes = File(filesDir, item.imagePath).readBytes()`（不存在则跳过）；`item.imageHash` 即内容 hash。
  2. 与 `lastHash` 相同则跳过（去重键 = **内容 hash**，天然跨设备幂等）。
  3. `plugin.push(ClipboardProfile(type="image", text="", hash=item.imageHash, hasData=true,`
     `dataName="${item.imageHash}.${ext}", data=bytes, size=bytes.size))`。
  4. 失败 → 沿用 5 秒退避（现有 `PUSH_RETRY_BACKOFF_MS`）。
- `pullRemote()`：
  - `remote.hasData && remote.data != null` → 校验 `sha256(data) == remote.hash`（不等则丢弃并记日志）
    → `ClipboardManager.importImageFromSync(data, dataName)` 落盘 + 入库（按 `imageHash` 去重）
    → `copyImageToSystemClipboard(本地绝对路径, shareCacheCopy = false)`；`lastHash = selfWritten = hash`。
  - `remote.hasData && remote.data == null`（插件不支持附件 / blob 下载失败）→ 记日志跳过，**不写回空文本**。
  - 文本路径**完全不变**（含现有 `currentHash` 比较与回声抑制）。
  - 回环：本应用写系统剪贴板用的是自己的 FileProvider URI，采集分支按"自写 URI"直接跳过，
    不会自己触发一轮推送；内容 hash 去重再兜一层。

#### 5.7.4 插件改造（webdav 先做，ximed 待服务端确认）

- `libs/dav-url.ts` 新增 `buildBlobUrl(davUrl, remotePath, dataName)` → `{dav}/{remotePath}/clipboard/blobs/<dataName>`；
  附件名先过白名单 `^[A-Za-z0-9][A-Za-z0-9._-]*$`（内容寻址名只含 hex 与扩展名，顺带挡掉 `..`/路径分隔符），
  复用 `buildFileUrl` 的目录再替换末段，避免"目录怎么拼"写两份。
- `push(profile)`：
  1. `if (profile.hasData && profile.data)` → `PUT blobUrl`，`Content-Type` 按 `data_name` 的扩展名推断
     （`image/png` / `image/jpeg` / `image/webp` / `image/gif` …，兜底 `application/octet-stream`），
     body 用 `profile.data`（**Uint8Array 直传，无需 base64**）；blob 路径 409/404 → 复用现有
     `ensureDirectories` 后重试一次。
  2. blob 成功后才 `PUT current.json`（**不新增字段**：`has_data` / `data_name` / `size` 已足够）；
     blob 失败 → 整体 false，**绝不**让 JSON 指向缺失 blob。
  3. blob 名即内容 hash → 重复 PUT 幂等，**不做 HEAD 预检**（省一次请求，避开 503 限流）。
- `pull()`：
  1. JSON 分支判定从「存在 `text` 键」改为「是对象 且（`text` 键存在 或 `has_data === true`）」，
     **阻止图片记录落进 `main.ts:313-324` 的纯文本兼容分支**（否则会把整段 JSON 当剪贴板文本写回本地）。
  2. `has_data === true` 且 `data_name` 非空 → 用 **`resp.body`**（不是 `resp.text`）GET blob，
     返回含 `data` 的 profile（`type` 取 `"image"`）；blob 404 → 清 `lastEtag` 并返回 null
     （下轮重试，避免永久跳过）。
  3. 503 退避沿用现有计数逻辑（blob 请求计入同一退避窗口）。
- `manifest.json`：`capabilities.clipboard_sync.attachments: true`，版本 bump（assets `onlyIfNewer` 守卫要求）。
- **ximed 插件**：附件端点需与服务端协议对齐（服务端代码不在本仓库，无法核实），Phase 3 内先不接；
  其 `clipboardSync` 不声明 `attachments`，宿主自动降级为文本同步。
- 测试：`plugins/*/main.test.ts` 扩展（blob PUT 在 JSON 之前、blob 404、`has_data` profile 解析、
  mock host 的二进制断言）；`--smoke` 冒烟。

#### 5.7.5 体积与流量策略

- **不新增任何同步相关设置**：图片载荷 = 原图，受既有的「单张最大体积」（默认 5MB，1–20MB 可调）约束；
  超过上限的图在采集阶段就已被拒收，根本不会进入同步队列。
- 已知代价（记录为接受的取舍）：宿主 HTTP 请求是「30s 超时 + 全量进内存」（`HttpHostApiImpl.kt:38-41`），
  单张越大峰值内存越高；JS 桥的 `ByteArray` 走 base64（`kotlinToJs → __ximeHost.b64`），
  5MB 图会产生约 6.7MB 中间字符串，峰值大致 2.5× 图大小；用户把上限调到 20MB 时峰值可达 ~50MB。
  真正的缓解手段是宿主侧压缩（原 D3，已改判）或流式上传 API——**等真机出现 OOM 反馈再补**。
- 坚果云等免费服务的限流（每 30 分钟 600 次请求）由现有 503 计数退避覆盖：图片推送多两次请求
  （PUT blob + PUT json），顺序固定为**先 blob 后 json**，blob 失败整体失败，不产生悬空引用。
- 冲突与取消：不做并发控制（沿用现状单 JSON 覆盖语义）；图片 blob 因内容寻址天然无冲突。

### 5.8 隐私与合规

- 剪贴板内容属用户数据：图片会**落盘到应用私有目录**并**可选上传到用户自己的 WebDAV**。
  需同步更新 app 内「隐私」说明（剪贴板图片本地保存 + 同步范围），以及 `SECURITY.md` 的相应描述。
- 敏感剪贴板（密码管理器等，API 33+ `EXTRA_IS_SENSITIVE`）一律跳过。
- 不申请存储/相册权限；`FileProvider` 走 `grantUriPermissions` 一次性授权。

---

## 6. 分阶段实施计划

### Phase 1 · 本地图片剪贴板闭环（唯一功能点）

| 改动 | 文件 |
|---|---|
| 数据模型 + 迁移 v4 + DAO | `clipboard/db/ClipboardEntry.kt`、`ClipboardDatabase.kt`、`ClipboardDao.kt` |
| 图片存储/配额/淘汰 | 新增 `clipboard/ClipboardImageStore.kt` |
| 采集分支（图片 + 修掉 URI 污染） | `clipboard/ClipboardManager.kt` |
| `ClipboardItem` 字段 + `selectClipboardImage` | `ClipboardManager.kt`、`ImeTextCommit.kt` |
| 回写/直插回调 | `ImeKeyboardCallbacks.kt`（新 `onClipboardImageSelect`）、`KeyboardView.kt`、`InputUIState.kt`/`KeyboardViewModel` 透传 |
| 面板卡片 + 长按菜单 + 预览 | `ui/menubar/ClipboardView.kt` |
| 候选栏图片芯片（D9） | `clipboard/ClipboardManager.kt`（`getRecentItems`）、`ui/keyboard/CandidateBarState.kt`、`CandidateBar.kt`、`KeyboardView.kt`、`service/ImeKeyRouter.kt`、`XimeInputMethodService.kt`（紧凑栏兜底标签） |
| 单测（JVM）+ androidTest | 见 §7 |

**验收（必须逐条过）**
1. 任意应用复制图片 → 面板出现缩略图卡片，**且不再出现 `content://…` 文本条目**。
2. 点选图片 → 微信/Telegram 输入框长按粘贴 → 发送成功（**真机关键验证项**）。
3. 支持 `image/*` 的编辑器（如 Gmail/记事本类）点选 → 直接插入图片（`commitContent` 路径）。
4. 长按删除 / 多选删除 / 清空 → `files/clipboard_images/` 下对应文件同步消失（adb 核对）。
5. 图片条目**在候选栏显示为「图片」芯片**（文本候选混排），点选与面板点选同效并随即消失；
   文本剪贴板全链路（采集/候选栏/快捷发送/同步）行为无回归。
6. 10MB+ 截图不 OOM、面板滚动流畅。
7. 复制密码管理器内容（敏感标记）→ 不入库。
8. `./gradlew test`、`assembleDebug`、`androidTest ClipboardManagerTest` 全绿；v3 旧库升级不丢数据。

### Phase 2 · 导入入口与设置（设置部分 ✅ 已完成）

- ✅ 新增「剪贴板」容器设置页（采集开关 / 四项配额 / 占用统计 / 立即清理），
  「剪贴板同步」收为内页，设置分组「同步与备份」→「数据与同步」（见 §5.6）。
- 选图入口（可选，成本较高）：IME 是 Service，**无法直接**拿 `ActivityResult`；
  需新增透明代理 Activity（`Theme.Translucent.NoTitleBar`）承载 `PickVisualMedia`，结果经进程内单向
  `StateFlow`/Bus 回投 IME。仍按用户反馈再定（尚未实现）。
- 「最近截图」不做（依赖 ROM/版本行为，属 §8 中待实测的不确定项）。

### Phase 3 · 同步附件（webdav 先行）

- SDK/契约：`ClipboardProfile` + `d.ts` + `JsClipboardSyncPluginAdapter`（含空值判定修正）+ 能力声明 `attachments`。
- 宿主：`ClipboardSyncBridge` + 新增 `ClipboardImageSyncer` + 同步设置项。
- 插件：`webdav-clipboard-sync`（blob PUT/GET + `dav-url.ts` + manifest）；ximed 暂缓。
- 验收：A 设备复制图 → 远端出现 `clipboard/blobs/<hash>.webp` + `current.json(has_data=true)`；
  B 设备键盘弹出拉取 → 图片条目出现 → 点选可粘贴；旧插件（无 `attachments`）文本同步不回归、无报错；
  远端无 `has_data` 的旧文件仍走纯文本兼容分支。

### Phase 4 · 生态扩展（可选，各自独立立项）

- 图片「快捷发送」；`host.clipboard` 图片读取能力（供 AI 识图类插件）；
  图片保存到相册入口。（图片候选栏已提前在 Phase 1 落地，见 D9）

---

## 7. 测试计划

**JVM 单测（新增，纯逻辑可测）**
- `ClipboardImageStoreTest`：内容寻址命名、去重、`MAX_IMAGE_BYTES`/`MAX_IMAGE_EDGE` 拒绝（含边界值与
  「尺寸解不出不误杀」）、**单张上限可配而最长边仍走内置常量**（Phase 2：放宽字节上限后字节放行、
  边长判定不受影响；落盘兜底校验用传入上限且失败不留文件）、`MAX_IMAGES`/`MAX_TOTAL_BYTES` 淘汰顺序、
  置顶不淘汰、保护集不淘汰、孤儿清理。（目录注入临时文件夹 + 伪 DAO 接口）
- `ClipboardBarStateTest`：剪贴板候选态下 `candidates` 与 `images` **等长同序**（索引对齐不变式）、
  纯图片列表仍进入剪贴板态、非剪贴板态忽略图片参数。
- `ClipboardClipParserTest`：`ClipData` → (imageUri | text) 判定；敏感标记跳过；MIME 三级兜底；魔数嗅探。
- `ClipboardImageSyncerTest`：≤上限直通、超限缩放、质量递减终止、输出 mime/hash 稳定性。
- `ClipboardSyncBridgeImageTest`：图片 push 分支（含 `attachments=false` 时跳过）、pull 图片写回分支、
  `hasData=true 但 data=null` 降级、文本分支零回归。（需要给 `ClipboardManager` 抽最小接口/注入接缝）
- `ClipboardDaoMigrationTest`（如有 Robolectric/room-testing 条件）：v3 → v4 迁移后旧数据可读、默认值为 `text`。

**androidTest**
- 扩展 `ClipboardManagerTest`：真实 `setPrimaryClip(image ClipData)` → 断言条目 `type=image`、文件落盘、`imageHash` 正确；
  同图重复复制只更新 timestamp；删除/清空后文件消失。

**插件测试**
- `xipm test`：webdav 的 blob PUT 顺序（blob 成功才写 JSON）、blob 404、`has_data` profile 解析、JSON 无 `text` 键不落纯文本分支。
- `plugin-core`：`JsClipboardSyncPluginTest` / `JsWebdavClipboardSyncPluginTest` 增加 `data` 字节往返与
  「`text=""` + `hasData=true` 不被判为无变更」的契约断言。

---

## 8. 真机验证清单（关键未知项，必须实测）

| # | 验证项 | 为何必须真机 |
|---|---|---|
| V1 | 浏览器/相册「复制图片」→ 面板出现条目（缩略图正确） | 各 ROM 的 ClipData 形态差异 |
| V2 | 读 `content://` clip URI 的字节是否被授权（`openInputStream` 不抛 `SecurityException`） | 平台 URI 授权与厂商 ROM 行为；失败要能优雅跳过 |
| V3 | **点选图片 → 微信/Telegram 长按粘贴 → 发送成功** | 目标应用是否接受 FileProvider URI 的 `image/*` clip |
| V4 | 支持 `image/*` 的编辑器走 `commitContent` 直插 | `contentMimeTypes` 声明差异 |
| V5 | 剪贴板塞满大图后的内存/滚动表现（建议 `adb shell dumpsys meminfo <pkg>` 对照） | 显式解码上限（§5.6.1：芯片 128px / 卡片 512px / 预览屏幕 px）的实际效果；**>5MB 或 >4096px 应被拒收并弹一次 Toast**（10s 内不重复） |
| V6 | MediaStore 降级路径（FileProvider 失败的 ROM）是否污染相册 | 现有 `copyImageToSystemClipboard` 降级会把图片写入 `Pictures/Xime` |
| V7 | 敏感剪贴板标记（API 33+）是否真的被系统带上 | 厂商实现差异 |
| V8 | 同步：A 复制 → B 拉取 → 可粘贴；大图压缩后远端体积 | WebDAV 服务商限流/配额 |
| V9 | 复制图片后唤出键盘：候选栏出现「图片」芯片（圆形缩略图清晰、与文本候选混排不错位），点选即直插或落剪贴板并随即消失 | 各主题配色下的芯片对比度、Coil 在输入法窗口内的缩略图加载、索引对齐 |

> V3 是本功能成败的关键项：若发现 FileProvider URI 在粘贴方不可读，
> 备选方案是改用 `MediaStore` 插入（`Pictures/Xime`，API 29+ 免权限）作为**首选**路径，
> 代价是相册会留下 Xime 的图片副本（需产品决策）。

---

## 9. 影响面审查（AGENTS.md 要求）

| 面 | 影响 | 处置 |
|---|---|---|
| Room schema | v3 → v4；`exportSchema = false` 无 schema 文件变更 | 新增迁移 + androidTest 覆盖 |
| `ClipboardItem` 消费者 | `ClipboardView`、候选栏、`QuickSendHostApiImpl`、同步桥、`KeyboardViewModel` | 全部按 `type` 分支处理，文本路径零改动 |
| 既存行为变化 | 「复制图片存成 `content://` 文本」被修复（**行为变化，需在 PROGRESS 记录**） | 属缺陷修复，随 Phase 1 交付 |
| 剪贴板同步 | 引擎新增图片通道；Phase 2 起设置入口由主页挪到「剪贴板」内页 | 未声明 `attachments` 的插件自动降级为文本；文本同步零回归。设置主页分组「同步与备份」→「数据与同步」，插件管理页/扩展商店的剪贴板同步入口不受影响（仍直达 `clipboard_sync`） |
| **Auto Backup（重要）** | `allowBackup=true` 且 `backup_rules.xml` / `data_extraction_rules.xml` 均为空模板 → `files/` **默认纳入云备份**，图片会挤爆 25MB 配额 | 必须在两份 XML 中 `<exclude domain="file" path="clipboard_images/"/>` |
| 存储占用 | `files/clipboard_images/` 最多 100MB | 必须并入既有 `StorageStats` 的 `ID_CLIPBOARD` 统计（当前只算 `clipboard.db`，`StorageStats.kt:88`）——否则 `otherSize = filesDir - accounted` 会把图片目录算进**不可清理**的「其他数据」（`StorageStats.kt:92-93`）；同时 `clearCategory(ID_CLIPBOARD)` 需一并删除图片文件。**已实现**：清理走 `ClipboardManager.clearClipboardAndWait()`（持 `imageMutex` → 删行 → 删文件 → `cleanupOrphans(emptySet())` 兜底清残留 → 失效自写 URI 保护集），`clearCategory` 改 `suspend` 等待完成后才返回，保证统计页读到的占用已回落。Coil 解码缓存落在 `cacheDir`，已被「缓存」类目覆盖 |
| 隐私 | 剪贴板图片落盘 + 可选上传自有 WebDAV | 更新 app 内隐私说明与 `SECURITY.md` |
| 权限 | 无新增（不申请存储/相册；Photo Picker 免权限） | 无需改 `AndroidManifest` 权限 |
| FileProvider | `files-path path="."` 已覆盖新目录 | `filepaths.xml` 无需改动 |
| 依赖 | 无新增（Coil 已在；图像压缩用平台 `Bitmap`） | — |
| 内存/性能 | 缩略图仅在面板可见时解码，受限目标尺寸；采集侧用 `inJustDecodeBounds`/流式读取 | 不常驻 Bitmap |
| 设置可配项 | 仅两项写入 `kime_settings`：图片采集开关 + 单张上限 | 其余限制保持内置常量（不暴露给用户）；纯函数改为带默认参数，故既有调用与单测不受影响；脏值由 `coerceIn` 兜底 |
| 插件生态 | `clipboard_sync` 能力新增 `attachments` 字段 | 老宿主忽略未知键；老插件无字段 → 降级 |
| 测试基线 | 现有 `ClipboardManagerTest`（androidTest，384 行）依赖表结构 | 需同步更新并回归 |
| typing-stats 等插件 | 图片不上屏、不投 `text_committed` | 无影响（无需改） |

---

## 10. 风险与备选方案

| 风险 | 概率/影响 | 缓解 |
|---|---|---|
| V2/V3：clip URI 不可读 / 目标应用不接受 FileProvider URI | 中 / 高 | 采集侧失败即跳过；回写侧备选 MediaStore URI（代价：相册留副本）；最坏情况功能降级为「仅本地历史 + 保存到相册」 |
| 大图同步撑爆 WebDAV 配额/限流、或 JS 桥峰值内存过高 | 中 / 中 | 载荷受「单张最大体积」约束（默认 5MB，更大的图采集阶段已拒收）；不压缩（D12）；顺序固定先 blob 后 json + 503 计数退避；真机出现 OOM 再补宿主压缩或流式上传 |
| 图片被 LRU 淘汰后，系统剪贴板仍指向已删文件 | 中 / 中 | 保护集机制（§5.2）；淘汰仅在非保护集内进行 |
| WebDAV 单 JSON 覆盖写导致多设备互相覆盖（既有问题，图片会放大影响） | 中 / 中 | 本期不做冲突解决；blob 内容寻址避免附件覆盖；在 PROGRESS/文档记录为已知限制 |
| ximed 服务端附件协议未知 | 确定未知 | Phase 3 只做 webdav；ximed 待服务端确认后单独立项 |
| 采集频率与 `OnPrimaryClipChanged` 重试造成重复采集 | 低 / 低 | 按 `imageHash` 去重（更新时间戳） |
| IME 进程内存被大图拖垮 | 低 / 中 | 5MB 单张上限（流式读取即截断）+ `inJustDecodeBounds`（字节直解）+ Coil 目标尺寸 |

**备选路径（若 V3 失败）**：不做「回写系统剪贴板」，改为「图片条目点选 → 保存到相册 → Toast 提示用户从相册发送」。
这是明确的功能降级，需用户拍板后再实施。

---

## 11. 待确认决策（需拍板）

| # | 决策 | 建议 | 结论 |
|---|---|---|---|
| D1 | 图片是否**自动采集**（默认开/关） | 默认开（与 Gboard 一致），设置可关 | ✅ 默认开；设置项已落地（设置 → 数据与同步 → 剪贴板 → 收集复制的图片），关闭后图片剪贴板整体跳过、文字不受影响 |
| D2 | 点选图片的默认行为 | 先 `commitContent` 直插，失败落剪贴板（复用现有 Emoji 语义） | ✅ 采纳（Phase 1 已实现） |
| D3 | 同步是否压缩、默认上限 | 压缩；默认单张 1MB / 最长边 1920px | ⛔ **已改判（D12）**：不压缩，原图直传，只受「单张最大体积」约束 |
| D4 | 是否做「相册选图」入口（需透明代理 Activity） | Phase 2 可选，先做设置与采集开关 | 待定 |
| D5 | 非图片 `content://` URI 不再存成文本 | 同意（现状是污染；改为跳过） | ✅ Phase 1 已按此实现（读取失败/非图片一律跳过） |
| D6 | 候选栏是否显示图片条目 | 不显示（仅面板） | ⛔ **已改判**：用户要求与文本同逻辑显示，见 D9 |
| D7 | 图片是否参与「快捷发送」 | 不参与（Phase 4 再议） | ✅ Phase 1 已按此实现（图片长按菜单无「快捷」项） |
| D8 | 超大图（>5MB 或 >4096px）如何处理 | 拒收 + 提示（不压缩） | ✅ 用户拍板：直接拒收 + Toast 提示；**单张上限可配**（1–20MB，默认 5），最长边 4096px 为内置限制不可配也不展示 |
| D9 | 图片在候选栏的呈现方式 | 与文本同栏混排 + Gboard 风格芯片 | ✅ 用户拍板：大圆角矩形底 + 圆形缩略图 + 「图片」二字；点选同面板（直插→剪贴板），消费后消失 |
| D10 | 是否生成真缩略图文件 | 暂不做，靠"显式解码上限 + 有界降采样" | ✅ 评估结论：崩溃路径已被上限封死，真缩略图只优化解码耗时；成本落在未验收的存储层，且不能复用为同步载荷。触发条件见 §5.6.1 |
| D11 | 剪贴板设置的分组与信息架构（Phase 2） | 「剪贴板同步」从设置主页挪入「剪贴板」容器页 | ✅ 用户拍板（方案 A）：入口改名「剪贴板」并作为容器页（含开关/配额/占用清理 + 「剪贴板同步」内页）；设置分组「同步与备份」改名「数据与同步」，避免分组名与子项同名、且能罩住本地数据与同步备份两类 |
| D12 | 图片同步的远端布局与协议来源（Phase 3） | 借 SyncClipboard 的字段语义 | ✅ 用户拍板（方案 B）：**远端仍是 ximed 布局**（`clipboard/current.json`），附件放并列的 `clipboard/blobs/<hash>.<ext>`；图片 `hash` = 原图 SHA-256，`data_name` 内容寻址；**原图直传不压缩**（撤销 D3）；不新增协议字段与用户设置；详见 §5.7 |

---

## 附录 A：改动文件总览（Phase 1）

```
app/src/main/java/com/kingzcheung/xime/clipboard/db/ClipboardEntry.kt        (+7 列)
app/src/main/java/com/kingzcheung/xime/clipboard/db/ClipboardDatabase.kt     (v4 + MIGRATION_3_4)
app/src/main/java/com/kingzcheung/xime/clipboard/db/ClipboardDao.kt          (+图片查询/淘汰)
app/src/main/java/com/kingzcheung/xime/clipboard/ClipboardImageStore.kt      (新增)
app/src/main/java/com/kingzcheung/xime/clipboard/ClipboardManager.kt         (采集/删除/回写)
app/src/main/java/com/kingzcheung/xime/service/ImeTextCommit.kt              (+selectClipboardImage)
app/src/main/java/com/kingzcheung/xime/service/ImeKeyboardCallbacks.kt       (+onClipboardImageSelect)
app/src/main/java/com/kingzcheung/xime/service/XimeInputMethodService.kt      (候选栏过滤 + 接线)
app/src/main/java/com/kingzcheung/xime/ui/keyboard/KeyboardView.kt            (回调透传 + 预览)
app/src/main/java/com/kingzcheung/xime/ui/menubar/ClipboardView.kt            (卡片/菜单/预览)
app/src/main/res/xml/backup_rules.xml + data_extraction_rules.xml            (排除图片目录)
app/src/test/java/.../clipboard/**                                           (新增单测)
app/src/androidTest/java/.../clipboard/ClipboardManagerTest.kt                (图片用例)
```

## 附录 B：Phase 2 改动文件总览（设置页与 IA）

```
app/.../ui/settings/ClipboardSettingsScreen.kt    (新增：剪贴板容器页)
app/.../ui/settings/SettingsRoutes.kt             (+Clipboard = "clipboard")
app/.../ui/settings/SettingsScreen.kt             (新路由 + 主页回调改名)
app/.../ui/settings/SettingsMainScreen.kt         (分组「数据与同步」+「剪贴板」入口)
app/.../ui/settings/StorageSpaceScreen.kt         (剪贴板历史清理的唯一入口)
app/.../settings/SettingsPreferences.kt           (+图片采集开关与单张上限读写)
app/.../settings/StorageStats.kt                  (clearCategory → suspend，等待清理完成)
app/.../clipboard/ClipboardImageStore.kt          (阈值改带默认值的参数)
app/.../clipboard/ClipboardManager.kt             (开关接入 / imageLimits / imageUsage)
app/src/test/.../clipboard/ClipboardImageStoreTest.kt  (+2 例可配置阈值)
```

## 附录 C：Phase 3 改动文件总览

```
plugin-core/.../api/ClipboardSyncPlugin.kt                 (profile + data 字节，手写 equals/hashCode)
plugin-core/.../js/JsClipboardSyncPluginAdapter.kt         (字节映射 + 空值判定修正)
plugin-core/.../model/PluginCapabilities.kt                (attachments)
plugin-core/.../runtime/installer/{InstallerManager,PluginRegistry}.kt  (attachments 解析/序列化)
tools/xime-plugin/templates/xime-plugin.d.ts               (SDK 类型)
app/.../clipboard/sync/ClipboardSyncBridge.kt              (图片分派 + 能力闸门 + 写回)
app/.../clipboard/ClipboardManager.kt                      (importImageFromSync)
app/.../ui/settings/ClipboardSyncSettingsScreen.kt         (插件不支持图片时的提示行)
plugins/webdav-clipboard-sync/{main.ts,libs/dav-url.ts,manifest.json,main.test.ts}
plugins/ximed-clipboard-sync/manifest.json                 (不声明 attachments → 自动降级)
```
> 无 `ClipboardImageSyncer`、无同步设置项：D12 原图直传。