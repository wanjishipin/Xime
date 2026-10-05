// 插件测试引擎：内嵌 QuickJS（rquickjs）+ mock host。
//
// 链路：bundle(main.ts → main.js) → bundle(main.test.ts → test.js) →
// AsyncContext 内 eval bootstrap(含 mock host) → eval main.js → eval test.js →
// 触发 __ximeRunTests()（async）→ 收集 TestCase[] 输出。

use std::path::{Path, PathBuf};
use std::time::Duration;

use anyhow::Result;
use rquickjs::context::EvalOptions;
use rquickjs::{AsyncContext, AsyncRuntime, Ctx, Function, Promise, TypedArray};

/// eval 时标注文件名：出错堆栈显示 main.js / main.test.ts 而非 eval_script。
fn eval_opts(name: &str) -> EvalOptions {
    let mut opts = EvalOptions::default();
    opts.filename = Some(name.to_string());
    opts
}

use crate::engine_state::NativeState;
use crate::manifest::Manifest;
/// 单个用例结果（JS 侧 __ximeRunTests 返回的原始对象）。
#[derive(Debug, Clone, rquickjs::FromJs)]
pub struct TestCase {
    name: String,
    passed: bool,
    message: String,
    stack: Option<String>,
}

/// 单个插件测试整体超时（防死循环/挂起）。
pub const TEST_TIMEOUT: Duration = Duration::from_secs(30);

/// 在给定产物目录运行插件测试。
///
/// - 存在 main.test.ts：运行全部用例，返回 Some(cases)
/// - 不存在且 `smoke=true`：仅加载 main.js（bundle + 引擎 eval）验证可加载，
///   返回 Some(空)（由调用方呈现"冒烟通过"）
/// - 不存在且 `smoke=false`：返回 None（跳过）
pub async fn run_plugin_tests(
    plugin_dir: &Path,
    out_dir: &Path,
    manifest: &Manifest,
    smoke: bool,
) -> Result<Option<Vec<TestCase>>> {
    let has_tests = plugin_dir.join("main.test.ts").is_file();
    if !has_tests && !smoke {
        return Ok(None);
    }

    let test_js = if has_tests {
        // bundle 测试入口（test.js，IIFE 副作用执行；test/assert 全局注入）
        let test_js_path = crate::build::bundle_test_js(plugin_dir, out_dir).await?;
        std::fs::read_to_string(&test_js_path)?
    } else {
        String::new()
    };
    let main_js = std::fs::read_to_string(out_dir.join("main.js"))?;

    let runtime = AsyncRuntime::new()?;
    let context = AsyncContext::full(&runtime).await?;

    crate::engine_state::set(NativeState {
        plugin_id: manifest.id.clone(),
        resources_dir: out_dir.join("resources"),
        main_js,
        test_js,
    });

    let result = context
        .async_with(run_tests_in_ctx)
        .await
        .map_err(|e: rquickjs::Error| anyhow::anyhow!("引擎执行失败: {e}"))?;

    Ok(Some(result))
}

/// 在 AsyncContext 内执行：bootstrap → main.js → test.js → 运行测试。
///（必须为 fn item：`async_with` 要求 `for<'js> AsyncFnOnce(Ctx<'js>)`，闭包捕获会导致
///  生命周期不够通用；输入经 engine_state 全局注入，测试严格串行无并发。）
async fn run_tests_in_ctx<'js>(ctx: Ctx<'js>) -> rquickjs::Result<Vec<TestCase>> {
    install_native_bindings(&ctx)?;

    ctx.eval_with_options::<(), _>(crate::engine_js::BOOTSTRAP_JS, eval_opts("xime-bootstrap.js"))?;
    let (main_js, test_js) = crate::engine_state::with(|s| (s.main_js.clone(), s.test_js.clone()));
    ctx.eval_with_options::<(), _>(main_js, eval_opts("main.js"))?;

    // 冒烟（无测试入口）：仅要求插件可加载（globalThis.plugin 为对象）
    if test_js.trim().is_empty() {
        let plugin: rquickjs::Value = ctx.globals().get("plugin")?;
        if !plugin.is_object() {
            return Err(rquickjs::Error::new_from_js_message(
                "plugin",
                "object",
                "加载后 globalThis.plugin 不是对象（入口未定义插件）",
            ));
        }
        return Ok(Vec::new());
    }

    ctx.eval_with_options::<(), _>(test_js, eval_opts("main.test.ts"))?;
    let run: Function = ctx.globals().get("__ximeRunTests")?;
    let promise: Promise = run.call(())?;
    promise.into_future().await
}

