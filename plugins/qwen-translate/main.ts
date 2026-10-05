// AI 翻译插件（qwen-mt 专用，TypeScript 源码，xime-plugin 编译为 QuickJS 单文件 main.js，SSE 流式）
//
// 职责划分：
//   插件  = 组装 qwen-mt 请求（原文直传 user 消息 + translation_options 翻译方向）+ 发起流式请求 + 累积译文
//   宿主  = 通用工具面板 + 结果交互自适应（1 条自动上屏替换选区 / 多条候选选择）
//     host.http.stream   async SSE 流式（await 建立，失败 reject XimeError；事件经 plugin.sse.onData/onDone/onError 回调槽投递）
//     host.config        配置存储
//     JSON               原生 JSON.parse/JSON.stringify（沙箱无 host.json，解析失败抛异常需 try/catch）
//
// qwen-mt 契约（阿里云百炼 OpenAI 兼容模式，与通用 chat 模型不同，不可混用）：
//   - messages 只接受 user/assistant 角色（system 会 400），content 即待翻译原文，无 prompt 模板；
//   - 翻译方向经 translation_options { source_lang, target_lang } 声明，取值须用官方语言枚举。

const KEY_API_KEY = 'apiKey';
const KEY_BASE_URL = 'baseUrl';
const KEY_MODEL = 'model';
const KEY_TARGET_LANG = 'targetLang';
const KEY_SOURCE_LANG = 'sourceLang';

const DEFAULTS = {
  baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
  model: 'qwen-mt-flash',
  sourceLang: 'auto',
  targetLang: 'English',
};

// 面板语言选择行选项："显示名|接口值"（面板显示前者，translation_options 用后者）。
// 枚举为 qwen-mt 官方支持语言；源语言额外支持 auto（自动检测）。
const SOURCE_LANGS = [
  '自动检测|auto',
  '简体中文|Chinese',
  'English|English',
  '日本語|Japanese',
  '한국어|Korean',
  'Deutsch|German',
  'Français|French',
  'Italiano|Italian',
  'Português|Portuguese',
  'Español|Spanish',
  'Русский|Russian',
  'العربية|Arabic',
  'ไทย|Thai',
  'Polski|Polish',
  'Tiếng Việt|Vietnamese',
];
const TARGET_LANGS = SOURCE_LANGS.filter((o) => o.split('|')[1] !== 'auto');
const SOURCE_VALUES = SOURCE_LANGS.map((o) => o.split('|')[1]);
const TARGET_VALUES = TARGET_LANGS.map((o) => o.split('|')[1]);

/** 面板候选条目（宿主渲染并点选上屏）。 */
interface ResultItem {
  id: string;
  text: string;
}

let lastContext = '';
let buffer = '';
let generating = false;
let sessionId = -1;

// 配置值必须落在官方枚举内：旧版本遗留的自由文本值（如"繁體中文"）不受 qwen-mt 支持，回退默认
function currentSourceLang(): string {
  const stored = host.config.get(KEY_SOURCE_LANG) || '';
  return SOURCE_VALUES.indexOf(stored) >= 0 ? stored : DEFAULTS.sourceLang;
}

function currentTargetLang(): string {
  const stored = host.config.get(KEY_TARGET_LANG) || '';
  return TARGET_VALUES.indexOf(stored) >= 0 ? stored : DEFAULTS.targetLang;
}

function buildItems(): ResultItem[] {
  if (buffer === '') return [];
  return [{ id: 'result', text: buffer }];
}

/** 面板控件行：源语言选择 + 互换键 + 目标语言选择（宿主渲染，值经 onInput/onAction 回流）。 */
function buildControlsUi(): XimeUiNode[] {
  return [
    { type: 'select', key: KEY_SOURCE_LANG, label: '源语言', value: currentSourceLang(), options: SOURCE_LANGS },
    { type: 'button', key: 'swapLang', label: '⇄' },
    { type: 'select', key: KEY_TARGET_LANG, label: '目标语言', value: currentTargetLang(), options: TARGET_LANGS },
  ];
}

// ================= 插件定义（宿主按扩展点路由调用） =================

