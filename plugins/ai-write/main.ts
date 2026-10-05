// AI 帮写插件（TypeScript 源码，xime-plugin 编译为 QuickJS 单文件 main.js，SSE 流式）
//
// 职责划分：
//   插件  = prompt 模板组装 + 发起流式请求（await host.http.stream）+ 累积增量文本 + 停止
//   宿主  = 通用工具面板（输入框/候选渲染/选区替换上屏）+ 通用原语：
//     host.http.stream        async SSE 流式（await 建立，失败 reject XimeError；事件经 plugin.sse.onData/onDone/onError 回调槽投递）
//     host.http.closeStream   await 主动中断流
//     host.config             配置存储
//     JSON                    原生 JSON.parse/JSON.stringify（沙箱无 host.json，解析失败抛异常需 try/catch）
//
// 工具面板契约（host 调用）：
//   panel.state(input)       返回面板状态 { inputText, items, loading }
//   panel.onInput(input)     输入变化
//   panel.onAction(input)    generate 发起流式生成 / stop 中断
//   panel.onItemClick(input) 点候选（上屏由宿主完成）

const KEY_API_KEY = 'apiKey';
const KEY_BASE_URL = 'baseUrl';
const KEY_MODEL = 'model';
const KEY_PROMPT = 'prompt';

const DEFAULTS = {
  baseUrl: '',
  model: '',
  prompt: `你是我的写作助手。请根据下面的写作要求，直接写出可用的正文。
要求：
1. 只输出正文本身：不要开场白（如"好的，以下是"）、解释、标题或结尾客套，输出内容会被直接填入输入框；
2. 用通顺、自然、得体的中文表达，篇幅与要求匹配，无明确长度要求时写 50~150 字左右；
3. 忠实使用写作要求中出现的信息（人名、事项、时间等），不要虚构具体细节；
4. 只输出纯文本：不用 markdown 标记（#、*、列表符号等），需要分点时用换行分段；
5. 若写作要求本身就是一段待润色/改写的文字，直接给出改写结果，不要附加说明。

写作要求：
{context}`,
};

/** 面板候选条目（宿主渲染并点选上屏）。 */
interface ResultItem {
  id: string;
  text: string;
}

let lastContext = '';
let buffer = '';
let generating = false;
let sessionId = -1;

function buildItems(): ResultItem[] {
  if (buffer === '') return [];
  return [{ id: 'result', text: buffer }];
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
          label: '写作模板',
          type: 'textarea',
          defaultValue: DEFAULTS.prompt,
          helpText: '写作 prompt 模板，{context} 会被替换为面板输入内容',
        },
      ];
    },
  },

  panel: {
    state(input: XimePanelInput): XimePanelState {
      return {
        inputText: input.inputText,
        items: buildItems(),
        loading: generating,
      };
    },

    onInput(input: XimePanelInputEvent): void {
      lastContext = input.value || '';
    },

    async onAction(input: XimePanelActionEvent): Promise<void> {
      if (input.actionId === 'stop') {
        if (sessionId >= 0) {
          try {
            await host.http.closeStream(sessionId);
          } catch (e) {
            host.logError('AI 流式中断失败: ' + ((e as Error).message || '未知错误'));
          }
          sessionId = -1;
        }
        generating = false;
        return;
      }
      if (input.actionId !== 'generate') return;
      if (generating) return;

      const context = lastContext;
      if (context === '') {
        host.logError('请先输入写作要求');
        return;
      }
      const apiKey = host.config.get(KEY_API_KEY) || '';
      if (apiKey === '') {
        host.logError('AI 帮写未配置 API Key');
        return;
      }

      buffer = '';
      generating = true;

      let baseUrl = host.config.get(KEY_BASE_URL) || DEFAULTS.baseUrl;
      baseUrl = baseUrl.replace(/\/+$/, '');
      const model = host.config.get(KEY_MODEL) || DEFAULTS.model;
      const prompt = (host.config.get(KEY_PROMPT) || DEFAULTS.prompt).split('{context}').join(context);

      // 请求体必须是 Uint8Array（宿主 bytes() 只认字节，JS 字符串会变 null）
      const body = new TextEncoder().encode(JSON.stringify({
        model: model,
        messages: [
          { role: 'system', content: '你是写作助手，直接输出正文，不要解释。' },
          { role: 'user', content: prompt },
        ],
        stream: true,
        // qwen3 等推理模型默认先思考再输出，帮写场景不需要：关闭后首字延迟显著降低；
        // 非推理模型（GPT 系等）会忽略此字段
        enable_thinking: false,
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
      // 上屏由宿主完成
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
