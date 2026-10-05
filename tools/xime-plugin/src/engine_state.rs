// 测试引擎全局状态（rquickjs #[function] 需无捕获 fn，native 参数经此注入）。
//
// 使用约束：插件测试严格串行执行（xipm test 的批量模式为 for 循环逐个运行），
// 不存在并发读写竞争。

use std::path::PathBuf;
use std::sync::Mutex;

pub struct NativeState {
    pub plugin_id: String,
    pub resources_dir: PathBuf,
    pub main_js: String,
    pub test_js: String,
}

static STATE: Mutex<Option<NativeState>> = Mutex::new(None);

pub fn set(state: NativeState) {
    *STATE.lock().unwrap() = Some(state);
}

pub fn with<T>(f: impl FnOnce(&NativeState) -> T) -> T {
    let guard = STATE.lock().unwrap();
    f(guard.as_ref().expect("engine_state 未初始化（xipm test 内部错误）"))
}