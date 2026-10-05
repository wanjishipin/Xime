// 恶搞兔表情包（QuickJS 脚本）
//
// 资源在 resources/ 下：
//   resources/icon.webp        插件图标
//   resources/emojis/*.jpg     表情图片（宿主渲染）
// 宿主渲染图片，插件只提供路径（host.resource.path）
//
// host.resource.list 为 async 服务（返回 Promise）：onLoad 预加载缓存，
// emoji.query 走同步调用路径（宿主不 await），仅读缓存

const CATEGORY = '恶搞兔';

// 去掉图片扩展名作为表情名（顺序：jpg → png → webp → gif）
function stripExt(name: string): string {
  return name
    .replace(/\.jpg$/, '')
    .replace(/\.png$/, '')
    .replace(/\.webp$/, '')
    .replace(/\.gif$/, '');
}

// onLoad 预加载的资源文件名缓存（query 同步读）
let emojiFiles: string[] = [];

const plugin = definePlugin({
  // 资源目录列表为 async 服务：onLoad 预加载缓存（失败降级为空列表）
  async onLoad(): Promise<void> {
    try {
      emojiFiles = (await host.resource.list('emojis')) ?? [];
    } catch (e) {
      host.logError('resource.list(emojis) 失败: ' + (e as Error).message);
      emojiFiles = [];
    }
  },

  emoji: {
    listCategories(): string[] {
      return [CATEGORY];
    },

    query(q: XimeEmojiQuery): XimeEmojiItem[] {
      const files = emojiFiles;
      const searchText = q.keyword ?? '';
      const topK = q.topK ?? 100;
      const list: XimeEmojiItem[] = [];
      let idx = 0;
      for (const f of files) {
        const name = stripExt(f);
        if (searchText === '' || name.indexOf(searchText) !== -1) {
          list.push({
            id: 'emoji_' + idx,
            text: name,
            insertText: '[表情' + name + ']',
            imageUrl: host.resource.path('emojis/' + f) ?? undefined,
          });
          idx = idx + 1;
        }
        if (list.length >= topK) break;
      }
      return list;
    },

    icon(): XimeIcon {
      return { assetName: 'icon.webp' };
    },
  },
});

export default plugin;
