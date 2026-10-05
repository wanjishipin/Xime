// 插件入口（TypeScript 模块）
//
// 用 definePlugin 定义扩展点并 default 导出；构建（xipm build）产物为
// `var plugin = (function () { ... })();`——QuickJS 脚本模式下即 globalThis.plugin。
//
// 扩展点（见 xime-plugin.d.ts 与插件开发指南）：
//   - 生命周期：onLoad / onUnload（可 async）
//   - 事件：events.onXxx（manifest capabilities.events 声明后投递）
//   - 工具面板：panel.state / panel.onAction / panel.onInput / panel.onItemClick
//   - emoji：emoji.listCategories / emoji.query / emoji.icon
//   - 网络：host.http（async，失败 throw XimeError）/ host.ws / host.crypto
//
// TS 范式：host 网络/IO 为 async（await + try/catch XimeError）；纯计算 API 同步。
// 语言基线 ES2020：class / async / 可选链 / 空值合并 / BigInt / Set / Map 均可用；
// 无 setTimeout / URL / fetch / Intl（网络与 IO 走 host 白名单）。

const plugin = definePlugin({
  onLoad(): void {
    host.log('示例插件已加载');
  },

  panel: {
    state(input: XimePanelInput): XimePanelState {
      // async 亦可（宿主等待）：async state(input): Promise<XimePanelState>
      return {
        items: [],
        ui: [
          { type: 'section', label: '示例插件' },
          { type: 'text', value: '修改 main.ts 后运行 xipm build', style: 'caption' },
          { type: 'divider' },
          { type: 'button', label: '测试按钮', key: 'demo' },
        ],
        loading: false,
      };
    },

    onAction(input: XimePanelActionEvent): void {
      // 长任务（网络请求）建议改 async 并 await：async onAction(input): Promise<void>
      host.log('面板操作: ' + input.actionId);
    },
  },
});

export default plugin;
