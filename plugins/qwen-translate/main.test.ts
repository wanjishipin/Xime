// AI 翻译（qwen-mt 专用）插件测试：请求契约 + 控件行 + 互换 + schema。
//
// mock 引擎不记录 http.stream 请求，测试内替换 host.http.stream 槽位捕获请求体，
// 并手动投递 sse.onData/onDone 模拟流式返回（与真机回调契约一致）。

const plugin = (globalThis as any).plugin as Record<string, any>;

let captured: { url?: string; method?: string; body?: any } = {};

function captureStream(): void {
  (host.http as any).stream = async (
    url: string,
    _headers: Record<string, string>,
    _timeoutMillis: number,
    method: string,
    body: Uint8Array,
  ): Promise<number> => {
    captured = {
      url,
      method,
      body: JSON.parse(new TextDecoder().decode(body)),
    };
    plugin.sse.onData(1, '{"choices":[{"delta":{"content":"Hello"}}]}');
    plugin.sse.onDone(1, '');
    return 1;
  };
}

test('generate 按 qwen-mt 契约发起请求（原文直传 + translation_options）', async () => {
  __ximeMock.setConfig('apiKey', 'sk-test');
  __ximeMock.setConfig('targetLang', 'English');
  captureStream();

  plugin.panel.onInput({ key: '', value: '你好世界' });
  await plugin.panel.onAction({ actionId: 'generate' });

  assert.equal(captured.method, 'POST', '应使用 POST');
  assert.ok((captured.url || '').endsWith('/chat/completions'), '应请求 chat/completions');
  // qwen-mt 契约：无 system/prompt 模板，原文即唯一的 user 消息
  assert.equal(captured.body.messages.length, 1, '只应有一条 user 消息');
  assert.equal(captured.body.messages[0].role, 'user');
  assert.equal(captured.body.messages[0].content, '你好世界');
  assert.equal(captured.body.translation_options.source_lang, 'auto');
  assert.equal(captured.body.translation_options.target_lang, 'English');

  // 流式增量累积为译文
  const state = plugin.panel.state({ inputText: '' });
  assert.equal(state.items[0].text, 'Hello');
});

test('generate 缺 API Key 时直接失败不发请求', async () => {
  captured = {};
  __ximeMock.setConfig('apiKey', '');
  plugin.panel.onInput({ key: '', value: '内容' });
  await plugin.panel.onAction({ actionId: 'generate' });
  assert.equal(captured.url, undefined, '未配置 Key 不应发起请求');
});

test('swapLang 互换源/目标；auto 不反向', async () => {
  __ximeMock.setConfig('sourceLang', 'English');
  __ximeMock.setConfig('targetLang', 'Japanese');
  await plugin.panel.onAction({ actionId: 'swapLang' });
  assert.equal(host.config.get('sourceLang'), 'Japanese');
  assert.equal(host.config.get('targetLang'), 'English');

  // 源语言为自动检测时无可交换方向：目标保持不变
  __ximeMock.setConfig('sourceLang', 'auto');
  __ximeMock.setConfig('targetLang', 'Chinese');
  await plugin.panel.onAction({ actionId: 'swapLang' });
  assert.equal(host.config.get('sourceLang'), 'Chinese');
  assert.equal(host.config.get('targetLang'), 'Chinese');
});

test('旧版遗留的自由文本语言值回退官方枚举', () => {
  __ximeMock.setConfig('sourceLang', '自动检测');
  __ximeMock.setConfig('targetLang', '繁體中文');
  const ui = plugin.panel.state({ inputText: '' }).ui as any[];
  // 契约：非法存量值不外漏，回退值必须是选项枚举内的合法接口值
  const sourceValues = (ui[0].options as string[]).map((o) => o.split('|')[1]);
  const targetValues = (ui[2].options as string[]).map((o) => o.split('|')[1]);
  assert.ok(sourceValues.indexOf(ui[0].value) >= 0, `源语言应回退枚举，实际 ${ui[0].value}`);
  assert.ok(targetValues.indexOf(ui[2].value) >= 0, `目标语言应回退枚举，实际 ${ui[2].value}`);
});

test('面板接受宿主上下文预填并声明控件行', () => {
  // 有上下文（宿主采集的选中文本/剪贴板兜底）：直接作为输入框预填，点生成即翻译
  const state = plugin.panel.state({ inputText: '选中的文本' });
  assert.equal(state.inputText, '选中的文本');
  // 无上下文：空框手输
  const empty = plugin.panel.state({ inputText: '' });
  assert.equal(empty.inputText, '');
  const ui = state.ui as any[];
  assert.equal(ui.length, 3);
  assert.equal(ui[0].key, 'sourceLang');
  assert.equal(ui[1].key, 'swapLang');
  assert.equal(ui[2].key, 'targetLang');
});

test('settings schema 为 qwen-mt 专用（无 prompt 模板）', () => {
  const fields = plugin.settings.schema() as any[];
  const keys = fields.map((f) => f.key);
  assert.ok(keys.indexOf('apiKey') >= 0, '应包含 apiKey');
  assert.ok(keys.indexOf('baseUrl') >= 0, '应包含 baseUrl');
  assert.ok(keys.indexOf('model') >= 0, '应包含 model');
  assert.ok(keys.indexOf('prompt') < 0, 'qwen-mt 专用插件不应有 prompt 字段');
});
