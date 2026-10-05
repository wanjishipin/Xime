mod build;
mod dev;
mod engine_js;
mod engine_state;
mod manifest;
mod pack;
mod test_engine;

use std::path::{Path, PathBuf};

use clap::{Args, Parser, Subcommand};

/// Xime 输入法插件工具链：TS 编译 / xipk 打包 / 脚手架 / 校验。
///
/// 插件源码为 TypeScript（main.ts + 可选 libs/*.ts 相对 import 内联），
/// 编译产物为 IIFE 单文件 main.js（QuickJS 脚本模式直接执行，无顶层 import/export）。
#[derive(Parser)]
#[command(name = "xipm", version, about, long_about = None)]
struct Cli {
    /// 指定设备序列号（等价 `adb -s <SERIAL>`；多设备/无线调试时使用，可写在子命令前后）
    #[arg(short = 's', long = "device", global = true, value_name = "SERIAL")]
    device: Option<String>,
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// 编译插件：main.ts（多文件 import 内联）→ IIFE 单文件 main.js
    Build(BuildArgs),
    /// 打包：编译 + 生成 <name>-<version>.xipk（可选拷贝到 app assets）
    Pack(PackArgs),
    /// 校验：manifest.json（宽松 JSON）字段与格式检查
    Check(CheckArgs),
    /// 运行插件测试（：main.test.ts → 结果
    Test(TestArgs),
    /// 真机热调试：watch 源码 → 编译打包 → adb 推送 → 广播安装/重载 → 日志跟随
    Dev(DevArgs),
    /// 真机安装：把 xipk（或插件目录，先编译打包）热安装到手机
    Install(InstallArgs),
    /// 真机插件日志：实时跟随（logcat）或历史错误（errors.jsonl）
    Logs(LogsArgs),
    /// 创建插件骨架（main.ts + manifest.json + SDK 类型 + tsconfig + main.test.ts）
    #[command(alias = "new")]
    Init(InitArgs),
}

#[derive(Args)]
struct BuildArgs {
    /// 插件目录（含 main.ts + manifest.json）；缺省为当前目录
    dir: Option<PathBuf>,
    /// 批量构建指定根目录下的所有插件
    #[arg(long)]
    all: bool,
    /// 批量模式：插件根目录
    #[arg(long, default_value = "plugins")]
    plugins_dir: PathBuf,
    /// 输出根目录（产物位于 <out>/<plugin-name>/）
    #[arg(long, default_value = "build/plugin-js")]
    out: PathBuf,
    /// 压缩产物（compress + 局部变量 mangle；不混淆属性名，宿主契约安全）
    #[arg(long)]
    minify: bool,
}

#[derive(Args)]
struct PackArgs {
    /// 插件目录；缺省为当前目录
    dir: Option<PathBuf>,
    /// 批量打包指定根目录下的所有插件
    #[arg(long)]
    all: bool,
    /// 批量模式：插件根目录
    #[arg(long, default_value = "plugins")]
    plugins_dir: PathBuf,
    /// 编译产物根目录（同 build --out）
    #[arg(long, default_value = "build/plugin-js")]
    out: PathBuf,
    /// xipk 输出目录
    #[arg(long, default_value = "build/plugin-release")]
    release_dir: PathBuf,
    /// 不压缩产物（pack 默认压缩：compress + 局部变量 mangle，不混淆属性名契约安全）
    #[arg(long = "no-minify")]
    no_minify: bool,
    /// 打包后拷贝到 app 内置资源目录（debug 内置插件）
    #[arg(long)]
    with_assets: bool,
    /// app 内置资源目录
    #[arg(long, default_value = "app/src/main/assets/plugins")]
    assets_dir: PathBuf,
}

#[derive(Args)]
struct CheckArgs {
    /// 插件目录；缺省为当前目录
    dir: Option<PathBuf>,
    /// 批量校验指定根目录下的所有插件
    #[arg(long)]
    all: bool,
    /// 批量模式：插件根目录
    #[arg(long, default_value = "plugins")]
    plugins_dir: PathBuf,
    /// 编译产物根目录（扩展点一致性检查基于编译产物；同 build --out）
    #[arg(long, default_value = "build/plugin-js")]
    out: PathBuf,
}