// ─────────────────────────────────────────────────────────────────────────────
// native 绑定：__ximeNative.*（JS mock host 的落地实现）
// ─────────────────────────────────────────────────────────────────────────────

fn install_native_bindings(ctx: &Ctx<'_>) -> rquickjs::Result<()> {
    let globals = ctx.globals();
    let obj = rquickjs::Object::new(ctx.clone())?;
    obj.set("log", Function::new(ctx.clone(), native_log)?)?;
    obj.set("textEncode", Function::new(ctx.clone(), native_text_encode)?)?;
    obj.set("textDecode", Function::new(ctx.clone(), native_text_decode)?)?;
    obj.set("atob", Function::new(ctx.clone(), native_atob)?)?;
    obj.set("btoa", Function::new(ctx.clone(), native_btoa)?)?;
    obj.set("gzip", Function::new(ctx.clone(), native_gzip)?)?;
    obj.set("gunzip", Function::new(ctx.clone(), native_gunzip)?)?;
    obj.set("sha256", Function::new(ctx.clone(), native_sha256)?)?;
    obj.set("hmacSha256", Function::new(ctx.clone(), native_hmac_sha256)?)?;
    obj.set("hmacSha1", Function::new(ctx.clone(), native_hmac_sha1)?)?;
    obj.set("hex", Function::new(ctx.clone(), native_hex)?)?;
    obj.set("base64", Function::new(ctx.clone(), native_base64)?)?;
    obj.set("resourcePath", Function::new(ctx.clone(), native_resource_path)?)?;
    obj.set("resourceList", Function::new(ctx.clone(), native_resource_list)?)?;
    globals.set("__ximeNative", obj)?;
    Ok(())
}

#[rquickjs::function]
fn native_log(level: String, message: String) {
    crate::engine_state::with(|s| crate::engine_js::emit_log(&s.plugin_id, &level, &message));
}

#[rquickjs::function]
fn native_text_encode<'js>(
    ctx: Ctx<'js>,
    s: String,
) -> rquickjs::Result<rquickjs::ArrayBuffer<'js>> {
    rquickjs::ArrayBuffer::new(ctx, s.into_bytes())
}

#[rquickjs::function]
fn native_text_decode<'js>(bytes: TypedArray<'js, u8>) -> String {
    let data = unsafe { bytes.as_bytes() }.unwrap_or(&[]);
    String::from_utf8_lossy(data).into_owned()
}

/// atob：base64 → 二进制串（宽松 padding，对齐宿主语义）。
#[rquickjs::function]
fn native_atob<'js>(s: String) -> String {
    use base64::Engine;
    let s = s.trim();
    let decode = |input: String| base64::engine::general_purpose::STANDARD.decode(input).ok();
    let bytes = decode(s.to_string())
        .or_else(|| decode(format!("{s}{}", "=".repeat((4 - s.len() % 4) % 4))))
        .unwrap_or_default();
    bytes.into_iter().map(|b| b as char).collect()
}

/// btoa：latin1 串 → base64。
#[rquickjs::function]
fn native_btoa<'js>(s: String) -> String {
    use base64::Engine;
    let bytes: Vec<u8> = s.chars().map(|c| c as u32 as u8).collect();
    base64::engine::general_purpose::STANDARD.encode(bytes)
}

#[rquickjs::function]
fn native_gzip<'js>(
    ctx: Ctx<'js>,
    data: TypedArray<'js, u8>,
) -> rquickjs::Result<rquickjs::ArrayBuffer<'js>> {
    use std::io::Write;
    let bytes = unsafe { data.as_bytes() }.unwrap_or(&[]);
    let mut enc = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
    enc.write_all(bytes)
        .map_err(|e| rquickjs::Error::new_from_js_message("native", "gzip", format!("gzip 写入失败: {e}")))?;
    let out = enc
        .finish()
        .map_err(|e| rquickjs::Error::new_from_js_message("native", "gzip", format!("gzip 失败: {e}")))?;
    rquickjs::ArrayBuffer::new(ctx, out)
}

#[rquickjs::function]
fn native_gunzip<'js>(
    ctx: Ctx<'js>,
    data: TypedArray<'js, u8>,
) -> rquickjs::Result<rquickjs::ArrayBuffer<'js>> {
    use std::io::Read;
    let bytes = unsafe { data.as_bytes() }.unwrap_or(&[]);
    let mut dec = flate2::read::GzDecoder::new(bytes);
    let mut out = Vec::new();
    dec.read_to_end(&mut out)
        .map_err(|e| rquickjs::Error::new_from_js_message("native", "gunzip", format!("gunzip 失败: {e}")))?;
    rquickjs::ArrayBuffer::new(ctx, out)
}

