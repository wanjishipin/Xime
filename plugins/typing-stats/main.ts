// 输入统计插件（QuickJS 脚本，演示 capabilities.events 下行事件 + passive 展示面板）
//
// 职责划分：
//   插件 = 事件累计（text_committed 增量持久化）+ 速度/日/周/月聚合 + 称号分级 + 面板节点树
//   宿主 = 事件投递（conflated 快照）+ InfoPanel 渲染（display: passive）+ action 回调
//
// 事件语义（manifest events 声明后由宿主投递到 events 槽）：
//   onTextCommitted: { committedText, sessionTotalChars, sessionTotalCommits, isPaste }
//     - session_* 为宿主进程生命周期累计；conflated 丢中间事件不影响统计（差值增量）
//     - 宿主重启后 session 归零：delta 为负时视为新会话起点
//     - 插件重载后：last_seen 持久化，首次差值按 0，避免重复累计
//     - is_paste = 粘贴性质上屏（剪贴板点选/编辑面板提交），不计入打字量
//   onInputChanged:  { inputText }  当前编码快照（高频，仅内存不落盘）
//
// 沙箱约束：无 os/io——日期与速度的时间源为 host.crypto.utcTime（UTC），
// 日期差值用儒略日纯算术实现，速度用 60 秒滑动窗口（内存态，不落盘）。

const KEY_TOTAL_CHARS = 'total_chars';
const KEY_TOTAL_COMMITS = 'total_commits';
const KEY_DAILY = 'daily';
const KEY_LAST_SEEN = 'last_seen_chars';

interface Title {
  min: number;
  name: string;
  badge: string;
}

// 称号分级（按累计字数）：降序匹配第一个满足项；badge 为面板展示徽章
const TITLES: Title[] = [
  { min: 500000, name: '传奇笔仙', badge: '👑' },
  { min: 100000, name: '一代宗师', badge: '💎' },
  { min: 20000, name: '键上飞侠', badge: '🥇' },
  { min: 5000, name: '熟练写手', badge: '🥈' },
  { min: 1000, name: '入门学徒', badge: '🥉' },
  { min: 0, name: '新手', badge: '🌱' },
];

// 明细保留天数（防止 host.config 无限膨胀）
const DAILY_KEEP_DAYS = 400;

// ===== 状态 =====
let totalChars = parseInt(host.config.get(KEY_TOTAL_CHARS) ?? '') || 0;
let totalCommits = parseInt(host.config.get(KEY_TOTAL_COMMITS) ?? '') || 0;
// 首次使用（无记录）时从当前快照起算，不回溯历史（差值按 0）
const lastSeenRaw = host.config.get(KEY_LAST_SEEN);
let lastSeenChars: number | null = lastSeenRaw === null || lastSeenRaw === '' ? null : parseInt(lastSeenRaw);
let daily: Record<string, number> = {};
try {
  daily = JSON.parse(host.config.get(KEY_DAILY) || '{}') || {};
} catch (e) {
  daily = {};
}
let currentInput = '';
// 打字速度滑动窗口（仅内存）
let speedWindow: Array<{ ts: number; chars: number }> = [];

// ===== 时间工具（沙箱无 os，纯算术） =====

// "YYYYMMDD" → 儒略日天数（Fliegel & Van Flandern 公式），用于日期差值
function ymdToDays(y: number, m: number, d: number): number {
  const a = Math.floor((14 - m) / 12);
  const y2 = y + 4800 - a;
  const m2 = m + 12 * a - 3;
  return d + Math.floor((153 * m2 + 2) / 5) + 365 * y2
    + Math.floor(y2 / 4) - Math.floor(y2 / 100) + Math.floor(y2 / 400) - 32045;
}

function parseYmd(key: string): [number, number, number] | null {
  const y = parseInt(key.substring(0, 4));
  const m = parseInt(key.substring(4, 6));
  const d = parseInt(key.substring(6, 8));
  if (isNaN(y) || isNaN(m) || isNaN(d)) return null;
  return [y, m, d];
}

function todayStr(): string {
  return host.crypto.utcTime('YYYYMMDD');
}

// "YYYYMMDDTHHMMSSZ" → 自洽的绝对秒数（仅用于差值比较）
function nowSec(): number {
  const s = host.crypto.utcTime('YYYYMMDDTHHMMSSZ');
  const p = parseYmd(s);
  if (p === null) return 0;
  const hh = parseInt(s.substring(9, 11)) || 0;
  const mi = parseInt(s.substring(11, 13)) || 0;
  const ss = parseInt(s.substring(13, 15)) || 0;
  return ymdToDays(p[0], p[1], p[2]) * 86400 + hh * 3600 + mi * 60 + ss;
}

// ===== 聚合 =====

// 近 daysBack 天（含今日）的字数合计
function sumRecentDays(daysBack: number): number {
  let total = 0;
  const t = todayStr();
  const p = parseYmd(t);
  if (p === null) return 0;
  const base = ymdToDays(p[0], p[1], p[2]);
  for (const key of Object.keys(daily)) {
    const dp = parseYmd(key);
    if (dp === null) continue;
    const diff = base - ymdToDays(dp[0], dp[1], dp[2]);
    if (diff >= 0 && diff < daysBack) total = total + daily[key];
  }
  return total;
}

// 自然月（"YYYYMM"）字数合计
function sumMonth(monthKey: string): number {
  let total = 0;
  for (const key of Object.keys(daily)) {
    if (key.substring(0, 6) === monthKey) total = total + daily[key];
  }
  return total;
}

