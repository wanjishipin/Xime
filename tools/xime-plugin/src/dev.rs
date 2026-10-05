// 真机开发支持（xipm dev / xipm logs）。
//
// dev：watch 插件源码 → 编译 + 打包 xipk → adb 推送 → 热安装/重载 → 跟随日志。
//      双通道（自动探测）：
//        debug 包（run-as 可用）  = 管道写入应用内部目录 + jsonl 落盘回执 + 错误落盘跟随
//        release 包（run-as 不可用）= 推 /data/local/tmp + logcat(XipmDev) 回执解析；
//          需宿主在 设置→关于 连点设备信息 7 次解锁并开启"插件开发模式"
// logs：实时跟随插件日志（logcat: JsPlugin / PluginErrorLog tag）；
//       --history 拉取宿主错误落盘文件 errors.jsonl（run-as，仅 debug 包）。
//
// 依赖：adb（PATH / ANDROID_HOME / ANDROID_SDK_ROOT / --adb），USB 或无线调试均可。
// 宿主侧要求：DevPluginInstallActivity（app/src/main，exported=true，开发模式开关门禁）。

use std::cell::RefCell;
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant, SystemTime};

use anyhow::{Context, Result};

use crate::engine_js as color;
use crate::manifest::Manifest;

/// debug 通道：应用内部私有目录下的热更新暂存目录（run-as 写入）。
const DEV_DIR: &str = "xipm-dev";

/// release 兼容通道：/data/local/tmp 下的暂存目录（shell 可写、应用可读，
/// 安装完成后由宿主侧删除源文件）。
const COMPAT_DEV_DIR: &str = "/data/local/tmp/xipm-dev";

/// 热安装组件（app/src/main 的 DevPluginInstallActivity：透明无界面、
/// `am start` 由 shell 特权发起，不受应用后台执行限制；开发模式开关门禁）。
const INSTALL_ACTIVITY_SUFFIX: &str = "plugin.DevPluginInstallActivity";

/// 推送/回执通道（`xipm dev` / `xipm install` 按 run-as 可用性自动探测）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DevChannel {
    /// debug 包：run-as 内部目录 + jsonl 回执 + 错误落盘跟随（原全链路）
    Debug,
    /// release 包：/data/local/tmp 推送 + logcat(XipmDev) 回执；无错误落盘跟随
    ReleaseCompat,
}

