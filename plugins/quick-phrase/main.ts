// 常用语插件：把宿主快捷发送内容（常用语库，含触发编码 code）匹配进候选栏。
//
// 数据流：
//   onLoad           首次拉取 host.quickSend.list() 到内存缓存
//   quick_send_changed  宿主推送变更事件（conflated 只保最新）→ 刷新缓存
//   transform.candidates 纯内存匹配缓存（hotPath 硬超时 15ms，禁止网络/文件 IO）
//
// 匹配与排序规则：
//   1. 内容命中：快捷条目 text 以某个引擎候选词开头（如候选"电话"、条目"电话号码：135..."）
//      → 该条目紧跟在该候选词之后
//   2. 编码命中：用户输入编码是条目 code 的前缀（code 非空，如输入 dh/d 命中 code=dh）
//      → 条目插到"引擎第一候选之后"（即候选列表第二候选位；引擎无候选则放第一）
//   3. 去重：同一条目只出现一次（内容命中优先于编码命中的位置）
// 未命中返回 null（不干预）。

interface QuickSendEntry {
  id: number;
  text: string;
  code: string | null;
  timestamp: number;
  isPinned: boolean;
}

interface TransformItem {
  engineIndex?: number;
  text?: string;
  comment?: string;
}

// host.quickSend.list() 的缓存 { {id,text,code,timestamp,isPinned}, ... }
let quickSends: QuickSendEntry[] = [];

function refresh(): void {
  if (!host.quickSend) return;
  quickSends = (host.quickSend.list() as QuickSendEntry[]) ?? [];
}

// 快捷条目的候选注释：有 code 显示 code（提示触发码），否则"快捷"
function commentOf(q: QuickSendEntry): string {
  const code = (q.code ?? '').replace(/\s/g, '');
  if (code !== '') return code;
  return '快捷';
}

const plugin = definePlugin({
  onLoad(): void {
    refresh();
  },

  events: {
    // count 为变更后条目数，缓存内容以 list() 全量刷新为准
    onQuickSendChanged(_e: XimeQuickSendChangedEvent): void {
      refresh();
    },
  },

  transform: {
    candidates(req: XimeTransformRequest): XimeTransformResponse | null {
      const input = req.inputText || '';
      const engineCands = req.candidates || [];
      if (input === '' || quickSends.length === 0) return null;

      const result: TransformItem[] = [];
      const inserted: Record<string, boolean> = {}; // 按 id 去重
      let firstEnginePos: number | null = null; // result 中第一个引擎候选项的最终位置

      // 1. 引擎候选按序保留；每条候选后跟随 text 以其开头的快捷条目
      for (let i = 0; i < engineCands.length; i++) {
        result.push({ engineIndex: i });
        if (firstEnginePos === null) firstEnginePos = result.length - 1;
        const ctext = engineCands[i].text || '';
        for (const q of quickSends) {
          const text = q.text || '';
          if (!inserted[String(q.id)] && text !== '' && ctext !== '' && text.substring(0, ctext.length) === ctext) {
            result.push({ text: text, comment: commentOf(q) });
            inserted[String(q.id)] = true;
          }
        }
      }

      // 2. 编码命中：输入编码是 code 前缀（code 非空，未插入过）
      const codeHits: QuickSendEntry[] = [];
      for (const q of quickSends) {
        const code = (q.code ?? '').replace(/\s/g, '');
        if (!inserted[String(q.id)] && code !== '' && input.substring(0, code.length) === code) {
          codeHits.push(q);
        }
      }
      if (codeHits.length > 0) {
        const insertAt = firstEnginePos === null ? 0 : firstEnginePos + 1;
        const codeItems: TransformItem[] = codeHits.map((q) => ({ text: q.text, comment: commentOf(q) }));
        result.splice(insertAt, 0, ...codeItems);
      }

      if (result.length === 0) return null;
      return { candidates: result };
    },
  },
});

export default plugin;