#[derive(Args)]
struct InitArgs {
    /// 插件目录名（生成到 <parent>/<name>/）
    name: String,
    /// 插件类型（tool/emoji/speech/clipboard_sync/backup）
    #[arg(long, default_value = "tool")]
    r#type: String,
    /// 生成位置（父目录，默认当前目录）
    #[arg(long, default_value = ".")]
    parent: PathBuf,
}

#[derive(Args)]
struct DevArgs {
    /// 插件目录（含 main.ts + manifest.json）；缺省为当前目录
    dir: Option<PathBuf>,
    /// 应用包名
    #[arg(long, default_value = "com.kingzcheung.xime")]
    package: String,
    /// adb 可执行文件路径（缺省：$ADB / $ANDROID_HOME/platform-tools/adb / PATH）
    #[arg(long)]
    adb: Option<PathBuf>,
    /// 不跟随设备日志（只做热更新循环）
    #[arg(long)]
    no_logs: bool,
    /// 构建产物根目录（xipk 暂存 <out>/dist）
    #[arg(long, default_value = "build/plugin-dev")]
    out: PathBuf,
}

#[derive(Args)]
struct InstallArgs {
    /// 待安装的 xipk 文件，或插件目录（含 manifest.json；会先编译打包）；缺省为当前目录
    target: Option<PathBuf>,
    /// 应用包名
    #[arg(long, default_value = "com.kingzcheung.xime")]
    package: String,
    /// adb 可执行文件路径（缺省：$ADB / $ANDROID_HOME/platform-tools/adb / PATH）
    #[arg(long)]
    adb: Option<PathBuf>,
}

#[derive(Args)]
struct LogsArgs {
    /// 插件目录（读 manifest.id 过滤日志）；缺省为当前目录
    dir: Option<PathBuf>,
    /// 应用包名
    #[arg(long, default_value = "com.kingzcheung.xime")]
    package: String,
    /// adb 可执行文件路径
    #[arg(long)]
    adb: Option<PathBuf>,
    /// 读取历史错误（宿主 errors.jsonl，run-as；需要 debug 包）
    #[arg(long)]
    history: bool,
    /// --history 展示的最新条数
    #[arg(long, default_value_t = 50)]
    lines: usize,
    /// --history 输出 JSON 行（脚本/IDE 集成）
    #[arg(long)]
    json: bool,
}

#[derive(Args)]
struct TestArgs {
    /// 插件目录（含 main.ts + main.test.ts）；缺省为当前目录
    dir: Option<PathBuf>,
    /// 批量测试指定根目录下的所有插件（无测试文件的按冒烟通过计）
    #[arg(long)]
    all: bool,
    /// 批量模式：插件根目录
    #[arg(long, default_value = "plugins")]
    plugins_dir: PathBuf,
    /// 编译产物根目录（同 build --out；测试基于编译产物运行）
    #[arg(long, default_value = "build/plugin-js")]
    out: PathBuf,
    /// 无 main.test.ts 时执行冒烟测试（加载 + 基本槽检查），默认跳过
    #[arg(long)]
    smoke: bool,
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();
    // 全局 -s/--device：dev 与 logs 共用（写在子命令前后均可）
    let device = cli.device;
    match cli.command {
        Commands::Build(args) => run_build(args).await,
        Commands::Pack(args) => run_pack(args).await,
        Commands::Check(args) => run_check(args).await,
        Commands::Test(args) => run_test(args).await,
        Commands::Dev(args) => dev::run_dev(dev::DevArgs {
            dir: args.dir,
            package: args.package,
            adb: args.adb,
            device: device.clone(),
            no_logs: args.no_logs,
            out: args.out,
        })
        .await,
        Commands::Install(args) => run_install(args, device.clone()).await,
        Commands::Logs(args) => dev::run_logs(dev::LogsArgs {
            dir: args.dir,
            package: args.package,
            adb: args.adb,
            device: device.clone(),
            history: args.history,
            lines: args.lines,
            json: args.json,
        })
        .await,
        Commands::Init(args) => run_init(args),
    }
}