#[rquickjs::function]
fn native_sha256<'js>(
    ctx: Ctx<'js>,
    data: TypedArray<'js, u8>,
) -> rquickjs::Result<rquickjs::ArrayBuffer<'js>> {
    use sha2::Digest;
    let bytes = unsafe { data.as_bytes() }.unwrap_or(&[]);
    let hash = sha2::Sha256::digest(bytes);
    rquickjs::ArrayBuffer::new(ctx, hash.to_vec())
}

#[rquickjs::function]
fn native_hmac_sha256<'js>(
    ctx: Ctx<'js>,
    key: TypedArray<'js, u8>,
    data: TypedArray<'js, u8>,
) -> rquickjs::Result<rquickjs::ArrayBuffer<'js>> {
    use hmac::digest::KeyInit;
    use hmac::{Hmac, Mac};
    let k = unsafe { key.as_bytes() }.unwrap_or(&[]);
    let d = unsafe { data.as_bytes() }.unwrap_or(&[]);
    let mut mac = Hmac::<sha2::Sha256>::new_from_slice(k)
        .map_err(|e| rquickjs::Error::new_from_js_message("native", "hmac-sha256", format!("hmac 失败: {e}")))?;
    mac.update(d);
    rquickjs::ArrayBuffer::new(ctx, mac.finalize().into_bytes().to_vec())
}

#[rquickjs::function]
fn native_hmac_sha1<'js>(
    ctx: Ctx<'js>,
    key: TypedArray<'js, u8>,
    data: TypedArray<'js, u8>,
) -> rquickjs::Result<rquickjs::ArrayBuffer<'js>> {
    use hmac::digest::KeyInit;
    use hmac::{Hmac, Mac};
    let k = unsafe { key.as_bytes() }.unwrap_or(&[]);
    let d = unsafe { data.as_bytes() }.unwrap_or(&[]);
    let mut mac = Hmac::<sha1::Sha1>::new_from_slice(k)
        .map_err(|e| rquickjs::Error::new_from_js_message("native", "hmac-sha1", format!("hmac 失败: {e}")))?;
    mac.update(d);
    rquickjs::ArrayBuffer::new(ctx, mac.finalize().into_bytes().to_vec())
}

#[rquickjs::function]
fn native_hex<'js>(bytes: TypedArray<'js, u8>) -> String {
    let data = unsafe { bytes.as_bytes() }.unwrap_or(&[]);
    data.iter().map(|b| format!("{b:02x}")).collect()
}

#[rquickjs::function]
fn native_base64<'js>(bytes: TypedArray<'js, u8>) -> String {
    use base64::Engine;
    let data = unsafe { bytes.as_bytes() }.unwrap_or(&[]);
    base64::engine::general_purpose::STANDARD.encode(data)
}

/// resource.path：合法资源名 → 绝对路径；不存在返回 null。
#[rquickjs::function]
fn native_resource_path<'js>(name: String) -> Option<String> {
    crate::engine_state::with(|s| {
        if !is_valid_resource_name(&name) {
            return None;
        }
        let file = s.resources_dir.join(&name);
        if file.is_file() {
            Some(file.to_string_lossy().into_owned())
        } else {
            None
        }
    })
}

/// resource.list：列出 resources/<dir> 下的文件名（不含子目录）。
#[rquickjs::function]
fn native_resource_list<'js>(dir: String) -> Vec<String> {
    crate::engine_state::with(|s| {
        if !is_valid_resource_name(&dir) {
            return Vec::new();
        }
        let dir_path = s.resources_dir.join(&dir);
        let mut out: Vec<String> = std::fs::read_dir(&dir_path)
            .map(|entries| {
                entries
                    .flatten()
                    .filter(|e| e.file_type().map(|t| t.is_file()).unwrap_or(false))
                    .map(|e| e.file_name().to_string_lossy().into_owned())
                    .collect()
            })
            .unwrap_or_default();
        out.sort();
        out
    })
}

/// 资源名约束（对齐宿主 InstallerManager.isValidResourcePath：禁止路径穿越）。
fn is_valid_resource_name(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 255
        && !name.starts_with('/')
        && !name.contains("..")
        && !name.contains('\\')
}

// ─────────────────────────────────────────────────────────────────────────────
// 测试输出
// ─────────────────────────────────────────────────────────────────────────────

