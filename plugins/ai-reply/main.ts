// AI 智能回复插件（TypeScript 源码，xime-plugin 编译为 QuickJS 单文件 main.js，同步全链路）
//
// 职责划分：
//   插件  = prompt 模板组装 + 调用 LLM（host.http.request）+ 解析候选列表
//   宿主  = 全屏纯展示面板（InfoPanel：ui 节点树 + items 候选点选上屏）+ 通用原语：
//     host.http.request  async HTTP（await；第 5 参 timeoutMillis 覆盖长生成超时）
//     host.config        配置存储
//     JSON               原生 JSON.parse/JSON.stringify（沙箱无 host.json，解析失败抛异常需 try/catch）
//
// 工具面板契约（host 调用，display: passive）：
//   panel.state(input)       返回面板状态 { items, ui, loading }（input.inputText 为宿主收集的上下文）
//   panel.onInput(input)     输入变化（passive 无输入框，保留兼容）
//   panel.onAction(input)    ui 节点 action 点击（generate = 调用 LLM）
//   panel.onItemClick(input) 点候选（上屏由宿主完成）

const KEY_API_KEY = 'apiKey';
const KEY_BASE_URL = 'baseUrl';
const KEY_MODEL = 'model';
const KEY_PROMPT = 'prompt';

const DEFAULTS = {
  baseUrl: '',
  model: '',
  prompt: `你是我的聊天回复助手。请根据对方消息，以我的身份生成回复候选，供我点选后直接发送。
要求：
1. 生成 3~5 条候选，每条不超过 30 字，口语自然、简洁礼貌，符合日常聊天习惯；
2. 候选之间意图要有区分（如同意、婉拒、追问等），覆盖最可能的选择，不要同义反复；
3. 对方提问时至少一条直接回答；对方提出请求时明确表达同意或拒绝，不要含糊敷衍；
4. 默认用中文回复；对方消息明显是其他语言时，改用该语言回复；
5. 严格只输出一个 JSON 对象：不要 markdown 代码块、不要编号、不要任何解释或多余文字，格式为 {"candidates": ["好的呀", "马上来", "稍等片刻"]}，candidates 为候选文本数组。

对方消息：
{context}`,
};

/** 面板候选条目（宿主渲染并点选上屏）。 */
interface CandidateItem {
  id: string;
  text: string;
}

let lastContext = '';
let lastError = '';
let cachedItems: CandidateItem[] = [];
let generating = false;

// ================= 工具函数 =================

function trim(s: string | null | undefined): string {
  return (s || '').replace(/^\s+/, '').replace(/\s+$/, '');
}

// 把 LLM 输出的多行文本拆成候选列表（去除编号/引号前缀，JSON 解析失败的兜底）
function splitCandidates(text: string): string[] {
  const lines: string[] = [];
  // Lua gmatch("[^\n]+") 逐行取非空行（\r 由 trim 处理）
  for (const line of text.split('\n')) {
    let t = trim(line);
    t = t.replace(/^[\s\-\d.)\]]*/, '').replace(/^["']/, '').replace(/["']$/, '');
    t = trim(t);
    if (t !== '') lines.push(t);
  }
  return lines;
}