/// 操作模式：批量（扫描 plugins 根）或单插件（指定目录/当前目录）。
enum Mode {
    All,
    Single(PathBuf),
}

/// 决定操作模式（三层命令共用）：
/// - `--all` 显式批量
/// - 未指定 DIR 且当前目录不是插件、且 plugins 根存在 → 批量（仓库根零参数即可
///   `xipm build` / `xipm pack` / `xipm check` 全量操作）
/// - 否则单插件（DIR，缺省当前目录）
fn resolve_mode(all: bool, dir: &Option<PathBuf>, plugins_dir: &Path) -> Mode {
    let cwd_is_plugin = Path::new("manifest.json").is_file();
    if all || (dir.is_none() && !cwd_is_plugin && plugins_dir.is_dir()) {
        Mode::All
    } else {
        Mode::Single(dir.clone().unwrap_or_else(|| PathBuf::from(".")))
    }
}

/// 扫描插件根目录（含 manifest.json 的子目录，按名称排序）。
fn scan_plugin_dirs(plugins_dir: &Path) -> anyhow::Result<Vec<PathBuf>> {
    let mut dirs = Vec::new();
    for entry in std::fs::read_dir(plugins_dir).map_err(|e| {
        anyhow::anyhow!("读取插件根目录失败 {}: {e}", plugins_dir.display())
    })? {
        let dir = entry?.path();
        if dir.is_dir() && manifest::Manifest::path_in(&dir).is_file() {
            dirs.push(dir);
        }
    }
    dirs.sort();
    Ok(dirs)
}

async fn run_build(args: BuildArgs) -> anyhow::Result<()> {
    match resolve_mode(args.all, &args.dir, &args.plugins_dir) {
        Mode::All => {
            let outcomes = build::build_all(&args.plugins_dir, &args.out, args.minify).await?;
            if outcomes.is_empty() {
                anyhow::bail!("未在 {} 下找到插件（需含 manifest.json）", args.plugins_dir.display());
            }
            for outcome in &outcomes {
                println!(
                    "✓ 编译 {} ({}) → {}",
                    outcome.manifest.id,
                    outcome.manifest.version,
                    outcome.out_dir.display()
                );
            }
            println!("共 {} 个插件", outcomes.len());
        }
        Mode::Single(dir) => {
            let outcome = build::build_plugin(&dir, &args.out, args.minify).await?;
            println!(
                "✓ 编译 {} ({}) → {}",
                outcome.manifest.id,
                outcome.manifest.version,
                outcome.out_dir.display()
            );
        }
    }
    println!();
    println!("提示：build 只编译到 {}（供测试/开发加载）；", args.out.display());
    println!("      生成 xipk 安装包运行 `xipm pack`（仓库根零参数即批量打包）");
    Ok(())
}

async fn run_pack(args: PackArgs) -> anyhow::Result<()> {
    let plugin_dirs = match resolve_mode(args.all, &args.dir, &args.plugins_dir) {
        Mode::All => scan_plugin_dirs(&args.plugins_dir)?,
        Mode::Single(dir) => vec![dir],
    };

    if plugin_dirs.is_empty() {
        anyhow::bail!("未找到插件目录（{} 下需含 manifest.json）", args.plugins_dir.display());
    }

    // pack 默认压缩分发产物（build 默认不压缩，便于测试/调试）
    let minify = !args.no_minify;
    for dir in &plugin_dirs {
        let outcome = build::build_plugin(dir, &args.out, minify).await?;
        let xipk = pack::pack_plugin(&outcome.out_dir, &outcome.manifest, &args.release_dir)?;
        println!(
            "✓ 打包 {} ({}) → {}",
            outcome.manifest.id,
            outcome.manifest.version,
            xipk.display()
        );
        if args.with_assets {
            let target = pack::copy_to_assets(&xipk, &args.assets_dir)?;
            println!("  ↳ 已拷贝内置资源 {}", target.display());
        }
    }
    println!();
    println!("共 {} 个 xipk → {}", plugin_dirs.len(), args.release_dir.display());
    Ok(())
}

/// install 的输入：xipk 文件直接安装；插件目录先编译打包（与 `xipm pack` 同布局）。
#[derive(Debug)]
enum InstallTarget {
    Xipk(PathBuf),
    PluginDir(PathBuf),
}

