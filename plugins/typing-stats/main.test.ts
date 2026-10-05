// typing-stats 测试：事件累计 / 粘贴不计 / 面板节点树 / 固定时钟。
// 运行：仓库根 `xipm test plugins/typing-stats`（或全局 `xipm test`）。

test("text_committed 差值累计：首次仅建基准，第二次起累计", () => {
  const p = (globalThis as any).plugin;
  p.events.onTextCommitted({
    committedText: "你好世界",
    sessionTotalChars: 5,
    sessionTotalCommits: 1,
    isPaste: false,
  });
  assert.equal(
    host.config.get("total_chars"),
    "0",
    "首次事件仅建立差值基准，不回溯历史"
  );

  p.events.onTextCommitted({
    committedText: "再来",
    sessionTotalChars: 8,
    sessionTotalCommits: 2,
    isPaste: false,
  });
  assert.equal(host.config.get("total_chars"), "3", "第二次按差值 +3");
});

test("粘贴（isPaste）不计字数也不计提交次数", () => {
  const p = (globalThis as any).plugin;
  const before = parseInt(host.config.get("total_chars") ?? "0");
  const commitsBefore = parseInt(host.config.get("total_commits") ?? "0");
  p.events.onTextCommitted({
    committedText: "粘贴内容",
    sessionTotalChars: before + 10,
    sessionTotalCommits: commitsBefore + 1,
    isPaste: true,
  });
  assert.equal(host.config.get("total_chars"), String(before), "粘贴不计字数");
  assert.equal(host.config.get("total_commits"), String(commitsBefore), "粘贴不计提交数");
});

test("宿主重启（session 归零）按新会话起点累计", () => {
  const p = (globalThis as any).plugin;
  const before = parseInt(host.config.get("total_chars") ?? "0");
  // 模拟重启：session 计数归零（delta 变负 → 按当前快照起算）
  p.events.onTextCommitted({
    committedText: "重启后",
    sessionTotalChars: 3,
    sessionTotalCommits: 1,
    isPaste: false,
  });
  assert.equal(host.config.get("total_chars"), String(before + 3), "重启后本次快照即增量");
});

test("panel.state 返回节点树（含称号/速度/今日区块）", () => {
  const p = (globalThis as any).plugin;
  const state = p.panel.state({});
  assert.ok(Array.isArray(state.ui), "ui 为节点数组");
  const labels = state.ui.map((n: any) => n.label ?? "");
  assert.ok(labels.some((l: string) => l.includes("称号")), "称号区块存在");
  assert.ok(labels.some((l: string) => l.includes("速度")), "速度区块存在");
  assert.ok(labels.some((l: string) => l.includes("今日")), "今日区块存在");
});

test("固定时钟 + reset：当天字数写入 daily（YYYYMMDD 键）", () => {
  __ximeMock.setClock(1789689600); // 2026-09-18T00:00:00Z
  const p = (globalThis as any).plugin;
  // 隔离前置用例状态：清零统计与 lastSeen 基准
  p.panel.onAction({ actionId: "reset" });
  p.events.onTextCommitted({
    committedText: "计时",
    sessionTotalChars: 5,
    sessionTotalCommits: 1,
    isPaste: false,
  }); // 首次建基准
  p.events.onTextCommitted({
    committedText: "计时2",
    sessionTotalChars: 10,
    sessionTotalCommits: 2,
    isPaste: false,
  }); // delta = 5
  const daily = JSON.parse(host.config.get("daily") ?? "{}");
  assert.equal(daily["20260918"], 5, "reset 后新会话增量计入 daily[20260918]");
});