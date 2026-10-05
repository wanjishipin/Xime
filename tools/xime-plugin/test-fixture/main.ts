// QuickJS 兼容性冒烟样例（TS 模块范式）：
// 覆盖多文件 import、TS 类型剥离、ES2020 目标语法（class fields / 可选链 /
// 空值合并 / BigInt / Set / Map / TypedArray / async / 模板字符串），
// 并探测宿主环境 API（console/TextEncoder/atob 等 polyfill 可用性）。
//
// 由 xipm build 编译为 IIFE 单文件（产物挂载 globalThis.plugin，宿主契约不变）；
// definePlugin 为宿主注入的类型恒等工厂（附加 snapshot 探针方法用于宿主测试）。

import { greet } from './libs/greet';

interface Person {
  name: string;
  age?: number;
}

class Counter {
  private count = 0;

  increment(): number {
    this.count += 1;
    return this.count;
  }

  get value(): number {
    return this.count;
  }
}

const person: Person = { name: 'Xime' };
const counter = new Counter();
counter.increment();
counter.increment();

const msg = greet(person.name ?? 'anon');
const doubled = [1, 2, 3].map((x) => x * 2);
const set = new Set(doubled);
const map = new Map<string, number>([['a', 1]]);
const big = BigInt(2) ** BigInt(64);
const bytes = new Uint8Array([1, 2, 3]);
const opt = person.age?.toString() ?? 'unknown';
const template = `sum=${[...set].reduce((a, b) => a + b, 0)}`;

async function asyncAdd(a: number, b: number): Promise<number> {
  return a + b;
}

const envProbe: Record<string, string> = {
  atob: typeof (globalThis as Record<string, unknown>).atob,
  btoa: typeof (globalThis as Record<string, unknown>).btoa,
  TextEncoder: typeof (globalThis as Record<string, unknown>).TextEncoder,
  TextDecoder: typeof (globalThis as Record<string, unknown>).TextDecoder,
  console: typeof (globalThis as Record<string, unknown>).console,
  setTimeout: typeof (globalThis as Record<string, unknown>).setTimeout,
  URL: typeof (globalThis as Record<string, unknown>).URL,
};

const plugin = definePlugin({
  onLoad(): void {
    // 冒烟夹具无加载逻辑（definePlugin 契约链路验证）
  },
});

// 测试探针（宿主测试直接 call("snapshot")；不属于插件契约，附加在导出对象上）
const probePlugin = {
  ...plugin,
  // 宿主环境能力验证放在 snapshot()：裸 QuickJS 只执行语法部分，宿主调用时验证 polyfill
  snapshot(): Record<string, unknown> {
    let utf8Roundtrip = 'unavailable';
    let base64Roundtrip = 'unavailable';
    let polyfillError = '';
    try {
      utf8Roundtrip = new TextDecoder().decode(new TextEncoder().encode('你好，Xime'));
      base64Roundtrip = atob(btoa('hello xime'));
    } catch (e) {
      polyfillError = String(e && (e as Error).message);
    }
    return {
      greet: msg,
      count: counter.value,
      sum: [...set].reduce((a, b) => a + b, 0),
      mapSize: map.size,
      big: big.toString(),
      byteSum: bytes.reduce((a, b) => a + b, 0),
      opt,
      template,
      asyncFn: typeof asyncAdd,
      envProbe,
      utf8Roundtrip,
      base64Roundtrip,
      polyfillError,
    };
  },

  // async 服务探针（TS 范式）：成功路径 await host.http.request
  async httpProbe(): Promise<string> {
    const resp = await host.http.request('GET', 'https://probe.example/hello');
    return `${resp.status}:${resp.text}`;
  },

  // async 服务探针：失败路径 throw XimeError（code/message/原型链形态）
  async httpProbeFail(): Promise<string> {
    try {
      await host.http.request('GET', 'https://probe.example/fail');
      return 'no-throw';
    } catch (e) {
      const err = e as XimeError;
      const kind = err instanceof XimeError ? 'xime-error' : 'other';
      const isError = err instanceof Error ? 'is-error' : 'not-error';
      return `${err.code}:${err.message}:${kind}:${isError}`;
    }
  },

  // async 服务探针：zlib gzip → gunzip 往返
  async zlibProbe(): Promise<string> {
    const raw = new TextEncoder().encode('你好，zlib');
    const packed = await host.zlib.gzip(raw);
    const unpacked = await host.zlib.gunzip(packed);
    return new TextDecoder().decode(unpacked);
  },

  // async 服务探针：gunzip 非法数据 → throw XimeError
  async zlibFailProbe(): Promise<string> {
    try {
      await host.zlib.gunzip(new Uint8Array([1, 2, 3]));
      return 'no-throw';
    } catch (e) {
      return (e as XimeError).code;
    }
  },

  // async 服务探针：resource.list（async 文件 IO；目录不存在返回空数组）
  async resourceProbe(): Promise<string> {
    const names = await host.resource.list('probe');
    return names.join(',');
  },

  // async 服务探针：ws 连接 → 发送 → 状态（宿主 Fake 注入）
  async wsProbe(): Promise<string> {
    const connected = await host.ws.connect('wss://probe.example/ws');
    const sent = await host.ws.sendText('ping');
    return `${connected}:${sent}:${host.ws.getState()}`;
  },

  // async 服务探针：ws 连接失败 → throw XimeError
  async wsFailProbe(): Promise<string> {
    try {
      await host.ws.connect('wss://probe.example/denied');
      return 'no-throw';
    } catch (e) {
      return (e as XimeError).code + ':' + (e as Error).message;
    }
  },
};

export default probePlugin;
