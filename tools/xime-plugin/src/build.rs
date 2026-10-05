use std::path::{Path, PathBuf};

/// 产物自包含 shim：`definePlugin` 为类型恒等函数（SDK 类型见 xime-plugin.d.ts）。
/// 顶层 var 在 QuickJS 脚本模式下即 globalThis.definePlugin；宿主也注入同名恒等函数（幂等）。
/// 注入使产物可在裸 QuickJS（无宿主注入）中执行——冒烟测试与本地调试均受益。
const DEFINE_PLUGIN_SHIM: &str = "var definePlugin = function (spec) { return spec; };";

use rolldown::{
    AddonOutputOption, Bundler, BundlerOptions, InputItem, OutputExports, OutputFormat, Platform,
    RawMinifyOptions,
};

use crate::manifest::Manifest;

/// 单个插件的构建结果。
pub struct BuildOutcome {
    /// 产物目录（含 main.js + manifest.json + resources/）
    pub out_dir: PathBuf,
    pub manifest: Manifest,
}

/// 插件源码入口（TS）。
const SOURCE_ENTRY: &str = "main.ts";

/// 插件测试入口（TS）：仅测试用，不进 xipk 产物。
pub const TEST_ENTRY: &str = "main.test.ts";

/// 构建单个插件：`<plugin_dir>/main.ts` → `<out_root>/<plugin_name>/main.js`，
/// 并复制 manifest.json 与 resources/（xipk 打包与测试加载均基于该产物目录）。
pub async fn build_plugin(plugin_dir: &Path, out_root: &Path, minify: bool) -> anyhow::Result<BuildOutcome> {
    let plugin_dir = strip_unc_prefix(
        plugin_dir
            .canonicalize()
            .map_err(|e| anyhow::anyhow!("插件目录不存在: {} ({e})", plugin_dir.display()))?,
    );

    // 输出根目录规范化为绝对路径：rolldown 的 dir 相对 cwd（插件目录）解释，
    // 相对路径会落到插件目录内（plugins/<name>/build/...），必须绝对化。
    std::fs::create_dir_all(out_root)
        .map_err(|e| anyhow::anyhow!("创建输出目录失败 {}: {e}", out_root.display()))?;
    let out_root = strip_unc_prefix(
        out_root
            .canonicalize()
            .map_err(|e| anyhow::anyhow!("规范化输出目录失败 {}: {e}", out_root.display()))?,
    );

    let manifest = Manifest::load(&plugin_dir).map_err(|e| anyhow::anyhow!("{e}"))?;

    let source_entry = plugin_dir.join(SOURCE_ENTRY);
    if !source_entry.is_file() {
        anyhow::bail!("入口源码不存在: {}", source_entry.display());
    }

    let plugin_name = plugin_dir
        .file_name()
        .map(|s| s.to_string_lossy().into_owned())
        .ok_or_else(|| anyhow::anyhow!("无法解析插件目录名: {}", plugin_dir.display()))?;

    let out_dir = out_root.join(&plugin_name);
    if out_dir.exists() {
        std::fs::remove_dir_all(&out_dir)
            .map_err(|e| anyhow::anyhow!("清理产物目录失败 {}: {e}", out_dir.display()))?;
    }
    std::fs::create_dir_all(&out_dir)
        .map_err(|e| anyhow::anyhow!("创建产物目录失败 {}: {e}", out_dir.display()))?;

    bundle_ts(&plugin_dir, &out_dir, minify).await?;

    // manifest.json 原样复制（宿主用宽松 JSON 解析，保留注释）
    std::fs::copy(Manifest::path_in(&plugin_dir), out_dir.join("manifest.json"))
        .map_err(|e| anyhow::anyhow!("复制 manifest.json 失败: {e}"))?;

    // resources/ 递归复制
    let resources_src = plugin_dir.join("resources");
    if resources_src.is_dir() {
        copy_dir_all(&resources_src, &out_dir.join("resources"))?;
    }

    Ok(BuildOutcome {
        out_dir,
        manifest,
    })
}

/// 批量构建：扫描 `<plugins_root>/*/manifest.json`，逐个编译到 `<out_root>/<name>/`。
pub async fn build_all(plugins_root: &Path, out_root: &Path, minify: bool) -> anyhow::Result<Vec<BuildOutcome>> {
    let mut plugin_dirs: Vec<PathBuf> = Vec::new();
    for entry in std::fs::read_dir(plugins_root)
        .map_err(|e| anyhow::anyhow!("读取插件根目录失败 {}: {e}", plugins_root.display()))?
    {
        let dir = entry?.path();
        if dir.is_dir() && Manifest::path_in(&dir).is_file() {
            plugin_dirs.push(dir);
        }
    }
    plugin_dirs.sort();

    let mut outcomes = Vec::new();
    for dir in plugin_dirs {
        outcomes.push(build_plugin(&dir, out_root, minify).await?);
    }
    Ok(outcomes)
}