// 裁剪 daily 明细：只保留最近 DAILY_KEEP_DAYS 天
function pruneDaily(): void {
  const t = todayStr();
  const p = parseYmd(t);
  if (p === null) return;
  const base = ymdToDays(p[0], p[1], p[2]);
  for (const key of Object.keys(daily)) {
    const dp = parseYmd(key);
    if (dp === null || (base - ymdToDays(dp[0], dp[1], dp[2])) >= DAILY_KEEP_DAYS) {
      delete daily[key];
    }
  }
}

// ===== 称号 =====

function resolveTitle(): Title {
  for (const t of TITLES) {
    if (totalChars >= t.min) return t;
  }
  return TITLES[TITLES.length - 1];
}

// 下一级称号提示；已满级返回 null
function nextTitleHint(): string | null {
  for (let i = TITLES.length - 1; i >= 0; i--) {
    if (totalChars < TITLES[i].min) {
      return '✨ 距「' + TITLES[i].name + '」还差 ' + (TITLES[i].min - totalChars) + ' 字';
    }
  }
  return null;
}

// ===== 速度 =====

function recordSpeed(tsSec: number, delta: number): void {
  if (delta <= 0) return;
  speedWindow.push({ ts: tsSec, chars: delta });
  // 清理窗口外数据（保留 2 倍窗口余量，避免边界抖动）
  const cutoff = tsSec - 120;
  speedWindow = speedWindow.filter((e) => e.ts >= cutoff);
}

// 最近 60 秒上屏字数 ≈ 字/分钟
function currentKpm(now: number): number {
  let total = 0;
  for (const e of speedWindow) {
    if (now - e.ts <= 60) total = total + e.chars;
  }
  return total;
}

// ===== 持久化 =====

function persist(): void {
  pruneDaily();
  host.config.set(KEY_TOTAL_CHARS, String(totalChars));
  host.config.set(KEY_TOTAL_COMMITS, String(totalCommits));
  host.config.set(KEY_LAST_SEEN, String(lastSeenChars));
  host.config.set(KEY_DAILY, JSON.stringify(daily));
}

const plugin = definePlugin({
  // ===== 下行事件（manifest events 声明） =====
  events: {
    onTextCommitted(e: XimeTextCommittedEvent): void {
      console.log("???")

      const sessionChars = e.sessionTotalChars || 0;
      let delta = sessionChars - (lastSeenChars === null ? sessionChars : lastSeenChars);
      lastSeenChars = sessionChars;
      if (delta < 0) {
        // 宿主重启（session 归零）：新会话起点，本快照即增量
        delta = sessionChars;
      }
      // isPaste（键盘剪贴板点选/编辑面板提交）：事件照收以推进差值基准，
      // 但粘贴不是打字，不计字数/提交次数/速度
      if (!e.isPaste) {
        if (delta > 0) {
          totalChars = totalChars + delta;
          const d = todayStr();
          daily[d] = (daily[d] || 0) + delta;
          recordSpeed(nowSec(), delta);
        }
        totalCommits = totalCommits + 1;
      }
      persist();
    },

    // 高频事件：只更新内存态，不写盘
    onInputChanged(e: XimeInputChangedEvent): void {
      currentInput = e.inputText || '';
    },
  },

  // ===== 面板（display: passive，声明式 ui 节点树） =====
  panel: {
    state(input: XimePanelInput): XimePanelState {
      const title = resolveTitle();
      const hint = nextTitleHint();
      const now = nowSec();
      const ui: XimeUiNode[] = [
        { type: 'section', label: '🏆 称号' },
        { type: 'metric', label: '当前称号', value: title.badge + ' ' + title.name },
      ];
      if (hint !== null) {
        ui.push({ type: 'text', value: hint, style: 'caption' });
      } else {
        ui.push({ type: 'text', value: '👑 已是最高称号', style: 'caption' });
      }

      ui.push({ type: 'section', label: '⚡ 速度' });
      ui.push({ type: 'metric', label: '💨 最近 1 分钟', value: String(currentKpm(now)), unit: '字/分' });

      const t = todayStr();
      ui.push({ type: 'section', label: '📅 今日' });
      ui.push({ type: 'metric', label: '✍️ 输入字数', value: String(daily[t] || 0), unit: '字' });
      ui.push({ type: 'metric', label: '🔢 提交次数', value: String(totalCommits) });

      ui.push({ type: 'section', label: '🗓 近 7 天' });
      ui.push({ type: 'metric', label: '✍️ 输入字数', value: String(sumRecentDays(7)), unit: '字' });

      ui.push({ type: 'section', label: '📆 本月' });
      ui.push({ type: 'metric', label: '✍️ 输入字数', value: String(sumMonth(t.substring(0, 6))), unit: '字' });

      if (currentInput !== '') {
        ui.push({ type: 'text', value: '⌨️ 正在输入: ' + currentInput, style: 'caption' });
      }
      ui.push({ type: 'divider' });
      ui.push({ type: 'button', label: '🗑️ 清零统计', key: 'reset' });

      return { inputText: input.inputText || '', items: [], ui, loading: false };
    },

    onItemClick(_input: XimePanelItemClickEvent): void {
      // passive 面板点击节点不上屏

    },

    onAction(input: XimePanelActionEvent): void {
      if (input.actionId === 'reset') {
        totalChars = 0;
        totalCommits = 0;
        lastSeenChars = null;
        daily = {};
        speedWindow = [];
        persist();
      }
    },
  },
});

export default plugin;
