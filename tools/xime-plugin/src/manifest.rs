use std::path::{Path, PathBuf};

use serde::Deserialize;

pub const MANIFEST_FILE: &str = "manifest.json";

/// 插件清单（manifest.json，宽松 JSON5 解析：支持 // 注释与尾逗号）。
///
/// 与宿主 InstallerManager 的字段约定保持一致（camelCase），
/// 未知字段静默忽略（向前兼容）。
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
#[allow(dead_code)] // 部分字段供校验/未来使用（解析保留完整清单）
pub struct Manifest {
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub description: String,
    #[serde(default = "default_version")]
    pub version: String,
    #[serde(default = "default_type")]
    pub r#type: String,
    #[serde(default = "default_entry")]
    pub entry: String,
    #[serde(default)]
    pub icon: Option<String>,
    #[serde(default)]
    pub min_host_version: Option<String>,
    #[serde(default)]
    pub max_host_version: Option<String>,
    /// 能力声明（json5 保留原始结构；宿主是消费真相源，CLI 只做类型 × 能力合理性校验）。
    #[serde(default)]
    pub capabilities: Option<serde_json::Value>,
    /// 目标平台声明（多平台适配；缺省视为 ["android"]，与存量插件行为一致）。
    #[serde(default)]
    pub platforms: Option<Vec<String>>,
}

/// 已知平台标识（未知值仅告警不报错，为未来平台留向前兼容）。
pub const KNOWN_PLATFORMS: [&str; 5] = ["android", "ios", "windows", "macos", "linux"];

/// 缺省平台：platforms 缺省/为空时视为仅 android（存量插件零改动即可通过校验）。
pub const DEFAULT_PLATFORM: &str = "android";

fn default_version() -> String {
    "0.0.0".to_string()
}

fn default_type() -> String {
    "unknown".to_string()
}

fn default_entry() -> String {
    "main.js".to_string()
}

impl Manifest {
    /// manifest.json 路径（插件目录下）。
    pub fn path_in(plugin_dir: &Path) -> PathBuf {
        plugin_dir.join(MANIFEST_FILE)
    }

    /// 从插件目录读取并校验 manifest.json。
    pub fn load(plugin_dir: &Path) -> Result<Manifest, String> {
        let path = Manifest::path_in(plugin_dir);
        let text = std::fs::read_to_string(&path)
            .map_err(|e| format!("读取 {} 失败: {e}", path.display()))?;
        // json5 与宿主 kotlinx 宽松模式对齐（注释、尾逗号、单引号）
        let manifest: Manifest =
            json5::from_str(&text).map_err(|e| format!("manifest.json 解析失败: {e}"))?;
        manifest.validate()?;
        Ok(manifest)
    }

    /// 校验必填字段与格式约束（与宿主 InstallerManager 的校验规则一致）。
    pub fn validate(&self) -> Result<(), String> {
        if !is_valid_plugin_id(&self.id) {
            return Err(format!(
                "非法插件 id: {}（仅允许字母/数字/下划线/连字符，点号分段，最长 64）",
                self.id
            ));
        }
        if !is_valid_entry(&self.entry) {
            return Err(format!("非法入口脚本: {}", self.entry));
        }
        if self.version.trim().is_empty() {
            return Err("version 不能为空".to_string());
        }
        Ok(())
    }

