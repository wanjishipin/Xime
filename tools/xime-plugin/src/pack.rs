use std::io::Write;
use std::path::{Path, PathBuf};

use zip::write::SimpleFileOptions;

use crate::manifest::Manifest;

/// 把插件产物目录打包为 `<name>-<version>.xipk`（xipk 即 zip，内容为产物目录相对路径）。
pub fn pack_plugin(
    out_dir: &Path,
    manifest: &Manifest,
    xipk_dir: &Path,
) -> anyhow::Result<PathBuf> {
    std::fs::create_dir_all(xipk_dir)
        .map_err(|e| anyhow::anyhow!("创建打包目录失败 {}: {e}", xipk_dir.display()))?;

    let plugin_name = out_dir
        .file_name()
        .map(|s| s.to_string_lossy().into_owned())
        .ok_or_else(|| anyhow::anyhow!("无法解析插件目录名: {}", out_dir.display()))?;

    let xipk_path = xipk_dir.join(format!("{plugin_name}-{}.xipk", manifest.version));
    if xipk_path.exists() {
        std::fs::remove_file(&xipk_path)?;
    }

    let file = std::fs::File::create(&xipk_path)
        .map_err(|e| anyhow::anyhow!("创建 xipk 失败 {}: {e}", xipk_path.display()))?;
    let mut zip = zip::ZipWriter::new(file);
    // 最高压缩级别（脚本 + 资源；图片等已压缩格式收益有限，但文本收益明显）
    let options = SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated)
        .compression_level(Some(9));

    add_dir(&mut zip, out_dir, out_dir, options)?;
    zip.finish()
        .map_err(|e| anyhow::anyhow!("写入 xipk 失败: {e}"))?;

    Ok(xipk_path)
}

fn add_dir(
    zip: &mut zip::ZipWriter<std::fs::File>,
    root: &Path,
    dir: &Path,
    options: SimpleFileOptions,
) -> anyhow::Result<()> {
    for entry in std::fs::read_dir(dir)? {
        let path = entry?.path();
        if path.is_dir() {
            add_dir(zip, root, &path, options)?;
        } else {
            let rel = path
                .strip_prefix(root)
                .map_err(|e| anyhow::anyhow!("路径计算失败: {e}"))?
                .to_string_lossy()
                .replace('\\', "/");
            zip.start_file(rel, options)?;
            zip.write_all(&std::fs::read(&path)?)?;
        }
    }
    Ok(())
}

/// 拷贝 xipk 到 app 内置资源目录（debug 构建自动安装的内置插件）。
pub fn copy_to_assets(xipk: &Path, assets_dir: &Path) -> anyhow::Result<PathBuf> {
    std::fs::create_dir_all(assets_dir)
        .map_err(|e| anyhow::anyhow!("创建 assets 目录失败 {}: {e}", assets_dir.display()))?;
    let target = assets_dir.join(
        xipk.file_name()
            .ok_or_else(|| anyhow::anyhow!("非法 xipk 路径: {}", xipk.display()))?,
    );
    std::fs::copy(xipk, &target)?;
    Ok(target)
}