/// 判定 install 目标：目录须含 manifest.json，文件须为 .xipk。
fn classify_install_target(target: &Path) -> anyhow::Result<InstallTarget> {
    if target.is_dir() {
        if !manifest::Manifest::path_in(target).is_file() {
            anyhow::bail!("插件目录缺少 manifest.json: {}", target.display());
        }
        return Ok(InstallTarget::PluginDir(target.to_path_buf()));
    }
    if !target.exists() {
        anyhow::bail!("路径不存在: {}", target.display());
    }
    if target.extension().and_then(|e| e.to_str()) == Some("xipk") {
        Ok(InstallTarget::Xipk(target.to_path_buf()))
    } else {
        anyhow::bail!(
            "install 只接受 .xipk 文件或插件目录（含 manifest.json）: {}",
            target.display()
        )
    }
}

async fn run_install(args: InstallArgs, device: Option<String>) -> anyhow::Result<()> {
    let target = args.target.unwrap_or_else(|| PathBuf::from("."));
    // xipk 直接安装；插件目录先编译打包（默认布局与 `xipm build` / `xipm pack` 一致）
    let (xipk, label, log_hint) = match classify_install_target(&target)? {
        InstallTarget::Xipk(path) => {
            let label = path
                .file_stem()
                .map(|s| s.to_string_lossy().into_owned())
                .unwrap_or_else(|| "plugin".into());
            (path, label, "<插件目录>".to_string())
        }
        InstallTarget::PluginDir(dir) => {
            let outcome = build::build_plugin(&dir, Path::new("build/plugin-js"), false).await?;
            let xipk = pack::pack_plugin(
                &outcome.out_dir,
                &outcome.manifest,
                Path::new("build/plugin-release"),
            )?;
            println!(
                "✓ 打包 {} ({}) → {}",
                outcome.manifest.id,
                outcome.manifest.version,
                xipk.display()
            );
            (xipk, outcome.manifest.id, dir.display().to_string())
        }
    };

    let adb = dev::Adb::detect(args.adb.clone())?.with_serial(device);
    adb.ensure_device()?;
    let channel = dev::detect_channel(&adb, &args.package);
    println!(
        "▶ 安装 {} → 设备 {}",
        xipk.display(),
        adb.serial().unwrap_or_else(|| "默认设备".into())
    );
    let remote_name = format!(
        "{}.xipk",
        xipk.file_stem()
            .map(|s| s.to_string_lossy().into_owned())
            .unwrap_or_else(|| "plugin".into())
    );
    dev::install_xipk(&adb, &args.package, channel, &xipk, &remote_name, &label, &log_hint).await
}