/// 打印单个插件的结果；返回 (通过数, 失败数)。
pub fn print_results(results: &[TestCase]) -> (usize, usize) {
    let mut passed = 0;
    let mut failed = 0;
    for t in results {
        if t.passed {
            passed += 1;
            println!("  {}", crate::engine_js::green(&format!("✓ {}", t.name)));
        } else {
            failed += 1;
            println!("  {}", crate::engine_js::red(&format!("✗ {}", t.name)));
            if !t.message.is_empty() {
                println!("    {}", crate::engine_js::red(&t.message));
            }
            if let Some(loc) = t.stack.as_deref().and_then(source_line_from_stack) {
                println!("    {} {}", crate::engine_js::yellow("→ 代码："), loc);
            }
            if let Some(stack) = &t.stack {
                for line in stack.lines().take(6) {
                    println!("    {}", line);
                }
            }
        }
    }
    (passed, failed)
}

/// 从堆栈定位出错点并取源码行：`main.js:191` → "main.js:191  ui.pusha({...})"。
///
/// 测试环境用 eval_with_options 标注了文件名，堆栈形如
/// `at state (main.js:191:31)`；取第一个可映射的行（bundle 未压缩，行号可用）。
fn source_line_from_stack(stack: &str) -> Option<String> {
    for raw in stack.lines() {
        let line = raw.trim();
        for file in ["main.test.ts", "main.js"] {
            let Some(pos) = line.find(file) else { continue };
            let rest = &line[pos + file.len()..];
            let mut chars = rest.chars();
            if chars.next() != Some(':') {
                continue;
            }
            let digits: String = chars.take_while(|c| c.is_ascii_digit()).collect();
            let Ok(num) = digits.parse::<usize>() else { continue };
            let src = crate::engine_state::with(|s| {
                if file == "main.js" {
                    s.main_js.clone()
                } else {
                    s.test_js.clone()
                }
            });
            if let Some(code) = src.lines().nth(num.saturating_sub(1)) {
                return Some(format!("{file}:{num}  {}", code.trim()));
            }
        }
    }
    None
}

// 让未使用的 PathBuf/Duration 引用保持语义清晰（超时参数由调用方使用）。
#[allow(dead_code)]
fn _unused(_p: &PathBuf, _d: Duration) {}
// ─────────────────────────────────────────────────────────────────────────────
// 扩展点一致性检查（xipm check）：加载编译产物 → 枚举 globalThis.plugin 扩展点
// ─────────────────────────────────────────────────────────────────────────────

/// manifest type → 必需扩展点键（宿主按类型实例化 Adapter，只调用对应扩展点）。
fn required_extension(type_name: &str) -> Option<&'static str> {
    match type_name {
        "tool" => Some("panel"),
        "speech" => Some("speech"),
        "emoji" => Some("emoji"),
        "clipboard_sync" => Some("clipboardSync"),
        "backup" => Some("backup"),
        _ => None,
    }
}

/// 内建扩展点键（与 BUILTIN_TYPES 一一对应；clipboard_sync 的键为 clipboardSync）。
fn is_builtin_extension_key(key: &str) -> bool {
    matches!(key, "panel" | "speech" | "emoji" | "clipboardSync" | "backup")
}

/// 扩展点声明与 manifest type 的一致性校验（纯函数，可离线单测）：
/// - 缺少本类型的必需扩展点 → error（emoji/speech 等类型的扩展点即插件本体）；
///   tool 型例外：panel 缺失仅 warning（纯管线型 tool——候选变换/事件——不开面板，合法）；
/// - 声明了其他类型的扩展点 → error（宿主按类型路由，永远不会被调用）。
/// 横切键（events/settings/transform/ws/sse/onLoad/onUnload）任何类型均可声明。
/// 返回 (errors, warnings)，文案面向插件作者。
pub fn validate_extensions(declared: &[String], type_name: &str) -> (Vec<String>, Vec<String>) {
    let mut errors = Vec::new();
    let mut warnings = Vec::new();
    let Some(required) = required_extension(type_name) else {
        return (errors, warnings); // 未知类型已在 capabilities 校验中报错，此处不重复
    };
    if !declared.iter().any(|k| k == required) {
        let msg = format!(
            "{type_name} 型插件应声明 {required} 扩展点（缺失时宿主侧该能力为空）"
        );
        // 纯管线型 tool（候选变换/事件订阅，无工具栏入口）不开面板是合法形态
        if type_name == "tool" {
            warnings.push(format!(
                "{msg}；纯管线型 tool（仅 transform/events）可忽略"
            ));
        } else {
            errors.push(msg);
        }
    }
    for key in declared {
        if is_builtin_extension_key(key) && key != required {
            errors.push(format!(
                "扩展点 {key} 与插件类型 {type_name} 不匹配（宿主只调用 {required}），该扩展点永远不会被调用"
            ));
        }
    }
    (errors, warnings)
}