/// 探测热安装通道：`run-as` 仅对 debuggable 包有效，release 包自动走兼容通道。
pub fn detect_channel(adb: &Adb, package: &str) -> DevChannel {
    if adb.run_as(package, &["true"]).is_ok() {
        DevChannel::Debug
    } else {
        DevChannel::ReleaseCompat
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// adb 封装
// ─────────────────────────────────────────────────────────────────────────────

#[derive(Clone)]
pub struct Adb {
    exe: PathBuf,
    /// 目标设备序列号。`-s/--device` 显式指定时保持不变；未指定时由 [Adb::ensure_device]
    /// 从 `adb devices` 里挑出唯一的在线设备并**记住**（RefCell 使其在 `&self` 上就地解析）。
    serial: RefCell<Option<String>>,
}

impl Adb {
    /// 定位 adb：--adb > $ADB > $ANDROID_HOME/platform-tools > $ANDROID_SDK_ROOT/... > PATH
    pub fn detect(explicit: Option<PathBuf>) -> Result<Self> {
        // Windows 上 SDK 目录里的可执行文件带 .exe 后缀；Command::new 对含路径分隔符的
        // 程序名不自动补后缀，缺这个会误报"adb 不可用"（PATH 兜底的裸 "adb" 无此问题）
        let bin = if cfg!(windows) { "adb.exe" } else { "adb" };
        let exe = explicit
            .or_else(|| std::env::var_os("ADB").map(PathBuf::from))
            .or_else(|| {
                std::env::var_os("ANDROID_HOME")
                    .map(|h| PathBuf::from(h).join("platform-tools").join(bin))
            })
            .or_else(|| {
                std::env::var_os("ANDROID_SDK_ROOT")
                    .map(|h| PathBuf::from(h).join("platform-tools").join(bin))
            })
            .unwrap_or_else(|| PathBuf::from("adb"));
        let adb = Self { exe, serial: RefCell::new(None) };
        adb.run(&["version"])
            .with_context(|| format!("adb 不可用（{}）。请安装 Android SDK Platform-Tools 或指定 --adb", adb.exe.display()))?;
        Ok(adb)
    }

    pub fn with_serial(mut self, serial: Option<String>) -> Self {
        self.serial = RefCell::new(serial);
        self
    }

    /// 当前目标设备序列号（[Adb::ensure_device] 之后必定有值）。
    pub fn serial(&self) -> Option<String> {
        self.serial.borrow().clone()
    }

    fn cmd(&self) -> Command {
        let mut cmd = Command::new(&self.exe);
        if let Some(s) = self.serial.borrow().as_ref() {
            cmd.arg("-s").arg(s);
        }
        cmd
    }

    fn run(&self, args: &[&str]) -> Result<String> {
        let out = self
            .cmd()
            .args(args)
            .output()
            .with_context(|| format!("执行 adb {} 失败", args.join(" ")))?;
        if !out.status.success() {
            anyhow::bail!(
                "adb {} 失败: {}",
                args.join(" "),
                String::from_utf8_lossy(&out.stderr).trim()
            );
        }
        Ok(String::from_utf8_lossy(&out.stdout).into_owned())
    }

    /// 设备在线检查；未显式指定 `-s/--device` 时**自动采用唯一的在线设备**并固定下来。
    ///
    /// 不再直接依赖裸 `adb get-state`：无线调试（`adb-tls-connect`）下这个"默认设备"查询
    /// 经常返回空串，而且设备列表里只要残留一条 offline transport（重复 `adb connect` 的
    /// 常见后果）就必然失败——用户看到的是"未检测到在线设备（adb get-state = ""）"或
    /// 后续命令报 "more than one device/emulator"。这里改为解析 `adb devices`，把选中的
    /// 序列号记住，后续所有 adb 调用都带 `-s`，从根上避开默认设备解析。
    pub fn ensure_device(&self) -> Result<()> {
        if self.serial.borrow().is_none() {
            let serial = self.detect_single_online_device()?;
            *self.serial.borrow_mut() = Some(serial);
        }
        let state = self.run(&["get-state"]).unwrap_or_default();
        if state.trim() != "device" {
            let serial = self.serial.borrow().clone().unwrap_or_default();
            anyhow::bail!(
                "设备 {serial} 不在线（adb get-state = {:?}）。\n\
                 提示：无线调试断流时可 `adb disconnect {serial}` 后重新 connect",
                state.trim()
            );
        }
        Ok(())
    }

    /// 解析 `adb devices`：唯一在线 → 采用；多台在线 → 列出序列号要求 `-s/--device`；
    /// 无在线 → 给可操作提示（offline / unauthorized 分别说明怎么处理）。
    fn detect_single_online_device(&self) -> Result<String> {
        let out = self.run(&["devices"])?;
        let (online, offline, unauthorized) = classify_devices(&out);
        if online.len() == 1 {
            return Ok(online[0].clone());
        }
        if online.len() > 1 {
            anyhow::bail!(
                "检测到多台在线设备：{}\n请用 -s/--device <序列号> 指定（无线调试常见于 \
                 `adb connect` 与 mDNS 各注册一条同设备 transport）",
                online.join(", ")
            );
        }
        let mut msg = String::from("未检测到在线设备。\n");
        if !offline.is_empty() {
            msg.push_str(&format!(
                "（offline transport：{} —— 执行 `adb disconnect <序列号>` 后重连）\n",
                offline.join(", ")
            ));
        }
        if !unauthorized.is_empty() {
            msg.push_str(&format!(
                "（未授权：{} —— 请在手机上确认 USB 调试授权弹窗）\n",
                unauthorized.join(", ")
            ));
        }
        msg.push_str("提示：USB 调试请确认已授权；无线调试可用：\n  adb pair <ip:port> && adb connect <ip:port>");
        anyhow::bail!(msg)
    }

    pub fn shell(&self, args: &[&str]) -> Result<String> {
        let mut full = vec!["shell"];
        full.extend_from_slice(args);
        self.run(&full)
    }

    /// 以 app uid 执行（debug 包 run-as），返回 stdout。
    pub fn run_as(&self, package: &str, args: &[&str]) -> Result<String> {
        let mut full = vec!["run-as", package];
        full.extend_from_slice(args);
        self.shell(&full)
    }

    /// 写入 app 内部目录（绕开 /sdcard Android/data 在部分 ROM 上的访问限制）。
    ///
    /// 走 `adb push` 到 /data/local/tmp 后再 `run-as cat` 拷进内部目录，**不再把二进制包
    /// 灌进 `adb shell` 的 stdin**：实测（platform-tools 37.0.0 / 无线调试 / Android 15）
    /// `adb shell -T "run-as … sh -c 'cat > …'" < x.xipk` 只落地前 646 字节
    /// （5677 → 646），宿主解析即报 `zip END header not found`。中转方式与 [push_compat] 同源。
    /// 返回设备上的内部绝对路径（供广播 extra 使用）。
    pub fn push_to_internal(&self, package: &str, name: &str, local: &Path) -> Result<String> {
        let tmp = format!("{COMPAT_DEV_DIR}/{name}");
        let expect = std::fs::metadata(local).map(|m| m.len()).unwrap_or(0);
        self.shell(&["mkdir", "-p", COMPAT_DEV_DIR])?;
        self.run(&["push", local.to_string_lossy().as_ref(), tmp.as_str()])
            .with_context(|| format!("adb push 到 {tmp} 失败"))?;
        // 应用可读的最低权限；拷完立即删除中转副本，缩短其他应用可读窗口
        self.shell(&["chmod", "644", &tmp])?;
        // 整条命令作为一个参数下发：重定向必须在内层 `sh -c` 的引号里，
        // 否则会被外层 shell（cwd=/，无写权限）先解析而报 "No such file or directory"
        let remote_cmd = format!("run-as {package} sh -c 'cat {tmp} > files/{DEV_DIR}/{name}'");
        self.shell(&[remote_cmd.as_str()])?;
        let _ = self.shell(&["rm", "-f", &tmp]);
        // 校验落地字节数：宁可在这里明确失败，也别让宿主拿着半个 zip 报"配置解析失败"
        let listed = self.run_as(package, &["ls", "-l", &format!("files/{DEV_DIR}/{name}")])?;
        if let Some(actual) = listed.split_whitespace().nth(4).and_then(|v| v.parse::<u64>().ok()) {
            if expect != 0 && actual != expect {
                anyhow::bail!(
                    "插件包落地字节数不符（本地 {expect} / 设备 {actual} bytes）：\
                     设备内部目录写入失败，请重试或改用 -s/--device 指定设备/重新插拔 adb"
                );
            }
        }
        Ok(format!("/data/user/0/{package}/files/{DEV_DIR}/{name}"))
    }

    /// release 兼容通道推送：/data/local/tmp（shell 可写、应用可读；宿主装后删除）。
    pub fn push_compat(&self, name: &str, local: &Path) -> Result<String> {
        self.shell(&["mkdir", "-p", COMPAT_DEV_DIR])?;
        let remote = format!("{COMPAT_DEV_DIR}/{name}");
        self.run(&["push", local.to_string_lossy().as_ref(), remote.as_str()])
            .with_context(|| format!("adb push 到 {remote} 失败"))?;
        // 应用可读的最低权限；装完宿主侧会删除，缩短其他应用可读窗口
        self.shell(&["chmod", "644", &remote])?;
        Ok(remote)
    }

    /// 触发热安装（`am start` 无界面 Activity；path 指向设备上的 xipk）。
    pub fn start_install(&self, package: &str, remote_xipk: &str) -> Result<()> {
        let component = format!("{package}/{package}.{INSTALL_ACTIVITY_SUFFIX}");
        let out = self.run(&[
            "shell",
            "am",
            "start",
            "-n",
            &component,
            "--es",
            "path",
            remote_xipk,
        ])?;
        if out.contains("Error") || out.contains("does not exist") {
            anyhow::bail!(
                "设备上的 {package} 不包含插件热安装组件，或开发模式未开启。\n\
                 release 包：设置 → 关于 → 连点设备信息 7 次解锁 → 开启\"插件开发模式\"；\n\
                 debug 包：请先执行：./gradlew installDebug\n（am start: {}）",
                out.trim()
            );
        }
        Ok(())
    }

    /// 读取应用私有文件（debug 包 run-as）。
    pub fn exec_out(&self, args: &[&str]) -> Result<Vec<u8>> {
        let out = self
            .cmd()
            .arg("exec-out")
            .args(args)
            .output()
            .with_context(|| format!("执行 adb exec-out {} 失败", args.join(" ")))?;
        if !out.status.success() {
            anyhow::bail!(
                "adb exec-out {} 失败: {}",
                args.join(" "),
                String::from_utf8_lossy(&out.stderr).trim()
            );
        }
        Ok(out.stdout)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// 日志跟随（logcat）
// ─────────────────────────────────────────────────────────────────────────────

/// 启动 logcat 跟随线程（过滤插件相关 tag），返回子进程句柄（用于退出清理）。
///
/// debug 通道：JsPlugin（console）与 PluginErrorLog（错误）由落盘通道负责，logcat
/// 侧排除以免重复；release 通道无落盘跟随，必须保留这两类 tag 才能看到插件日志。
fn spawn_logcat(adb: &Adb, plugin_id: &str, channel: DevChannel) -> Result<Child> {
    let mut child = adb
        .cmd()
        .arg("logcat")
        .arg("-v")
        .arg("time")
        .arg("-T") // 从"最新 1 行"开始，不回放历史缓冲
        .arg("1")
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .context("启动 adb logcat 失败")?;
    let stdout = child.stdout.take().expect("logcat stdout");
    let id = plugin_id.to_string();
    let file_channel = channel == DevChannel::Debug;
    std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(std::result::Result::ok) {
            if !line.contains(&id) {
                continue;
            }
            // 落盘通道可读时，JsPlugin/PluginErrorLog 已由文件跟随器输出，避免重复
            if file_channel && (line.contains("JsPlugin") || line.contains("PluginErrorLog")) {
                continue;
            }
            print_log_line(&line);
        }
    });
    Ok(child)
}

fn print_log_line(line: &str) {
    // `logcat -v time` 优先级字段形如 ` E/TAG`、` W/TAG`（单字母 + 斜杠）
    let out = if line.contains(" E/") {
        color::red(line)
    } else if line.contains(" W/") {
        color::yellow(line)
    } else {
        line.to_string()
    };
    println!("{out}");
}

// ─────────────────────────────────────────────────────────────────────────────
// 源码变更指纹（watch）
// ─────────────────────────────────────────────────────────────────────────────

/// 汇总插件目录（main.ts/manifest.json/resources/** 等）的 mtime+size 指纹。
/// 跳过构建产物与依赖目录。
fn fingerprint(dir: &Path) -> u64 {
    use std::hash::{Hash, Hasher};
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    let walker = walkdir::WalkDir::new(dir).into_iter().filter_entry(|e| {
        let name = e.file_name().to_string_lossy();
        !matches!(name.as_ref(), "node_modules" | ".git" | "build" | "dist" | ".idea")
    });
    for entry in walker.flatten() {
        if !entry.file_type().is_file() {
            continue;
        }
        entry.path().hash(&mut hasher);
        if let Ok(meta) = entry.metadata() {
            meta.len().hash(&mut hasher);
            if let Ok(mtime) = meta.modified() {
                let nanos = mtime
                    .duration_since(SystemTime::UNIX_EPOCH)
                    .map(|d| d.as_nanos())
                    .unwrap_or(0);
                nanos.hash(&mut hasher);
            }
        }
    }
    hasher.finish()
}

// ─────────────────────────────────────────────────────────────────────────────
// xipm dev
// ─────────────────────────────────────────────────────────────────────────────

pub struct DevArgs {
    pub dir: Option<PathBuf>,
    pub package: String,
    pub adb: Option<PathBuf>,
    pub device: Option<String>,
    pub no_logs: bool,
    pub out: PathBuf,
}

pub async fn run_dev(args: DevArgs) -> Result<()> {
    let dir = args
        .dir
        .clone()
        .unwrap_or_else(|| PathBuf::from("."))
        .canonicalize()
        .map_err(|e| anyhow::anyhow!("插件目录不存在: {e}"))?;
    let manifest = Manifest::load(&dir).map_err(|e| anyhow::anyhow!("{e}"))?;
    let adb = Adb::detect(args.adb.clone())?.with_serial(args.device.clone());
    adb.ensure_device()?;

    // 通道探测：run-as 仅对 debuggable 包有效；release 包自动走兼容通道
    let channel = detect_channel(&adb, &args.package);
    match channel {
        DevChannel::Debug => {
            adb.shell(&["run-as", &args.package, "mkdir", "-p", &format!("files/{DEV_DIR}")])?;
        }
        DevChannel::ReleaseCompat => {
            println!("  release 通道：run-as 不可用，改走 /data/local/tmp + logcat 回执");
            println!("  若安装失败：宿主 设置 → 关于 → 连点设备信息 7 次解锁 → 开启\"插件开发模式\"");
        }
    }
    let plugin_name = dir
        .file_name()
        .map(|s| s.to_string_lossy().into_owned())
        .unwrap_or_else(|| "plugin".into());

    println!("▶ 插件热更新：{} ({})", manifest.id, manifest.version);

    deploy(&dir, &args.out, &adb, &args.package, &plugin_name, channel).await?;

    // 插件错误实时跟随（errors.jsonl 增量轮询：不依赖 ROM 限流的 logcat；
    // 仅 debug 通道——release 无 run-as 读不了落盘文件）
    let error_follower = if channel == DevChannel::Debug {
        Some(spawn_error_follower(
            adb.clone(),
            args.package.clone(),
            manifest.id.clone(),
        ))
    } else {
        println!("  release 通道：无错误落盘跟随，仅 logcat 实时日志（--history 亦不可用）");
        None
    };
    let mut logcat = if args.no_logs {
        None
    } else {
        println!("  日志跟随中（console/错误落盘通道 + 宿主日志；Ctrl-C 退出）");
        Some(spawn_logcat(&adb, &manifest.id, channel)?)
    };

    let mut last = fingerprint(&dir);
    loop {
        tokio::select! {
            _ = tokio::signal::ctrl_c() => {
                println!("\n已退出（设备上仍保留最后一次安装）");
                break;
            }
            _ = tokio::time::sleep(Duration::from_millis(500)) => {
                let current = fingerprint(&dir);
                if current != last {
                    // 防抖：编辑器保存常触发多次事件
                    tokio::time::sleep(Duration::from_millis(250)).await;
                    last = fingerprint(&dir);
                    let started = Instant::now();
                    match deploy(&dir, &args.out, &adb, &args.package, &plugin_name, channel).await {
                        Ok(()) => println!("↻ 热更新完成（{:.0}ms）", started.elapsed().as_millis()),
                        Err(e) => eprintln!("{} 热更新失败: {e:#}", color::red("✗")),
                    }
                }
            }
        }
    }
    if let Some(child) = &mut logcat {
        let _ = child.kill();
    }
    if let Some(follower) = error_follower {
        follower.stop();
    }
    Ok(())
}

/// 编译 → 打包 → 热安装（[install_xipk]）。
/// （dev 用未压缩产物，便于真机排查）
async fn deploy(
    dir: &Path,
    out_root: &Path,
    adb: &Adb,
    package: &str,
    plugin_name: &str,
    channel: DevChannel,
) -> Result<()> {
    let outcome = crate::build::build_plugin(dir, out_root, false).await?;
    let xipk = crate::pack::pack_plugin(
        &outcome.out_dir,
        &outcome.manifest,
        &out_root.join("dist"),
    )?;
    install_xipk(
        adb,
        package,
        channel,
        &xipk,
        &format!("{plugin_name}.xipk"),
        &outcome.manifest.id,
        &dir.display().to_string(),
    )
    .await
}

/// 推送 xipk → `am start` 热安装 → 等待设备回执（最长 10s）。
///
/// - `remote_name`：设备上的文件名（宿主只认 `path` extra，文件名无协议含义）
/// - `label`：展示用的插件标识（回执未带插件 id 时的兜底）
/// - `log_hint`：超时提示里 `xipm logs <log_hint>` 的取值（插件目录）
pub async fn install_xipk(
    adb: &Adb,
    package: &str,
    channel: DevChannel,
    xipk: &Path,
    remote_name: &str,
    label: &str,
    log_hint: &str,
) -> Result<()> {
    let fetch = |adb: &Adb, package: &str| match channel {
        DevChannel::Debug => fetch_results(adb, package),
        DevChannel::ReleaseCompat => fetch_results_compat(adb),
    };
    let before = fetch(adb, package).map(|v| v.len()).unwrap_or(0);
    let remote_path = match channel {
        DevChannel::Debug => adb.push_to_internal(package, remote_name, xipk)?,
        DevChannel::ReleaseCompat => adb.push_compat(remote_name, xipk)?,
    };
    adb.start_install(package, &remote_path)?;

    // 等待设备回执（debug：jsonl 落盘；release：logcat XipmDev dump——
    // 部分 ROM 限流后台持续日志，但前台 dump 拿得到安装打点）
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        tokio::time::sleep(Duration::from_millis(400)).await;
        if let Ok(results) = fetch(adb, package) {
            if results.len() > before {
                if let Some(done) = results
                    .iter()
                    .skip(before)
                    .rev()
                    .find(|r| r.stage == "done")
                {
                    if done.ok == Some(true) {
                        println!(
                            "  {} 热安装成功：{} {}",
                            color::green("✓"),
                            done.p.clone().unwrap_or_else(|| label.to_string()),
                            done.m
                        );
                    } else {
                        println!("  {} 热安装失败：{}", color::red("✗"), done.m);
                    }
                    return Ok(());
                }
            }
        }
        if Instant::now() >= deadline {
            if channel == DevChannel::ReleaseCompat {
                println!(
                    "  {} 未收到设备回执（10s）：请确认已开启\"插件开发模式\"\
                     （设置 → 关于 → 连点设备信息 7 次解锁），或用 `xipm logs {log_hint}` 查看设备日志",
                    color::yellow("!")
                );
            } else {
                println!(
                    "  {} 未收到设备回执（10s）：请确认宿主为最新 debug 包（./gradlew installDebug），\
                     或用 `xipm logs {log_hint}` 查看设备日志",
                    color::yellow("!")
                );
            }
            return Ok(());
        }
    }
}

/// 读取热安装回执行（`files/logs/xipm-dev-result.jsonl`，run-as）。
#[derive(serde::Deserialize)]
struct DevResult {
    stage: String,
    #[serde(default)]
    ok: Option<bool>,
    #[serde(default)]
    p: Option<String>,
    #[serde(default)]
    m: String,
}

fn fetch_results(adb: &Adb, package: &str) -> Result<Vec<DevResult>> {
    let text = adb
        .run_as(package, &["cat", "files/logs/xipm-dev-result.jsonl"])
        .unwrap_or_default();
    Ok(text.lines().filter_map(|l| serde_json::from_str(l).ok()).collect())
}

/// release 兼容通道回执：解析 `logcat -d -s XipmDev` 的 INSTALL_OK / INSTALL_FAIL 行
/// （DevPluginInstaller 的 logcat 打点，格式与 jsonl 字段一一对应）。
fn fetch_results_compat(adb: &Adb) -> Result<Vec<DevResult>> {
    let text = adb
        .run(&["logcat", "-d", "-v", "time", "-s", "XipmDev"])
        .unwrap_or_default();
    let mut results = Vec::new();
    for line in text.lines() {
        if let Some(pos) = line.find("INSTALL_OK ") {
            let rest = &line[pos + "INSTALL_OK ".len()..];
            let mut parts = rest.splitn(2, ' ');
            let id = parts.next().map(|s| s.to_string());
            let m = parts.next().unwrap_or("").to_string();
            results.push(DevResult {
                stage: "done".into(),
                ok: Some(true),
                p: id,
                m,
            });
        } else if let Some(pos) = line.find("INSTALL_FAIL ") {
            let m = line[pos + "INSTALL_FAIL ".len()..].to_string();
            results.push(DevResult {
                stage: "done".into(),
                ok: Some(false),
                p: None,
                m,
            });
        }
    }
    Ok(results)
}

// ─────────────────────────────────────────────────────────────────────────────
// xipm logs
// ─────────────────────────────────────────────────────────────────────────────

pub struct LogsArgs {
    pub dir: Option<PathBuf>,
    pub package: String,
    pub adb: Option<PathBuf>,
    pub device: Option<String>,
    pub history: bool,
    pub lines: usize,
    pub json: bool,
}

pub async fn run_logs(args: LogsArgs) -> Result<()> {
    let dir = args
        .dir
        .clone()
        .unwrap_or_else(|| PathBuf::from("."))
        .canonicalize()
        .map_err(|e| anyhow::anyhow!("插件目录不存在: {e}"))?;
    let manifest = Manifest::load(&dir).map_err(|e| anyhow::anyhow!("{e}"))?;
    let adb = Adb::detect(args.adb.clone())?.with_serial(args.device.clone());
    adb.ensure_device()?;

    if args.history {
        if adb.run_as(&args.package, &["true"]).is_err() {
            anyhow::bail!(
                "--history 需要 debug 包（run-as 不可用）；release 包请使用实时日志（默认模式）"
            );
        }
        let bytes = adb.exec_out(&[
            "run-as",
            &args.package,
            "cat",
            "files/logs/plugins/errors.jsonl",
        ])?;
        print_history(&bytes, &manifest.id, args.lines, args.json);
        return Ok(());
    }

    println!(
        "▶ 跟随 {} 的插件日志与错误（Ctrl-C 退出；历史错误用 --history）",
        manifest.id
    );
    // 通道探测：debug 包走 run-as 落盘跟随；release 包无落盘，仅 logcat（含 JsPlugin/错误 tag）
    let channel = if adb.run_as(&args.package, &["true"]).is_ok() {
        DevChannel::Debug
    } else {
        DevChannel::ReleaseCompat
    };
    let follower = if channel == DevChannel::Debug {
        Some(spawn_error_follower(
            adb.clone(),
            args.package.clone(),
            manifest.id.clone(),
        ))
    } else {
        println!("  release 通道：无错误落盘跟随（--history 不可用），仅 logcat 实时日志");
        None
    };
    let mut child = spawn_logcat(&adb, &manifest.id, channel)?;
    tokio::signal::ctrl_c().await.ok();
    let _ = child.kill();
    if let Some(f) = follower {
        f.stop();
    }
    Ok(())
}

/// 错误实时跟随器句柄（置位停止标志 + 唤醒线程退出）。
pub struct ErrorFollower {
    stop: std::sync::Arc<std::sync::atomic::AtomicBool>,
}

impl ErrorFollower {
    pub fn stop(&self) {
        self.stop.store(true, std::sync::atomic::Ordering::Relaxed);
    }
}

/// 实时跟随插件开发日志（1s 轮询，不依赖 logcat，规避 ROM 后台日志限流）：
/// - console（`files/logs/plugins/dev-console.jsonl`）：只输出跟随开始后的新增；
/// - 错误（`files/logs/plugins/errors.jsonl`）：启动时提示最近 1 条，之后只输出新增。
fn spawn_error_follower(adb: Adb, package: String, plugin_id: String) -> ErrorFollower {
    let stop = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
    let stop_flag = stop.clone();
    std::thread::spawn(move || {
        let mut last_error_ts: i64 = 0;
        let mut error_baseline_set = false;
        let mut last_console_ts: i64 = 0;
        let mut console_baseline_set = false;
        while !stop_flag.load(std::sync::atomic::Ordering::Relaxed) {
            // ---- console 落盘（新增） ----
            if let Ok(lines) = fetch_console(&adb, &package) {
                let mine: Vec<&ConsoleLine> = lines.iter().filter(|e| e.p == plugin_id).collect();
                // 首次可读即建立基线（即便为空）：启动时已有历史则静默，之后新增实时显示
                if !console_baseline_set {
                    console_baseline_set = true;
                    last_console_ts = mine.iter().map(|e| e.t).max().unwrap_or(0);
                }
                let mut fresh: Vec<&&ConsoleLine> =
                    mine.iter().filter(|e| e.t > last_console_ts).collect();
                fresh.sort_by_key(|e| e.t);
                for e in fresh {
                    print_console_line(e);
                    last_console_ts = last_console_ts.max(e.t);
                }
            }
            // ---- 错误落盘 ----
            if let Ok(errors) = fetch_errors(&adb, &package) {
                let mine: Vec<&StoredError> = errors.iter().filter(|e| e.p == plugin_id).collect();
                if !mine.is_empty() {
                    if !error_baseline_set {
                        error_baseline_set = true;
                        last_error_ts = mine.iter().map(|e| e.t).max().unwrap_or(0);
                        if let Some(latest) = mine.iter().max_by_key(|e| e.t) {
                            println!(
                                "  {} 最近错误（历史用 `xipm logs . --history`）:",
                                color::yellow("●")
                            );
                            print_runtime_error(latest);
                        }
                    } else {
                        let mut fresh: Vec<&&StoredError> =
                            mine.iter().filter(|e| e.t > last_error_ts).collect();
                        fresh.sort_by_key(|e| e.t);
                        for e in fresh {
                            print_runtime_error(e);
                            last_error_ts = last_error_ts.max(e.t);
                        }
                    }
                }
            }
            std::thread::sleep(Duration::from_millis(1000));
        }
    });
    ErrorFollower { stop }
}

/// 渲染插件 console 行（落盘通道；error 级别红色）。
fn print_console_line(e: &ConsoleLine) {
    let time = clock_hms(e.t);
    if e.l == "error" {
        println!("  {time} {} {}", color::red("[error]"), e.m);
    } else {
        println!("  {time} {} {}", color::yellow("[log]"), e.m);
    }
}

/// epoch 毫秒 → "HH:MM:SS"（UTC）。
fn clock_hms(ms: i64) -> String {
    let secs = ms.div_euclid(1000);
    let rem = secs.rem_euclid(86_400);
    format!("{:02}:{:02}:{:02}", rem / 3600, (rem % 3600) / 60, rem % 60)
}

/// 读取插件 console 落盘（`files/logs/plugins/dev-console.jsonl`，run-as）。
fn fetch_console(adb: &Adb, package: &str) -> Result<Vec<ConsoleLine>> {
    let text = adb
        .run_as(package, &["cat", "files/logs/plugins/dev-console.jsonl"])
        .unwrap_or_default();
    Ok(text.lines().filter_map(|l| serde_json::from_str(l).ok()).collect())
}

/// dev-console.jsonl 行（PluginDevConsoleFileSink 的短键 JSONL）。
#[derive(serde::Deserialize)]
struct ConsoleLine {
    t: i64,
    p: String,
    l: String,
    m: String,
}

/// 渲染单条插件错误（终端高亮 + 堆栈前几行）。
fn print_runtime_error(e: &StoredError) {
    println!(
        "  {} [{}] {} — {}",
        color::red("● 插件错误"),
        e.c.as_deref().unwrap_or("OTHER"),
        e.o,
        e.m
    );
    if let Some(stack) = &e.s {
        for line in stack.lines().take(4) {
            println!("      {line}");
        }
    }
}

/// 读取宿主错误落盘（`files/logs/plugins/errors.jsonl`，run-as）。
fn fetch_errors(adb: &Adb, package: &str) -> Result<Vec<StoredError>> {
    let text = adb
        .run_as(package, &["cat", "files/logs/plugins/errors.jsonl"])
        .unwrap_or_default();
    Ok(text.lines().filter_map(|l| serde_json::from_str(l).ok()).collect())
}

/// errors.jsonl 行（FilePluginErrorStore 的短键 JSONL）。
#[derive(serde::Deserialize)]
struct StoredError {
    t: i64,
    p: String,
    o: String,
    m: String,
    s: Option<String>,
    c: Option<String>,
}

fn print_history(bytes: &[u8], plugin_id: &str, limit: usize, raw: bool) {
    let text = String::from_utf8_lossy(bytes);
    let mut errors: Vec<StoredError> = text
        .lines()
        .filter_map(|line| serde_json::from_str::<StoredError>(line).ok())
        .filter(|e| e.p == plugin_id)
        .collect();

    if errors.is_empty() {
        println!("（暂无历史错误：{}）", plugin_id);
        return;
    }
    let start = errors.len().saturating_sub(limit);
    errors.drain(..start);
    for e in &errors {
        if raw {
            println!(
                "{}",
                serde_json::json!({
                    "time": fmt_epoch_ms(e.t),
                    "category": e.c,
                    "operation": e.o,
                    "message": e.m,
                    "stack": e.s,
                })
            );
            continue;
        }
        let category = e.c.as_deref().unwrap_or("OTHER");
        println!(
            "{} [{}] {} — {}",
            color::yellow(&fmt_epoch_ms(e.t)),
            category,
            e.o,
            e.m
        );
        if let Some(stack) = &e.s {
            for line in stack.lines().take(6) {
                println!("    {line}");
            }
        }
    }
    println!("（共 {} 条，展示最新 {} 条）", errors_len_hint(bytes), errors.len());
}

fn errors_len_hint(bytes: &[u8]) -> usize {
    String::from_utf8_lossy(bytes).lines().count()
}

/// epoch 毫秒 → "YYYY-MM-DD HH:MM:SSZ"（UTC，纯算术，无时区依赖）。
fn fmt_epoch_ms(ms: i64) -> String {
    let secs = ms.div_euclid(1000);
    let days = secs.div_euclid(86_400);
    let rem = secs.rem_euclid(86_400);
    let (y, m, d) = civil_from_days(days);
    format!(
        "{y:04}-{m:02}-{d:02} {:02}:{:02}:{:02}Z",
        rem / 3600,
        (rem % 3600) / 60,
        rem % 60
    )
}

/// Howard Hinnant 的 civil_from_days（epoch 天数 → 年月日）。
fn civil_from_days(z: i64) -> (i64, u32, u32) {
    let z = z + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (if m <= 2 { y + 1 } else { y }, m as u32, d as u32)
}

/// 把 `adb devices` 输出分类成（在线 / offline / 未授权）三组序列号。
///
/// 抽成纯函数以便免真机单测：无线调试下 transport 重复注册或掉线（手动 `adb connect`
/// 的那条变 offline、mDNS 那条还在线）是最高频的故障源。
fn classify_devices(output: &str) -> (Vec<String>, Vec<String>, Vec<String>) {
    let mut online = Vec::new();
    let mut offline = Vec::new();
    let mut unauthorized = Vec::new();
    for line in output.lines().skip(1) {
        let mut it = line.split_whitespace();
        let serial = match it.next() {
            Some(v) => v,
            None => continue,
        };
        let state = match it.next() {
            Some(v) => v,
            None => continue,
        };
        match state {
            "device" => online.push(serial.to_string()),
            "offline" => offline.push(serial.to_string()),
            "unauthorized" => unauthorized.push(serial.to_string()),
            _ => {}
        }
    }
    (online, offline, unauthorized)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn epoch_formatting() {
        assert_eq!(fmt_epoch_ms(0), "1970-01-01 00:00:00Z");
        // 2026-09-18T00:00:00Z
        assert_eq!(fmt_epoch_ms(1_789_689_600_000), "2026-09-18 00:00:00Z");
    }

    #[test]
    fn classify_single_online_device() {
        let out = "List of devices attached\nadb-aa386ace-pzsFrY._adb-tls-connect._tcp\tdevice\n";
        let (online, offline, unauthorized) = classify_devices(out);
        assert_eq!(online, vec!["adb-aa386ace-pzsFrY._adb-tls-connect._tcp"]);
        assert!(offline.is_empty() && unauthorized.is_empty());
    }

    #[test]
    fn classify_separates_offline_and_unauthorized() {
        // 实测故障形态：手动 connect 的 ip:port 已掉线，mDNS 那条仍在线
        let out = "List of devices attached\n192.168.31.66:39353\toffline\n\
                   adb-aa386ace._adb-tls-connect._tcp\tdevice\nemulator-5554\tunauthorized\n";
        let (online, offline, unauthorized) = classify_devices(out);
        assert_eq!(online, vec!["adb-aa386ace._adb-tls-connect._tcp"]);
        assert_eq!(offline, vec!["192.168.31.66:39353"]);
        assert_eq!(unauthorized, vec!["emulator-5554"]);
    }

    #[test]
    fn classify_ignores_header_and_unknown_states() {
        let out = "List of devices attached\n* daemon started successfully *\n\nfoo\tbogus\n";
        let (online, offline, unauthorized) = classify_devices(out);
        assert!(online.is_empty() && offline.is_empty() && unauthorized.is_empty());
    }
}