// 从模型输出中提取 JSON（剥离 ```json 代码块包裹，取首个 { 或 [ 到末尾对应括号的区间）
function extractJson(text: string): string | null {
  let t = text.replace(/```[\w]*/g, '').replace(/```/g, '');
  t = trim(t);
  const startIdx = t.search(/[{[]/);
  if (startIdx < 0) return null;
  // 末尾对应的右括号（等价 Lua reverse():find("[}%]]")）
  const endIdx = Math.max(t.lastIndexOf('}'), t.lastIndexOf(']'));
  if (endIdx < 0) return null;
  if (endIdx <= startIdx) return null;
  return t.substring(startIdx, endIdx + 1);
}

// 解析候选：优先 JSON 对象（prompt 已要求 {"candidates": [...]}），兼容直接数组，失败时按行拆分兜底
function parseCandidates(content: string): CandidateItem[] {
  const items: CandidateItem[] = [];
  const json = extractJson(content || '');
  if (json !== null) {
    // 原生 JSON.parse 非法输入抛异常（原 host.json.decode 返回 nil），catch 后走兜底
    let list: unknown = null;
    try {
      const data: unknown = JSON.parse(json);
      if (data !== null && typeof data === 'object') {
        const obj = data as Record<string, unknown>;
        list = obj.candidates !== undefined ? obj.candidates : data;
      }
    } catch (e) {
      list = null;
    }
    if (Array.isArray(list)) {
      for (const v of list) {
        if (typeof v === 'string') {
          const t = trim(v);
          if (t !== '') items.push({ id: String(items.length + 1), text: t });
        }
      }
    }
    if (items.length > 0) return items;
  }
  for (const line of splitCandidates(content || '')) {
    items.push({ id: String(items.length + 1), text: line });
  }
  return items;
}

// ================= 工具面板契约 =================

// ui 节点树（统一 UiNode 白名单 type：section/text/metric/divider/button）：
//   未生成 → 对方消息预览 + "生成回复"按钮；失败 → 错误提示 + 重试按钮；
//   生成中 → 空 ui（loading 字段驱动宿主显示加载态）；有候选 → 提示文字（items 由宿主渲染）
function buildUi(): XimeUiNode[] {
  const ui: XimeUiNode[] = [];
  if (cachedItems.length > 0) {
    ui.push({ type: 'section', label: '回复候选' });
    ui.push({ type: 'text', value: '点击候选直接上屏', style: 'caption' });
  } else if (!generating) {
    ui.push({ type: 'section', label: '对方消息' });
    if (lastContext !== '') {
      ui.push({ type: 'text', value: lastContext });
    } else {
      ui.push({ type: 'text', value: '暂无上下文：先复制对方消息，再从工具栏打开本面板', style: 'caption' });
    }
    if (lastError !== '') {
      ui.push({ type: 'text', value: lastError, style: 'caption' });
    }
    ui.push({ type: 'button', label: lastError !== '' ? '重新生成' : '生成回复', key: 'generate' });
  }
  return ui;
}

// ================= 插件定义（宿主按扩展点路由调用） =================

const plugin = definePlugin({
  // ================= 配置 schema（与 manifest 一致，插件中心表单数据源） =================

  settings: {
    schema(): XimeUiNode[] {
      return [
        {
          key: KEY_API_KEY,
          label: 'API Key',
          type: 'secret',
          placeholder: '输入 LLM API Key',
          helpText: 'OpenAI 兼容接口的 API Key',
        },
        {
          key: KEY_BASE_URL,
          label: '接口地址',
          type: 'text',
          defaultValue: DEFAULTS.baseUrl,
          helpText: 'OpenAI 兼容接口地址（/chat/completions 前缀），域名将自动获得联网授权',
        },
        {
          key: KEY_MODEL,
          label: '模型',
          type: 'text',
          defaultValue: DEFAULTS.model,
        },
        {
          key: KEY_PROMPT,
          label: '回复模板',
          type: 'textarea',
          defaultValue: DEFAULTS.prompt,
          helpText: '生成候选的 prompt 模板，{context} 会被替换为对方消息',
        },
      ];
    },
  },

  // ================= 工具面板契约 =================

  panel: {
    state(input: XimePanelInput): XimePanelState {
      // openToolPanel 时宿主传入收集的上下文（选区 > 输入框 > 剪贴板）；
      // action 点击后的单次重拉传空串，不覆盖已有上下文。
      // 上下文变化（用户复制了新消息）→ 旧候选/错误状态失效，回到初始态
      if (trim(input.inputText) !== '') {
        const ctx = trim(input.inputText);
        if (ctx !== lastContext) {
          cachedItems = [];
          lastError = '';
          generating = false;
        }
        lastContext = ctx;
      }
      return {
        items: cachedItems,
        loading: generating,
        ui: buildUi(),
      };
    },

    onInput(input: XimePanelInputEvent): void {
      lastContext = input.value || '';
    },

    async onAction(input: XimePanelActionEvent): Promise<void> {
      if (input.actionId !== 'generate') return;
      const context = trim(lastContext);
      if (context === '') {
        lastError = '请先复制对方消息再打开本面板';
        return;
      }
      if (generating) return;

      const apiKey = host.config.get(KEY_API_KEY) || '';
      if (apiKey === '') {
        lastError = '未配置 API Key，请到插件中心配置';
        return;
      }

      generating = true;
      lastError = '';
      cachedItems = [];

      let baseUrl = host.config.get(KEY_BASE_URL) || DEFAULTS.baseUrl;
      baseUrl = baseUrl.replace(/\/+$/, '');
      const model = host.config.get(KEY_MODEL) || DEFAULTS.model;
      const prompt = (host.config.get(KEY_PROMPT) || DEFAULTS.prompt).split('{context}').join(context);

      // 请求体必须是 Uint8Array（宿主 bytes() 只认字节，JS 字符串会变 null）
      const body = new TextEncoder().encode(JSON.stringify({
        model: model,
        messages: [
          { role: 'system', content: '你是智能回复助手。严格按格式输出：只输出一个 JSON 对象，格式为 {"candidates": ["回复一", "回复二"]}，不要任何其他文字。' },
          { role: 'user', content: prompt },
        ],
        temperature: 0.8,
        // qwen3 等推理模型默认先思考再回答，候选回复场景不需要：关闭后出首字/总耗时显著降低；
        // 非推理模型（GPT 系等）会忽略此字段
        enable_thinking: false,
      }));
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + apiKey,
      };
      const url = baseUrl + '/chat/completions';

      try {
        const resp = await host.http.request('POST', url, headers, body, 60000);
        if (resp.status !== 200) {
          lastError = 'AI 接口返回 ' + String(resp.status);
          host.logError(lastError + ': ' + resp.text);
          return;
        }

        // 原生 JSON.parse 非法输入抛异常
        let data: unknown = null;
        try {
          data = JSON.parse(resp.text);
        } catch (e) {
          data = null;
        }
        const items: CandidateItem[] = [];
        if (data !== null && typeof data === 'object') {
          const choices = (data as Record<string, unknown>).choices;
          if (Array.isArray(choices)) {
            for (const choice of choices) {
              const message = (choice as { message?: { content?: unknown } }).message;
              const content = typeof message?.content === 'string' ? message.content : '';
              for (const item of parseCandidates(content)) {
                items.push(item);
              }
            }
          }
        }
        if (items.length === 0) {
          lastError = '未解析到候选，请重试';
        }
        cachedItems = items;
      } catch (e) {
        lastError = 'AI 请求失败: ' + ((e as Error).message || '未知错误');
        host.logError(lastError);
      } finally {
        generating = false;
      }
    },

    onItemClick(_input: XimePanelItemClickEvent): void {
      // 点选上屏即本次任务完成：重置面板状态，下次打开回到初始态
      // （否则旧候选残留，新复制的消息看起来"没效果"）
      cachedItems = [];
      lastError = '';
      generating = false;
    },
  },
});

export default plugin;