/// rolldown：TS 模块（多文件相对 import 内联）→ IIFE 单文件。
///
/// 输出形态 `var plugin = (function () { ... })();`——QuickJS 脚本模式下
/// 等价于 `globalThis.plugin`，与宿主契约（入口脚本定义全局对象 plugin）一致。
/// 插件源码因此可以写成标准 TS 模块（`export default {...} satisfies XimePlugin`）。
async fn bundle_ts(plugin_dir: &Path, out_dir: &Path, minify: bool) -> anyhow::Result<()> {
    bundle_entry(
        plugin_dir,
        out_dir,
        SOURCE_ENTRY,
        "main",
        "plugin",
        minify,
        Some(DEFINE_PLUGIN_SHIM),
        OutputExports::Default,
    )
    .await
}

/// 打包测试入口：`<plugin_dir>/main.test.ts` → `<out_dir>/test.js`（IIFE，副作用执行；
/// 全局 test/assert 由测试环境注入，源码无需 import/export）。
pub async fn bundle_test_js(plugin_dir: &Path, out_dir: &Path) -> anyhow::Result<PathBuf> {
    // rolldown 的 cwd/input 需可解析路径：与 build_plugin 一致先规范化
    let plugin_dir = strip_unc_prefix(
        plugin_dir
            .canonicalize()
            .map_err(|e| anyhow::anyhow!("插件目录不存在: {} ({e})", plugin_dir.display()))?,
    );
    let entry = plugin_dir.join(TEST_ENTRY);
    if !entry.is_file() {
        anyhow::bail!("测试入口不存在: {}", entry.display());
    }
    bundle_entry(
        &plugin_dir,
        out_dir,
        TEST_ENTRY,
        "test",
        "test",
        false,
        None,
        OutputExports::None,
    )
    .await?;
    Ok(out_dir.join("test.js"))
}

/// 通用 rolldown 打包：TS 模块（多文件相对 import 内联）→ IIFE 单文件。
async fn bundle_entry(
    plugin_dir: &Path,
    out_dir: &Path,
    entry: &str,
    file_name: &str,
    global_name: &str,
    minify: bool,
    banner: Option<&str>,
    exports: OutputExports,
) -> anyhow::Result<()> {
    // 入口一律用绝对路径：rolldown 在 Windows 上对 cwd + 相对 import 的解析不可靠
    let abs_entry = plugin_dir.join(entry);
    let mut bundler = Bundler::new(BundlerOptions {
        input: Some(vec![InputItem {
            name: Some(file_name.to_string()),
            import: abs_entry.to_string_lossy().into_owned(),
        }]),
        cwd: Some(plugin_dir.to_path_buf()),
        format: Some(OutputFormat::Iife),
        name: Some(global_name.to_string()),
        exports: Some(exports),
        // compress + 局部变量 mangle（属性名不混淆：宿主契约方法名/事件字段名必须保留）
        minify: Some(RawMinifyOptions::Bool(minify)),
        banner: Some(AddonOutputOption::String(banner.map(|s| s.to_string()))),
        dir: Some(out_dir.to_string_lossy().into_owned()),
        platform: Some(Platform::Browser),
        ..Default::default()
    })
    .map_err(|e| anyhow::anyhow!("bundler 初始化失败: {e:?}"))?;

    bundler
        .write()
        .await
        .map_err(|e| anyhow::anyhow!("TS 编译失败: {e:?}"))?;

    let out = out_dir.join(format!("{file_name}.js"));
    if !out.is_file() {
        anyhow::bail!("编译未生成 {file_name}.js：{}", out.display());
    }
    Ok(())
}

fn copy_dir_all(src: &Path, dst: &Path) -> anyhow::Result<()> {
    std::fs::create_dir_all(dst)?;
    for entry in std::fs::read_dir(src)? {
        let entry = entry?;
        let file_type = entry.file_type()?;
        let target = dst.join(entry.file_name());
        if file_type.is_dir() {
            copy_dir_all(&entry.path(), &target)?;
        } else {
            std::fs::copy(entry.path(), target)?;
        }
    }
    Ok(())
}

/// 去掉 Windows `canonicalize()` 产生的 `\\?\` 扩展长度前缀。
///
/// rolldown 会把该前缀归一化成 `//?/C:/...` 并当成模块标识符解析，导致
/// `UNRESOLVED_ENTRY: Cannot resolve entry module //?/C:/.../main.ts`——
/// 所有插件在 Windows 上都无法编译。非 Windows 或本身无前缀时原样返回。
fn strip_unc_prefix(path: PathBuf) -> PathBuf {
    let raw = path.to_string_lossy();
    if let Some(rest) = raw.strip_prefix(r"\\?\") {
        // UNC 共享路径（\\?\UNC\server\share）需还原为 \\server\share
        if let Some(unc) = rest.strip_prefix(r"UNC\") {
            return PathBuf::from(format!(r"\\{unc}"));
        }
        return PathBuf::from(rest);
    }
    path
}