    /// 类型 × 能力合理性校验（规则与宿主 PluginCapabilities.validateForType 一致）：
    /// - 内建能力块与 type 错配 → errors（宿主不会消费错配块，声明只会误导）；
    /// - 横切权限的非常见组合 → warnings（运行时按声明门禁，此处仅软提示）。
    /// 返回 (errors, warnings)，文案面向插件作者。
    pub fn validate_capabilities(&self) -> (Vec<String>, Vec<String>) {
        const BUILTIN_TYPES: [&str; 5] = ["tool", "speech", "emoji", "clipboard_sync", "backup"];
        let mut errors = Vec::new();
        let mut warnings = Vec::new();
        let Some(caps) = self.capabilities.as_ref().and_then(|c| c.as_object()) else {
            return (errors, warnings);
        };
        if !BUILTIN_TYPES.contains(&self.r#type.as_str()) {
            errors.push(format!(
                "未知插件类型: {}（允许: tool/speech/emoji/clipboard_sync/backup）",
                self.r#type
            ));
            return (errors, warnings);
        }
        for key in caps.keys() {
            if BUILTIN_TYPES.contains(&key.as_str()) && key != &self.r#type {
                errors.push(format!(
                    "capabilities.{key} 与插件类型 {} 不匹配（此块仅 {key} 型插件声明），宿主不会消费该块",
                    self.r#type
                ));
            }
        }
        // 横切权限典型适用类型（软校验，与宿主 CROSS_CUTTING_TYPICAL 一致）
        const TYPICAL: [(&str, &[&str]); 3] = [
            ("clipboard_read", &["clipboard_sync", "tool"]),
            ("candidate_transform", &["tool"]),
            ("quick_send_read", &["tool"]),
        ];
        for (cap, types) in TYPICAL {
            let declared = caps.get(cap).and_then(|v| v.as_bool()).unwrap_or(false);
            if declared && !types.contains(&self.r#type.as_str()) {
                warnings.push(format!(
                    "{} 型插件声明了 capabilities.{cap}（典型用于 {} 型），请确认非误报",
                    self.r#type,
                    types.join("/")
                ));
            }
        }
        (errors, warnings)
    }

    /// 生效的平台列表：声明缺省/空白项过滤后为空时回退 [android]。
    pub fn effective_platforms(&self) -> Vec<String> {
        self.platforms
            .as_ref()
            .map(|list| {
                list.iter()
                    .map(|s| s.trim().to_string())
                    .filter(|s| !s.is_empty())
                    .collect::<Vec<_>>()
            })
            .filter(|list| !list.is_empty())
            .unwrap_or_else(|| vec![DEFAULT_PLATFORM.to_string()])
    }

    /// 平台声明校验（软校验：仅在 xipm check 报告，不阻断 build/pack）：
    /// - 显式声明空数组 → error（无意义的声明，多半是笔误）；
    /// - 未知平台标识 / 重复项 → warning（向前兼容未来平台）。
    /// 返回 (errors, warnings)，文案面向插件作者。
    pub fn validate_platforms(&self) -> (Vec<String>, Vec<String>) {
        let mut errors = Vec::new();
        let mut warnings = Vec::new();
        let Some(list) = self.platforms.as_ref() else {
            return (errors, warnings); // 未声明：生效值回退 android，无需提示
        };
        if list.is_empty() {
            errors.push(
                "platforms 为空数组（至少声明一个平台，或缺省视为 [\"android\"]）".to_string(),
            );
            return (errors, warnings);
        }
        let effective = self.effective_platforms();
        let mut seen = std::collections::HashSet::new();
        for p in &effective {
            if !KNOWN_PLATFORMS.contains(&p.as_str()) {
                warnings.push(format!(
                    "未知平台标识: {p}（已知: {}；拼写错误会导致目标宿主无法识别）",
                    KNOWN_PLATFORMS.join("/")
                ));
            }
            if !seen.insert(p) {
                warnings.push(format!("platforms 存在重复项: {p}"));
            }
        }
        (errors, warnings)
    }
}

/// 插件 id：字母/数字/下划线/连字符，点号仅作命名空间分段（禁止空段/首尾点），最长 64。
pub fn is_valid_plugin_id(id: &str) -> bool {
    if id.is_empty() || id.len() > 64 {
        return false;
    }
    if id.starts_with('.') || id.ends_with('.') {
        return false;
    }
    id.split('.').all(|segment| {
        !segment.is_empty()
            && segment
                .chars()
                .all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
    })
}

