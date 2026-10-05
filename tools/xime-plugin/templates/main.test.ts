// 插件测试骨架（`xipm test .` 运行，无需真机）。
//
// test / assert / __ximeMock 为测试环境注入的全局（仅 xipm test 存在，真机没有）：
// - test(name, fn)：注册用例，fn 可为 async
// - assert.ok / equal / deepEqual / throws / rejects
// - __ximeMock：mock host 的配置与断言入口（http stub、ws 会话、时钟、请求记录等，
//   类型见 xime-plugin.d.ts 的「测试环境」节）
// - host.* 与真机契约一致（async 服务 reject XimeError）；网络请求必须显式 stub，
//   未 stub 会 reject（绝不会真实联网）

test("面板动作返回预期", () => {
  const p = (globalThis as any).plugin as Record<string, any>;
  assert.equal(typeof p.panel, "object", "面板扩展点存在");
});

test("async 用例：http stub + 请求记录", async () => {
  __ximeMock.addHttpResponse("GET", "https://api.example.com/ping", {
    status: 200,
    text: "pong",
  });

  const resp = await host.http.request("GET", "https://api.example.com/ping", {});
  assert.equal(resp.text, "pong");
  assert.equal(__ximeMock.httpRequests.length, 1);
  assert.equal(__ximeMock.httpRequests[0].url, "https://api.example.com/ping");

  console.log("示例日志（透出到终端）：", host.sdkVersion);
});

test("未 stub 的网络请求 reject E_NETWORK", async () => {
  await assert.rejects(
    host.http.request("GET", "https://api.example.com/other", {}),
    "未注册的 URL 应被拒绝"
  );
});