/// 加载编译产物（bootstrap + main.js）并枚举 globalThis.plugin 的顶层键。
/// 入口未定义插件对象时返回错误（与冒烟检查同口径）。
pub async fn inspect_extensions(
    main_js: String,
    plugin_id: &str,
    resources_dir: PathBuf,
) -> Result<Vec<String>> {
    crate::engine_state::set(NativeState {
        plugin_id: plugin_id.to_string(),
        resources_dir,
        main_js,
        test_js: String::new(),
    });
    let runtime = AsyncRuntime::new()?;
    let context = AsyncContext::full(&runtime).await?;
    let declared = context
        .async_with(inspect_in_ctx)
        .await
        .map_err(|e: rquickjs::Error| anyhow::anyhow!("引擎执行失败: {e}"))?;
    Ok(declared)
}

///（fn item：`async_with` 要求 `for<'js>`，与 run_tests_in_ctx 同理）
async fn inspect_in_ctx<'js>(ctx: Ctx<'js>) -> rquickjs::Result<Vec<String>> {
    install_native_bindings(&ctx)?;
    ctx.eval_with_options::<(), _>(crate::engine_js::BOOTSTRAP_JS, eval_opts("xime-bootstrap.js"))?;
    let main_js = crate::engine_state::with(|s| s.main_js.clone());
    ctx.eval_with_options::<(), _>(main_js, eval_opts("main.js"))?;
    let keys: String = ctx.eval_with_options(
        "(function(){var p=globalThis.plugin;if(!p||typeof p!=='object')return '';return JSON.stringify(Object.keys(p));})()",
        eval_opts("xime-inspect.js"),
    )?;
    if keys.is_empty() {
        return Err(rquickjs::Error::new_from_js_message(
            "plugin",
            "object",
            "加载后 globalThis.plugin 不是对象（入口未定义插件）",
        ));
    }
    serde_json::from_str(&keys)
        .map_err(|e| rquickjs::Error::new_from_js_message("plugin", "keys", e.to_string()))
}

#[cfg(test)]
mod extension_check_tests {
    use super::validate_extensions;

    fn declared(keys: &[&str]) -> Vec<String> {
        keys.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn tool_without_panel_is_warning() {
        // 纯管线型 tool（仅 transform/events）不开面板是合法形态，仅提示
        let (errors, warnings) = validate_extensions(&declared(&["transform", "events"]), "tool");
        assert!(errors.is_empty());
        assert_eq!(warnings.len(), 1);
        assert!(warnings[0].contains("panel"));
    }

    #[test]
    fn builtin_extension_of_other_type_is_error() {
        // emoji 型声明了 panel：缺 emoji 是 error + panel 错配是 error
        let (errors, warnings) = validate_extensions(&declared(&["panel"]), "emoji");
        assert_eq!(errors.len(), 2);
        assert!(errors.iter().any(|e| e.contains("emoji 扩展点")));
        assert!(errors.iter().any(|e| e.contains("panel") && e.contains("emoji")));
        assert!(warnings.is_empty());

        // tool 型声明了 panel（正确）+ backup（错配）：只有 backup 一条 error
        let (errors, warnings) = validate_extensions(&declared(&["panel", "backup"]), "tool");
        assert_eq!(errors.len(), 1);
        assert!(errors[0].contains("backup"));
        assert!(warnings.is_empty());
    }

    #[test]
    fn correct_declaration_passes() {
        let cases = [
            (&["panel", "events", "settings"][..], "tool"),
            (&["speech", "ws"][..], "speech"),
            (&["emoji"][..], "emoji"),
            (&["clipboardSync"][..], "clipboard_sync"),
            (&["backup"][..], "backup"),
        ];
        for (keys, ty) in cases {
            let (errors, warnings) = validate_extensions(&declared(keys), ty);
            assert!(errors.is_empty(), "{ty}: {errors:?}");
            assert!(warnings.is_empty(), "{ty}: {warnings:?}");
        }
    }

    #[test]
    fn unknown_type_skipped() {
        // 未知类型在 capabilities 校验中已报错，此处不重复
        let (errors, warnings) = validate_extensions(&declared(&["panel"]), "widget");
        assert!(errors.is_empty());
        assert!(warnings.is_empty());
    }
}
