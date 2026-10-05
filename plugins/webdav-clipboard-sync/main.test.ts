// webdav-clipboard-sync 测试：Basic Auth 凭据换行清理 + push body 文本保持字符串。
// 运行：仓库根 `xipm test plugins/webdav-clipboard-sync`。

const DAV_URL = 'https://dav.example.com/dav/';
const REMOTE_FILE = 'https://dav.example.com/dav/clipboard/current.json';

function lastRequest(): XimeTestMockRequest {
  return __ximeMock.httpRequests[__ximeMock.httpRequests.length - 1];
}

test('用户名/密码含换行会被清理，Authorization 为单行 Basic 头', async () => {
  __ximeMock.setConfig('davUrl', DAV_URL);
  __ximeMock.setConfig('remotePath', '');
  __ximeMock.setConfig('username', 'alice\n');
  __ximeMock.setConfig('password', 'secret\r\n');
  __ximeMock.addHttpResponse('PUT', REMOTE_FILE, { status: 201 });

  const p = (globalThis as any).plugin;
  const ok = await p.clipboardSync.push({
    type: 'text', hash: 'h1', text: 'hi', hasData: false, dataName: null, size: 2, source: null,
  });
  assert.ok(ok === true, 'push 应成功');

  const auth = lastRequest().headers['Authorization'];
  const expected = 'Basic ' + host.crypto.base64(new TextEncoder().encode('alice:secret'));
  assert.equal(auth, expected, '凭据中的换行应被清除');
  assert.equal(auth.indexOf('\n'), -1, 'Authorization 请求头不应含换行');
});

test('push body 的 text 字段序列化为字符串（纯数字不被当数字）', async () => {
  __ximeMock.setConfig('davUrl', DAV_URL);
  __ximeMock.setConfig('remotePath', '');
  __ximeMock.setConfig('username', '');
  __ximeMock.setConfig('password', '');
  __ximeMock.addHttpResponse('PUT', REMOTE_FILE, { status: 201 });

  const p = (globalThis as any).plugin;
  await p.clipboardSync.push({
    type: 'text', hash: 'h2', text: '13100076519', hasData: false, dataName: null, size: 11, source: null,
  });

  const body = JSON.parse(lastRequest().text ?? '');
  assert.equal(typeof body.text, 'string', 'text 必须序列化为字符串');
  assert.equal(body.text, '13100076519');
});

// ============================================================
// Phase 3 / D12：图片附件（blob 先传后写 JSON、拉取必须用 resp.body）
// 每个用例用**独立的 davUrl**：mock 的路由是"先注册先匹配"且跨用例不清空，
// 复用同一 URL 会让后一个用例匹配到前一个的 stub。
// ============================================================

interface TestEndpoints {
  json: string;
  blob: (name: string) => string;
}

function useBase(hostName: string): TestEndpoints {
  const dav = 'https://' + hostName + '/dav/';
  __ximeMock.setConfig('davUrl', dav);
  __ximeMock.setConfig('remotePath', '');
  __ximeMock.setConfig('username', 'alice');
  __ximeMock.setConfig('password', 'secret');
  return {
    json: dav + 'clipboard/current.json',
    blob: (name: string): string => dav + 'clipboard/blobs/' + name,
  };
}

function imgBytes(): Uint8Array {
  // PNG 魔数 + 若干字节：只验证字节无损，不要求是合法图片
  return new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 7, 8, 9]);
}

function imageProfile(name: string, bytes: Uint8Array): any {
  return {
    type: 'image', hash: 'h9', text: '', hasData: true, dataName: name,
    data: bytes, size: bytes.length, source: null,
  };
}