/// 入口脚本：普通文件名，不允许路径分隔符与 ".."。
pub fn is_valid_entry(name: &str) -> bool {
    !name.is_empty()
        && name.len() <= 128
        && !name.contains('/')
        && !name.contains('\\')
        && !name.contains("..")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plugin_id_validation() {
        assert!(is_valid_plugin_id("kaomoji"));
        assert!(is_valid_plugin_id("com.kingzcheung.xime.plugin.typing_stats"));
        assert!(is_valid_plugin_id("webdav-clipboard_sync"));
        assert!(!is_valid_plugin_id(""));
        assert!(!is_valid_plugin_id("a..b"));
        assert!(!is_valid_plugin_id(".abc"));
        assert!(!is_valid_plugin_id("abc."));
        assert!(!is_valid_plugin_id("a/b"));
        assert!(!is_valid_plugin_id(&"a".repeat(65)));
    }

    #[test]
    fn entry_validation() {
        assert!(is_valid_entry("main.js"));
        assert!(!is_valid_entry(""));
        assert!(!is_valid_entry("libs/main.js"));
        assert!(!is_valid_entry("../main.js"));
    }

    #[test]
    fn parse_manifest_with_comments_and_trailing_commas() {
        let text = r#"
            // 顶层注释
            {
              "id": "demo",
              "name": "示例",
              "version": "1.0.0",
              "entry": "main.js", // 行内注释
              /* 块注释 */
              "type": "tool",
            }
        "#;
        let manifest: Manifest = json5::from_str(text).unwrap();
        assert_eq!(manifest.id, "demo");
        assert_eq!(manifest.version, "1.0.0");
        manifest.validate().unwrap();
    }

    #[test]
    fn defaults_applied() {
        let manifest: Manifest = json5::from_str(r#"{ "id": "mini" }"#).unwrap();
        assert_eq!(manifest.entry, "main.js");
        assert_eq!(manifest.version, "0.0.0");
        assert_eq!(manifest.r#type, "unknown");
    }

    #[test]
    fn unknown_fields_ignored() {
        let manifest: Manifest =
            json5::from_str(r#"{ "id": "a", "futureField": 123, "capabilities": { "events": [] } }"#)
                .unwrap();
        assert_eq!(manifest.id, "a");
    }

    #[test]
    fn capabilities_block_type_mismatch_is_error() {
        let manifest: Manifest = json5::from_str(
            r#"{ "id": "a", "type": "emoji", "capabilities": { "emoji": { "columns": 3 }, "tool": { "display": "direct" } } }"#,
        )
        .unwrap();
        let (errors, warnings) = manifest.validate_capabilities();
        assert_eq!(errors.len(), 1);
        assert!(errors[0].contains("capabilities.tool"));
        assert!(errors[0].contains("emoji"));
        assert!(warnings.is_empty());
    }

    #[test]
    fn cross_cutting_capability_unusual_type_is_warning() {
        let manifest: Manifest = json5::from_str(
            r#"{ "id": "a", "type": "emoji", "capabilities": { "emoji": {}, "clipboard_read": true } }"#,
        )
        .unwrap();
        let (errors, warnings) = manifest.validate_capabilities();
        assert!(errors.is_empty());
        assert_eq!(warnings.len(), 1);
        assert!(warnings[0].contains("clipboard_read"));
    }

    #[test]
    fn typical_capability_combinations_pass_silently() {
        // tool + clipboard_read + candidate_transform + quick_send_read：典型组合零告警
        let manifest: Manifest = json5::from_str(
            r#"{ "id": "a", "type": "tool", "capabilities": { "tool": { "display": "direct" }, "clipboard_read": true, "candidate_transform": true, "quick_send_read": true } }"#,
        )
        .unwrap();
        let (errors, warnings) = manifest.validate_capabilities();
        assert!(errors.is_empty());
        assert!(warnings.is_empty());

        // clipboard_sync + clipboard_read：典型组合零告警
        let manifest: Manifest = json5::from_str(
            r#"{ "id": "b", "type": "clipboard_sync", "capabilities": { "clipboard_sync": { "protocols": ["ximed"] }, "clipboard_read": true } }"#,
        )
        .unwrap();
        let (errors, warnings) = manifest.validate_capabilities();
        assert!(errors.is_empty());
        assert!(warnings.is_empty());
    }

    #[test]
    fn unknown_type_is_error() {
        let manifest: Manifest =
            json5::from_str(r#"{ "id": "a", "type": "widget", "capabilities": {} }"#).unwrap();
        let (errors, _) = manifest.validate_capabilities();
        assert_eq!(errors.len(), 1);
        assert!(errors[0].contains("widget"));
    }

    #[test]
    fn platforms_default_to_android_when_absent() {
        let manifest: Manifest = json5::from_str(r#"{ "id": "a" }"#).unwrap();
        assert_eq!(manifest.effective_platforms(), vec!["android"]);
        let (errors, warnings) = manifest.validate_platforms();
        assert!(errors.is_empty());
        assert!(warnings.is_empty());
    }

    #[test]
    fn platforms_known_values_pass_silently() {
        let manifest: Manifest =
            json5::from_str(r#"{ "id": "a", "platforms": ["android", "ios"] }"#).unwrap();
        assert_eq!(manifest.effective_platforms(), vec!["android", "ios"]);
        let (errors, warnings) = manifest.validate_platforms();
        assert!(errors.is_empty());
        assert!(warnings.is_empty());
    }

    #[test]
    fn platforms_empty_array_is_error() {
        let manifest: Manifest = json5::from_str(r#"{ "id": "a", "platforms": [] }"#).unwrap();
        let (errors, _) = manifest.validate_platforms();
        assert_eq!(errors.len(), 1);
        assert!(errors[0].contains("空数组"));
    }

    #[test]
    fn platforms_unknown_and_duplicate_warn() {
        let manifest: Manifest = json5::from_str(
            r#"{ "id": "a", "platforms": ["android", "android", "harmonyos"] }"#,
        )
        .unwrap();
        let (errors, warnings) = manifest.validate_platforms();
        assert!(errors.is_empty());
        assert_eq!(warnings.len(), 2);
        assert!(warnings.iter().any(|w| w.contains("harmonyos")));
        assert!(warnings.iter().any(|w| w.contains("重复")));
    }

    #[test]
    fn platforms_blank_entries_fall_back_to_android() {
        let manifest: Manifest = json5::from_str(r#"{ "id": "a", "platforms": ["  ", ""] }"#).unwrap();
        assert_eq!(manifest.effective_platforms(), vec!["android"]);
        let (errors, warnings) = manifest.validate_platforms();
        assert!(errors.is_empty());
        assert!(warnings.is_empty());
    }
}