const plugin = definePlugin({
  // ================= 配置 schema（插件中心设置页表单数据源） =================

  settings: {
    schema(): XimeUiNode[] {
      return [
        {
          key: KEY_API_KEY,
          label: 'API Key',
          type: 'secret',
          placeholder: '输入阿里云百炼 API Key',
          helpText: '阿里云百炼（DashScope）的 API Key',
        },
        {
          key: KEY_BASE_URL,
          label: '接口地址',
          type: 'text',
          defaultValue: DEFAULTS.baseUrl,
          helpText: '百炼 OpenAI 兼容模式地址（/compatible-mode/v1 前缀），域名将自动获得联网授权',
        },
        {
          key: KEY_MODEL,
          label: '模型',
          type: 'text',
          defaultValue: DEFAULTS.model,
          helpText: 'qwen-mt 系列翻译模型（如 qwen-mt-flash / qwen-mt-plus）',
        },
      ];
    },
  },

  panel: {
    state(input: XimePanelInput): XimePanelState {
      return {
        // 接受宿主上下文预填：选中文本优先（无选区时宿主兜底剪贴板），都无则为空框手输
        inputText: input.inputText || '',
        items: buildItems(),
        loading: generating,
        ui: buildControlsUi(),
      };
    },

    onInput(input: XimePanelInputEvent): void {
      if (input.key === '') {
        // 主输入框
        lastContext = input.value || '';
        return;
      }
      if (input.key === KEY_SOURCE_LANG && input.value) {
        host.config.set(KEY_SOURCE_LANG, input.value);
        return;
      }
      if (input.key === KEY_TARGET_LANG && input.value) {
        host.config.set(KEY_TARGET_LANG, input.value);
        return;
      }
    },

    async onAction(input: XimePanelActionEvent): Promise<void> {
      if (input.actionId === 'swapLang') {
        // 互换源/目标语言后由宿主重拉 state 刷新选择行；
        // 源语言为自动检测（auto）时没有可交换的目标方向，保持目标语言不变
        const src = currentSourceLang();
        host.config.set(KEY_SOURCE_LANG, currentTargetLang());
        if (src !== DEFAULTS.sourceLang) {
          host.config.set(KEY_TARGET_LANG, src);
        }
        return;
      }
      if (input.actionId !== 'generate') return;
      const context = lastContext;
      if (context === '') {
        host.logError('请先输入待翻译内容');
        return;
      }
      if (generating) return;

      const apiKey = host.config.get(KEY_API_KEY) || '';
      if (apiKey === '') {
        host.logError('AI 翻译未配置 API Key');
        return;
      }

      buffer = '';
      generating = true;

      let baseUrl = host.config.get(KEY_BASE_URL) || DEFAULTS.baseUrl;
      baseUrl = baseUrl.replace(/\/+$/, '');
      const model = host.config.get(KEY_MODEL) || DEFAULTS.model;

      // qwen-mt 契约：原文直传 user 消息，翻译方向经 translation_options（无 system/prompt 模板）；
      // 请求体必须是 Uint8Array（宿主 bytes() 只认字节，JS 字符串会变 null）
      const body = new TextEncoder().encode(JSON.stringify({
        model: model,
        messages: [{ role: 'user', content: context }],
        translation_options: {
          source_lang: currentSourceLang(),
          target_lang: currentTargetLang(),
        },
        stream: true,
      }));
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + apiKey,
      };
      const url = baseUrl + '/chat/completions';

      // SSE 流式：await 建立，resolve 会话 id（回调槽 sse.onData/onDone/onError 携带该 id）；失败 reject XimeError
      try {
        sessionId = await host.http.stream(url, headers, 0, 'POST', body);
      } catch (e) {
        generating = false;
        host.logError('AI 流式连接被拒绝: ' + ((e as Error).message || '未知错误'));
      }
    },

    onItemClick(_input: XimePanelItemClickEvent): void {
      // 上屏由宿主完成（选区替换）
    },
  },

  // ---- SSE 回调槽（宿主投递；形参 id 与模块态 sessionId 同名会遮蔽，统一用 sid） ----

  sse: {
    onData(_sid: number, text: string): void {
      if (text === undefined || text === null || text === '' || text === '[DONE]') return;
      // 原生 JSON.parse 非法输入抛异常（原 host.json.decode 返回 nil）
      let data: unknown = null;
      try {
        data = JSON.parse(text);
      } catch (e) {
        return;
      }
      if (data === null || typeof data !== 'object') return;
      const choices = (data as Record<string, unknown>).choices;
      if (!Array.isArray(choices) || choices.length === 0) return;
      const delta = (choices[0] as { delta?: { content?: unknown } }).delta;
      if (delta !== undefined && delta !== null && delta.content !== undefined && delta.content !== null) {
        buffer = buffer + String(delta.content);
      }
    },

    onDone(_sid: number, fullText: string): void {
      if (fullText !== undefined && fullText !== null && fullText !== '' && buffer === '') {
        buffer = fullText;
      }
      generating = false;
      sessionId = -1;
    },

    onError(_sid: number, message: string): void {
      host.logError('AI 流式请求失败: ' + (message !== null && message !== undefined ? message : '未知错误'));
      generating = false;
      sessionId = -1;
    },
  },
});

export default plugin;