async fn run_check(args: CheckArgs) -> anyhow::Result<()> {
    let plugin_dirs = match resolve_mode(args.all, &args.dir, &args.plugins_dir) {
        Mode::All => scan_plugin_dirs(&args.plugins_dir)?,
        Mode::Single(dir) => vec![dir],
    };

    let mut failed = 0;
    for dir in plugin_dirs {
        match manifest::Manifest::load(&dir) {
            Ok(manifest) => {
                println!(
                    "✓ {} ({}) type={} entry={}",
                    manifest.id, manifest.version, manifest.r#type, manifest.entry
                );
                // 类型 × 能力合理性校验：内建块错配按失败计，可疑组合仅提示
                let (cap_errors, cap_warnings) = manifest.validate_capabilities();
                for e in &cap_errors {
                    eprintln!("  ✗ {e}");
                }
                for w in &cap_warnings {
                    println!("  ⚠ {w}");
                }
                failed += cap_errors.len();
                // 平台声明校验：空数组按失败计，未知标识/重复项仅提示
                let (plat_errors, plat_warnings) = manifest.validate_platforms();
                for e in &plat_errors {
                    eprintln!("  ✗ {e}");
                }
                for w in &plat_warnings {
                    println!("  ⚠ {w}");
                }
                failed += plat_errors.len();

                // 扩展点一致性校验（需编译产物；缺失则跳过并提示，先运行 xipm build）
                match find_built_main_js(&dir, &args.out) {
                    None => println!("  ! 未找到编译产物 main.js，跳过扩展点检查（先运行 xipm build）"),
                    Some(path) => {
                        let main_js = std::fs::read_to_string(&path)?;
                        let inspect = tokio::time::timeout(
                            test_engine::TEST_TIMEOUT,
                            test_engine::inspect_extensions(
                                main_js,
                                &manifest.id,
                                path.parent().map(|p| p.join("resources")).unwrap_or_default(),
                            ),
                        )
                        .await;
                        match inspect {
                            Err(_) => {
                                eprintln!("  ✗ 扩展点检查超时（{}）", path.display());
                                failed += 1;
                            }
                            Ok(Err(e)) => {
                                eprintln!("  ✗ 产物加载失败（{}）: {e}", path.display());
                                failed += 1;
                            }
                            Ok(Ok(declared)) => {
                                let (ext_errors, ext_warnings) =
                                    test_engine::validate_extensions(&declared, &manifest.r#type);
                                for e in &ext_errors {
                                    eprintln!("  ✗ {e}");
                                    failed += 1;
                                }
                                for w in &ext_warnings {
                                    println!("  ⚠ {w}");
                                }
                            }
                        }
                    }
                }

                // TS 源码存在性提示（非强制：纯 JS 插件可无 main.ts）
                if !dir.join("main.ts").is_file() && !dir.join("main.js").is_file() {
                    println!("  ! 缺少入口源码 main.ts（或 main.js）");
                    failed += 1;
                }
            }
            Err(e) => {
                eprintln!("✗ {}: {e}", dir.display());
                failed += 1;
            }
        }
    }
    if failed > 0 {
        anyhow::bail!("{failed} 个插件校验失败");
    }
    Ok(())
}

/// 定位插件的编译产物 main.js：插件目录直置（纯 JS 插件）优先，
/// 其次 build 输出根下的同名目录（xipm build 的默认布局）。
fn find_built_main_js(dir: &Path, out_root: &Path) -> Option<PathBuf> {
    let direct = dir.join("main.js");
    if direct.is_file() {
        return Some(direct);
    }
    let name = dir.file_name()?;
    let built = out_root.join(name).join("main.js");
    if built.is_file() {
        return Some(built);
    }
    None
}

async fn run_test(args: TestArgs) -> anyhow::Result<()> {
    let plugin_dirs = match resolve_mode(args.all, &args.dir, &args.plugins_dir) {
        Mode::All => scan_plugin_dirs(&args.plugins_dir)?,
        Mode::Single(dir) => vec![dir],
    };
    if plugin_dirs.is_empty() {
        anyhow::bail!("未找到插件目录（{} 下需含 manifest.json）", args.plugins_dir.display());
    }

    let mut passed_total = 0usize;
    let mut failed_total = 0usize;
    for dir in &plugin_dirs {
        println!("▶ {}", dir.display());
        let outcome = build::build_plugin(dir, &args.out, false).await?;
        let dir = dir.clone();
        let result = tokio::time::timeout(
            test_engine::TEST_TIMEOUT,
            test_engine::run_plugin_tests(&dir, &outcome.out_dir, &outcome.manifest, args.smoke),
        )
        .await;

        match result {
            Ok(Ok(Some(cases))) => {
                if cases.is_empty() {
                    println!("  {} 冒烟：加载成功（无测试用例）", engine_js::green("✓"));
                } else {
                    let (p, f) = test_engine::print_results(&cases);
                    passed_total += p;
                    failed_total += f;
                }
            }
            Ok(Ok(None)) => {
                println!("  （无 main.test.ts，已跳过；--smoke 可启用加载冒烟）");
            }
            Ok(Err(e)) => {
                failed_total += 1;
                eprintln!("  {} 测试执行失败: {e:#}", engine_js::red("✗"));
            }
            Err(_) => {
                failed_total += 1;
                eprintln!("  {} 测试超时（>{}s，疑似死循环或挂起）",
                    engine_js::red("✗"), test_engine::TEST_TIMEOUT.as_secs());
            }
        }
        println!();
    }

    if failed_total > 0 {
        anyhow::bail!("{} 个插件测试失败", failed_total);
    }
    println!("{} 全部用例通过（{passed_total} 个）", engine_js::green("✓"));
    Ok(())
}

fn run_init(args: InitArgs) -> anyhow::Result<()> {
    let target = args.parent.join(&args.name);
    if target.exists() {
        anyhow::bail!("目标目录已存在: {}", target.display());
    }
    std::fs::create_dir_all(&target)?;
    std::fs::create_dir_all(target.join("resources"))?;

    let package_name = args.name.trim_start_matches('-');
    let manifest_text = include_str!("../templates/manifest.json")
        .replace("{{PACKAGE}}", package_name)
        .replace("{{NAME}}", package_name)
        .replace("{{TYPE}}", &args.r#type);
    std::fs::write(target.join("manifest.json"), manifest_text)?;
    std::fs::write(target.join("main.ts"), include_str!("../templates/main.ts"))?;
    std::fs::write(target.join("main.test.ts"), include_str!("../templates/main.test.ts"))?;
    std::fs::write(
        target.join("xime-plugin.d.ts"),
        include_str!("../templates/xime-plugin.d.ts"),
    )?;
    std::fs::write(target.join("tsconfig.json"), include_str!("../templates/tsconfig.json"))?;
    std::fs::write(target.join(".gitignore"), "dist/\n")?;

    println!("✓ 已创建插件骨架 {}", target.display());
    println!("  下一步：xipm test .（与 xipm build --out dist");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 全局 `-s/--device`：子命令前后均可写（与 `adb -s` 一致），缺省为 None。
    #[test]
    fn device_flag_is_global_and_position_independent() {
        let before = Cli::try_parse_from(["xipm", "-s", "SERIAL-1", "dev"]).unwrap();
        assert_eq!(before.device.as_deref(), Some("SERIAL-1"));

        let after = Cli::try_parse_from(["xipm", "dev", "-s", "SERIAL-2"]).unwrap();
        assert_eq!(after.device.as_deref(), Some("SERIAL-2"));

        let long = Cli::try_parse_from(["xipm", "logs", "--device", "SERIAL-3"]).unwrap();
        assert_eq!(long.device.as_deref(), Some("SERIAL-3"));

        let unset = Cli::try_parse_from(["xipm", "dev"]).unwrap();
        assert_eq!(unset.device, None);
    }

    /// `-s` 是全局参数：非 adb 子命令也能解析（与 adb 的全局 `-s` 语义一致）。
    #[test]
    fn device_flag_is_accepted_by_other_subcommands() {
        let cli = Cli::try_parse_from(["xipm", "-s", "SERIAL", "check"]).unwrap();
        assert_eq!(cli.device.as_deref(), Some("SERIAL"));
        assert!(matches!(cli.command, Commands::Check(_)));
    }

    /// install 目标判定：.xipk 文件 / 插件目录（须含 manifest.json），其余输入明确报错。
    #[test]
    fn classify_install_target_accepts_xipk_and_plugin_dir() {
        let root = std::env::temp_dir().join(format!("xipm-install-classify-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);

        let plugin = root.join("demo-plugin");
        std::fs::create_dir_all(&plugin).unwrap();
        std::fs::write(plugin.join("manifest.json"), "{}").unwrap();
        let xipk = root.join("demo-plugin-1.0.0.xipk");
        std::fs::write(&xipk, b"xipk").unwrap();
        let bare_dir = root.join("bare");
        std::fs::create_dir_all(&bare_dir).unwrap();
        let other = root.join("notes.txt");
        std::fs::write(&other, b"x").unwrap();

        assert!(matches!(
            classify_install_target(&xipk).unwrap(),
            InstallTarget::Xipk(_)
        ));
        assert!(matches!(
            classify_install_target(&plugin).unwrap(),
            InstallTarget::PluginDir(_)
        ));

        let err = classify_install_target(&bare_dir).unwrap_err().to_string();
        assert!(err.contains("manifest.json"), "应提示缺 manifest.json: {err}");
        assert!(classify_install_target(&other).is_err(), "非 xipk 文件应报错");
        assert!(
            classify_install_target(&root.join("missing.xipk")).is_err(),
            "不存在的路径应报错"
        );

        let _ = std::fs::remove_dir_all(&root);
    }
}