test('push 有附件时先 PUT blob 再 PUT JSON，字节直传且 Content-Type 按扩展名', async () => {
  const ep = useBase('push-img.example.com');
  const bytes = imgBytes();
  const name = 'aa11bb22.png';
  __ximeMock.addHttpResponse('PUT', ep.blob(name), { status: 201 });
  __ximeMock.addHttpResponse('PUT', ep.json, { status: 201 });
  const before = __ximeMock.httpRequests.length;

  const p = (globalThis as any).plugin;
  const ok = await p.clipboardSync.push(imageProfile(name, bytes));
  assert.ok(ok === true, 'push 应成功');

  const sent = __ximeMock.httpRequests.slice(before);
  assert.equal(sent.length, 2, '应发两次请求（blob + JSON）');
  assert.equal(sent[0].url, ep.blob(name), '必须先写 blob');
  assert.equal(sent[0].headers['Content-Type'], 'image/png', 'Content-Type 应按扩展名推断');
  assert.ok(sent[0].body instanceof Uint8Array, 'blob body 应是 Uint8Array');
  assert.deepEqual(Array.from(sent[0].body as Uint8Array), Array.from(bytes), '字节应原样直传');
  assert.equal(sent[1].url, ep.json, '再写 JSON');

  const body = JSON.parse(sent[1].text ?? '');
  assert.equal(body.has_data, true);
  assert.equal(body.data_name, name);
  assert.equal(body.hash, 'h9');
});

test('blob 上传失败时不写 JSON（不产生指向缺失附件的悬空引用）', async () => {
  const ep = useBase('blob-fail.example.com');
  const name = 'bb22cc33.png';
  __ximeMock.addHttpResponse('PUT', ep.blob(name), { status: 500 });
  const before = __ximeMock.httpRequests.length;

  const p = (globalThis as any).plugin;
  const ok = await p.clipboardSync.push(imageProfile(name, imgBytes()));
  assert.equal(ok, false, 'blob 失败时 push 必须返回 false');

  const sent = __ximeMock.httpRequests.slice(before);
  assert.equal(sent.length, 1, 'blob 失败后不应再写 JSON');
  assert.equal(sent[0].url, ep.blob(name));
});

test('pull 解析 has_data 记录并用 resp.body 取回附件字节', async () => {
  const ep = useBase('pull-img.example.com');
  const bytes = imgBytes();
  const name = 'cc33dd44.png';
  __ximeMock.addHttpResponse('GET', ep.json, {
    status: 200,
    headers: { ETag: '"v1"' },
    text: JSON.stringify({
      type: 'image', hash: 'h9', text: '', has_data: true,
      data_name: name, size: bytes.length, source: null,
    }),
  });
  __ximeMock.addHttpResponse('GET', ep.blob(name), {
    status: 200, headers: { 'Content-Type': 'image/png' }, body: bytes,
  });

  const p = (globalThis as any).plugin;
  const profile = await p.clipboardSync.pull();
  assert.ok(profile !== null, 'has_data 记录必须走 JSON 分支（不能掉进纯文本兼容分支）');
  assert.equal(profile.type, 'image');
  assert.equal(profile.hasData, true);
  assert.equal(profile.dataName, name);
  assert.equal(profile.text, '', '图片 profile 的 text 为空串');
  assert.ok(profile.data instanceof Uint8Array, 'data 应是 Uint8Array');
  assert.deepEqual(Array.from(profile.data as Uint8Array), Array.from(bytes), '字节应无损（不能用 resp.text）');
});

test('pull 附件下载失败时清 ETag 并返回 null（下一轮重试，不永久跳过）', async () => {
  const ep = useBase('pull-blob-404.example.com');
  const name = 'dd44ee55.png';
  __ximeMock.addHttpResponse('GET', ep.json, {
    status: 200,
    headers: { ETag: '"v2"' },
    text: JSON.stringify({
      type: 'image', hash: 'h9', text: '', has_data: true,
      data_name: name, size: 11, source: null,
    }),
  });
  __ximeMock.addHttpResponse('GET', ep.blob(name), { status: 404 });

  const p = (globalThis as any).plugin;
  const profile = await p.clipboardSync.pull();
  assert.ok(profile === null, '附件下载失败应返回 null（不写回空文本）');
  const etag = host.config.get('lastEtag');
  assert.ok(etag === null || etag === '', 'ETag 应被清掉，否则 304 会让这次失败变成永久跳过');
});

test('data_name 非法（路径穿越）时不上传附件', async () => {
  const ep = useBase('bad-name.example.com');
  __ximeMock.addHttpResponse('PUT', ep.json, { status: 201 });
  const before = __ximeMock.httpRequests.length;

  const p = (globalThis as any).plugin;
  const ok = await p.clipboardSync.push(imageProfile('../evil.png', imgBytes()));
  assert.equal(ok, false, '非法附件名必须拒绝');
  assert.equal(__ximeMock.httpRequests.length, before, '不应发出任何请求');
});
