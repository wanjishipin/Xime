// s3-clipboard-sync 测试：SigV4 签名（AWS 官方向量 + 独立实现向量）、
// 对象布局（元数据 + 内容寻址 blob）、条件写乐观锁、附件双向、连接测试、限流退避。
//
// 运行：仓库根 `xipm test plugins/s3-clipboard-sync`。
// 固定向量由 Python 独立实现（hashlib/hmac）算出，见 vector 注释；clock 固定为
// 2026-09-30T12:00:00Z（epoch 1790769600）⇒ x-amz-date 恒为 20260930T120000Z。
//
// 注意：mock 的路由是"先注册先匹配"且跨用例不清空，故每个用例用**独立的 bucket/对象键**；
// 限流用例会污染模块级退避计数，必须放在最后。

import { signRequest } from './libs/sigv4';

const ENDPOINT = 'https://acc123.r2.cloudflarestorage.com';
const AKID = 'AKIAEXAMPLE1234567890';
const SECRET = 'wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY';
const FIXED_EPOCH = 1790769600; // 2026-09-30T12:00:00Z
const AMZ_DATE = '20260930T120000Z';
const IMAGE_HASH = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa';
const IMAGE_NAME = IMAGE_HASH + '.png';
const PNG_MAGIC = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);

interface Bucket {
  key: string;
  url: string;
  blob: (name: string) => string;
  probe: string;
  bucketUrl: string;
}

function plugin(): any {
  return (globalThis as any).plugin;
}

function useBucket(bucket: string, dir?: string): Bucket {
  const prefix = dir === undefined ? 'xime' : dir;
  const key = prefix + '/clipboard.json';
  __ximeMock.setConfig('endpoint', ENDPOINT);
  __ximeMock.setConfig('region', 'auto');
  __ximeMock.setConfig('bucket', bucket);
  __ximeMock.setConfig('dir', prefix);
  __ximeMock.setConfig('accessKeyId', AKID);
  __ximeMock.setConfig('secretAccessKey', SECRET);
  host.config.remove('lastEtag');
  __ximeMock.setClock(FIXED_EPOCH);
  return {
    key: key,
    url: ENDPOINT + '/' + bucket + '/' + key,
    blob: function (name: string) { return ENDPOINT + '/' + bucket + '/' + prefix + '/blobs/' + name; },
    probe: ENDPOINT + '/' + bucket + '/' + prefix + '/.xime-probe',
    bucketUrl: ENDPOINT + '/' + bucket,
  };
}

function requestCount(): number {
  return __ximeMock.httpRequests.length;
}

function requestAt(index: number): XimeTestMockRequest {
  return __ximeMock.httpRequests[index];
}

function lastRequest(): XimeTestMockRequest {
  return requestAt(requestCount() - 1);
}

function textProfile() {
  return {
    type: 'text', hash: 'h1', text: 'hi', hasData: false, dataName: null, size: 2, source: null,
  };
}

// ============================================================
// SigV4：AWS 官方测试向量（aws-sig-v4-test-suite / get-vanilla）
// ============================================================

test('SigV4 命中 AWS 官方测试向量 get-vanilla', () => {
  const result = signRequest({
    method: 'GET',
    canonicalUri: '/',
    headers: { host: 'example.amazonaws.com', 'x-amz-date': '20150830T123600Z' },
    signedHeaders: ['host', 'x-amz-date'],
    payloadHash: 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    dateStamp: '20150830',
    amzDate: '20150830T123600Z',
    region: 'us-east-1',
    service: 'service',
    accessKeyId: 'AKIDEXAMPLE',
    secretAccessKey: 'wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY',
  });
  assert.equal(
    result.authorization,
    'AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, '
      + 'SignedHeaders=host;x-amz-date, '
      + 'Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31',
    '应与 AWS 官方 aws-sig-v4-test-suite 的 get-vanilla 签名一致');
  assert.equal(
    result.canonicalRequest,
    'GET\n/\n\nhost:example.amazonaws.com\nx-amz-date:20150830T123600Z\n\nhost;x-amz-date\n'
      + 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855',
    'canonical request 结构应与 AWS 规范一致（含头尾空行）');
});

// ============================================================
// 文本同步：整条 Authorization 与独立实现对齐
// ============================================================

test('文本 push：Authorization 与独立实现一致，首次用 If-None-Match: * 创建', async () => {
  const b = useBucket('xime-test-auth');
  __ximeMock.addHttpResponse('PUT', b.url, { status: 200, headers: { ETag: '"etag-1"' } });

  const ok = await plugin().clipboardSync.push(textProfile());
  assert.ok(ok === true, 'push 应成功');

  const req = lastRequest();
  assert.equal(req.method, 'PUT');
  assert.equal(req.url, b.url, 'path-style：{endpoint}/{bucket}/{key}');
  assert.equal(
    req.headers['Authorization'],
    'AWS4-HMAC-SHA256 Credential=' + AKID + '/20260930/auto/s3/aws4_request, '
      + 'SignedHeaders=host;x-amz-content-sha256;x-amz-date, '
      + 'Signature=5ce7456f6440041636f31b6911baacba72ce62d700311c8e639524a57dcf4495',
    '签名应与独立 Python 实现对同一请求的计算结果一致');
  assert.equal(req.headers['x-amz-date'], AMZ_DATE, 'amz-date 取宿主 UTC 时钟');
  assert.equal(req.headers['x-amz-content-sha256'],
    '79d828785f2ce626782166f8811677f70ee32b859adb67d27d50c30ac276c20a', 'payload 哈希');
  assert.equal(req.headers['If-None-Match'], '*', '无缓存 ETag 时要求"远端不存在"');
  assert.equal(req.headers['If-Match'], undefined, '不应同时带 If-Match');
  assert.equal(req.text,
    '{"type":"text","hash":"h1","text":"hi","has_data":false,"data_name":null,"size":2,"source":null}',
    'wire JSON 为 snake_case');
  assert.equal(host.config.get('lastEtag'), '"etag-1"', '成功后应缓存 ETag 供乐观锁使用');
});

test('凭据含换行/空白时被清理，签名不受影响', async () => {
  const b = useBucket('xime-test-auth');
  __ximeMock.setConfig('accessKeyId', ' ' + AKID + '\n');
  __ximeMock.setConfig('secretAccessKey', SECRET + '\r\n');
  __ximeMock.addHttpResponse('PUT', b.url, { status: 200, headers: { ETag: '"etag-2"' } });

  const ok = await plugin().clipboardSync.push(textProfile());
  assert.ok(ok === true, '清理空白后应能正常签名');
  const auth = lastRequest().headers['Authorization'];
  assert.equal(auth.indexOf('\n'), -1, 'Authorization 不应含换行');
  assert.equal(
    auth,
    'AWS4-HMAC-SHA256 Credential=' + AKID + '/20260930/auto/s3/aws4_request, '
      + 'SignedHeaders=host;x-amz-content-sha256;x-amz-date, '
      + 'Signature=5ce7456f6440041636f31b6911baacba72ce62d700311c8e639524a57dcf4495',
    '与无空白凭据的签名逐字节一致');
});

test('有缓存 ETag 时 push 用 If-Match，成功后刷新缓存', async () => {
  const b = useBucket('xime-test-lock');
  host.config.set('lastEtag', '"old"');
  __ximeMock.addHttpResponse('PUT', b.url, { status: 200, headers: { ETag: '"new"' } });

  assert.ok(await plugin().clipboardSync.push(textProfile()) === true);
  assert.equal(lastRequest().headers['If-Match'], '"old"', '条件写：只覆盖我看到的那一版');
  assert.equal(lastRequest().headers['If-None-Match'], undefined);
  assert.equal(host.config.get('lastEtag'), '"new"', '成功后缓存新 ETag');
});

test('region 配置进入签名 scope（非 R2 服务需填实际区域）', async () => {
  const b = useBucket('xime-test-region');
  __ximeMock.setConfig('region', 'us-east-1');
  __ximeMock.addHttpResponse('PUT', b.url, { status: 200 });

  await plugin().clipboardSync.push(textProfile());
  assert.ok(
    lastRequest().headers['Authorization'].indexOf('/20260930/us-east-1/s3/aws4_request') >= 0,
    'scope 应使用配置的 region');
});

test('自定义目录（可含子目录）时，元数据与附件共用一个前缀', async () => {
  const b = useBucket('xime-test-dir', 'xime/archive');
  __ximeMock.addHttpResponse('PUT', b.blob(IMAGE_NAME), { status: 200 });
  __ximeMock.addHttpResponse('PUT', b.url, { status: 200 });

  const profile = {
    type: 'image', hash: IMAGE_HASH, text: '', hasData: true, dataName: IMAGE_NAME,
    size: PNG_MAGIC.length, source: null, data: PNG_MAGIC,
  };
  assert.ok(await plugin().clipboardSync.push(profile) === true);
  assert.equal(requestAt(requestCount() - 2).url, ENDPOINT + '/xime-test-dir/xime/archive/blobs/' + IMAGE_NAME);
  assert.equal(lastRequest().url, ENDPOINT + '/xime-test-dir/xime/archive/clipboard.json');
});

test('端点带路径前缀：URL 与 canonical URI 一致（尾部斜杠已归一）', async () => {
  __ximeMock.setConfig('endpoint', 'https://s3.example.com/proxy/');
  __ximeMock.setConfig('bucket', 'xime-test-prefix');
  __ximeMock.setConfig('dir', 'xime');
  __ximeMock.setConfig('accessKeyId', AKID);
  __ximeMock.setConfig('secretAccessKey', SECRET);
  host.config.remove('lastEtag');
  __ximeMock.setClock(FIXED_EPOCH);
  const url = 'https://s3.example.com/proxy/xime-test-prefix/xime/clipboard.json';
  __ximeMock.addHttpResponse('PUT', url, { status: 200 });

  assert.ok(await plugin().clipboardSync.push(textProfile()) === true);
  assert.equal(lastRequest().url, url, '路径式 + 端点路径前缀（尾部斜杠已归一）');
});

// ============================================================
// 附件（图片原图）：blob 先传后写元数据；拉取必须用 resp.body
// ============================================================

test('图片 push：先 PUT blob（带 MIME）再 PUT 元数据', async () => {
  const b = useBucket('xime-test-img');
  __ximeMock.addHttpResponse('PUT', b.blob(IMAGE_NAME), { status: 200 });
  __ximeMock.addHttpResponse('PUT', b.url, { status: 200, headers: { ETag: '"img"' } });

  const before = requestCount();
  const profile = {
    type: 'image', hash: IMAGE_HASH, text: '', hasData: true, dataName: IMAGE_NAME,
    size: PNG_MAGIC.length, source: null, data: PNG_MAGIC,
  };
  assert.ok(await plugin().clipboardSync.push(profile) === true, 'push 应成功');

  assert.equal(requestCount() - before, 2, '恰好两个请求');
  const blobReq = requestAt(before);
  assert.equal(blobReq.method, 'PUT');
  assert.equal(blobReq.url, b.blob(IMAGE_NAME), '附件走内容寻址路径 blobs/<sha256>.<ext>');
  assert.equal(blobReq.headers['Content-Type'], 'image/png', '按扩展名给 MIME');
  assert.equal(blobReq.headers['x-amz-content-sha256'],
    '4c4b6a3be1314ab86138bef4314dde022e600960d8689a2c8f8631802d20dab6', '附件字节的哈希');
  assert.equal(blobReq.body === null ? -1 : blobReq.body.length, PNG_MAGIC.length, '原图字节直传');
  assert.equal(blobReq.headers['If-None-Match'], undefined, '内容寻址 ⇒ 附件不做条件写');

  const wire = JSON.parse(requestAt(before + 1).text ?? '');
  assert.equal(wire.has_data, true);
  assert.equal(wire.data_name, IMAGE_NAME);
  assert.equal(wire.type, 'image');
  assert.equal(wire.text, '', '图片条目的 text 为空串');
});

test('附件未上传成功时绝不写元数据（避免悬空引用）', async () => {
  const b = useBucket('xime-test-dangling');
  __ximeMock.addHttpResponse('PUT', b.blob(IMAGE_NAME), { status: 500 });
  __ximeMock.addHttpResponse('PUT', b.url, { status: 200 });

  const profile = {
    type: 'image', hash: IMAGE_HASH, text: '', hasData: true, dataName: IMAGE_NAME,
    size: PNG_MAGIC.length, source: null, data: PNG_MAGIC,
  };
  const before = requestCount();
  assert.ok(await plugin().clipboardSync.push(profile) === false, 'blob 失败 ⇒ push 失败');
  assert.equal(requestCount() - before, 1, '只发了附件请求，没有写元数据');
  assert.equal(lastRequest().url, b.blob(IMAGE_NAME));
});

test('has_data 为真但缺附件字节时不发请求', async () => {
  useBucket('xime-test-nodata');
  const before = requestCount();
  const profile = {
    type: 'image', hash: IMAGE_HASH, text: '', hasData: true, dataName: IMAGE_NAME,
    size: 0, source: null,
  };
  assert.ok(await plugin().clipboardSync.push(profile) === false);
  assert.equal(requestCount(), before, '不应发出任何请求');
});

test('pull 图片：先 GET 元数据再 GET blob，字节取自 resp.body', async () => {
  const b = useBucket('xime-test-pullimg');
  // 图片条目没有 text 键：不能被误判成纯文本兼容分支
  __ximeMock.addHttpResponse('GET', b.url, {
    status: 200,
    headers: { ETag: '"img-etag"' },
    text: JSON.stringify({
      type: 'image', hash: IMAGE_HASH, has_data: true, data_name: IMAGE_NAME,
      size: PNG_MAGIC.length, source: null,
    }),
  });
  __ximeMock.addHttpResponse('GET', b.blob(IMAGE_NAME), { status: 200, body: PNG_MAGIC });

  const before = requestCount();
  const profile = await plugin().clipboardSync.pull();
  assert.ok(profile !== null, '应返回 profile');
  assert.equal(profile.type, 'image');
  assert.equal(profile.hasData, true);
  assert.equal(profile.text, '');
  assert.equal(profile.dataName, IMAGE_NAME);
  assert.equal(profile.data === undefined ? -1 : profile.data.length, PNG_MAGIC.length, '原图字节');
  assert.equal(profile.data === undefined ? -1 : profile.data[0], 0x89, '二进制未被文本解码破坏');
  assert.equal(requestCount() - before, 2, '元数据 + 附件');
  assert.equal(requestAt(before + 1).url, b.blob(IMAGE_NAME));
  assert.equal(host.config.get('lastEtag'), '"img-etag"', '缓存元数据 ETag');
});

test('pull：附件还没传上来（404）时返回 null 并清掉 ETag，下轮重试', async () => {
  const b = useBucket('xime-test-halfpush');
  __ximeMock.addHttpResponse('GET', b.url, {
    status: 200,
    headers: { ETag: '"half"' },
    text: JSON.stringify({
      type: 'image', hash: IMAGE_HASH, has_data: true, data_name: IMAGE_NAME,
      size: PNG_MAGIC.length, source: null,
    }),
  });
  __ximeMock.addHttpResponse('GET', b.blob(IMAGE_NAME), { status: 404 });

  assert.ok(await plugin().clipboardSync.pull() === null, '附件缺失 ⇒ 本次不同步');
  const etag = host.config.get('lastEtag');
  assert.ok(etag === null || etag === '', '必须清掉 ETag，否则 304 会让失败变成永久跳过');
});

test('pull：元数据声明 has_data 却缺 data_name 时也清掉 ETag（坏记录不被 304 永久跳过）', async () => {
  const b = useBucket('xime-test-badmeta');
  __ximeMock.addHttpResponse('GET', b.url, {
    status: 200,
    headers: { ETag: '"bad"' },
    text: JSON.stringify({
      type: 'image', hash: IMAGE_HASH, has_data: true, size: PNG_MAGIC.length, source: null,
    }),
  });

  assert.ok(await plugin().clipboardSync.pull() === null, '缺附件名 ⇒ 本次不同步');
  const etag = host.config.get('lastEtag');
  assert.ok(etag === null || etag === '', '必须清掉 ETag，否则畸形记录永远不再被检查');
});

// ============================================================
// 文本拉取：304 / 404 / 纯文本兼容
// ============================================================

test('pull：带缓存 ETag 发条件请求，304 表示无变更', async () => {
  const b = useBucket('xime-test-auth');
  host.config.set('lastEtag', '"etag-9"');
  __ximeMock.addHttpResponse('GET', b.url, { status: 304 });

  assert.ok(await plugin().clipboardSync.pull() === null);
  const req = lastRequest();
  assert.equal(req.headers['If-None-Match'], '"etag-9"');
  assert.equal(
    req.headers['Authorization'],
    'AWS4-HMAC-SHA256 Credential=' + AKID + '/20260930/auto/s3/aws4_request, '
      + 'SignedHeaders=host;x-amz-content-sha256;x-amz-date, '
      + 'Signature=11f849f468feafc4ee20b39e267385c351c00deca98a684af5e2e285644b5f7a',
    'GET 空 body 的签名应与独立实现一致');
});

test('pull：远端尚无对象（404）返回 null', async () => {
  const b = useBucket('xime-test-empty');
  __ximeMock.addHttpResponse('GET', b.url, { status: 404 });
  assert.ok(await plugin().clipboardSync.pull() === null);
});

test('pull：文本条目解析为 profile 并缓存 ETag', async () => {
  const b = useBucket('xime-test-text');
  __ximeMock.addHttpResponse('GET', b.url, {
    status: 200,
    headers: { ETag: '"txt"' },
    text: JSON.stringify({
      type: 'text', hash: 'abc', text: '你好', has_data: false, data_name: null,
      size: 6, source: 'phone',
    }),
  });

  const profile = await plugin().clipboardSync.pull();
  assert.ok(profile !== null);
  assert.equal(profile.text, '你好');
  assert.equal(profile.hash, 'abc');
  assert.equal(profile.hasData, false);
  assert.equal(profile.source, 'phone');
  assert.equal(host.config.get('lastEtag'), '"txt"');
});

test('pull：非 JSON 对象按纯文本兼容处理', async () => {
  const b = useBucket('xime-test-plain');
  __ximeMock.addHttpResponse('GET', b.url, { status: 200, text: 'plain clipboard' });

  const profile = await plugin().clipboardSync.pull();
  assert.ok(profile !== null);
  assert.equal(profile.text, 'plain clipboard');
  assert.equal(profile.type, 'text');
  assert.equal(profile.hasData, false);
});

test('pull：403 带 S3 错误码时记日志并返回 null（不中断轮询）', async () => {
  const b = useBucket('xime-test-denied');
  __ximeMock.addHttpResponse('GET', b.url, {
    status: 403,
    text: '<Error><Code>AccessDenied</Code><Message>Access Denied</Message></Error>',
  });
  assert.ok(await plugin().clipboardSync.pull() === null);
});

// ============================================================
// 条件写冲突：412 → HEAD 刷新 ETag → 重试一次
// ============================================================

test('push 条件写冲突（412）：刷新 ETag 后重试一次成功', async () => {
  const b = useBucket('xime-test-conflict');
  host.config.set('lastEtag', '"stale"');
  let putCount = 0;
  __ximeMock.addHttpResponse('PUT', b.url, (req: XimeTestMockRequest) => {
    putCount = putCount + 1;
    if (req.headers['If-Match'] === '"fresh"') {
      return { status: 200, headers: { ETag: '"fresh"' } };
    }
    return { status: 412, text: '<Error><Code>PreconditionFailed</Code></Error>' };
  });
  __ximeMock.addHttpResponse('HEAD', b.url, { status: 200, headers: { ETag: '"fresh"' } });

  assert.ok(await plugin().clipboardSync.push(textProfile()) === true, '重试后应成功');
  assert.equal(putCount, 2, 'PUT 恰好两次');
  assert.equal(lastRequest().headers['If-Match'], '"fresh"', '重试用刷新后的 ETag');
  assert.equal(host.config.get('lastEtag'), '"fresh"');
});

test('push 条件写冲突且刷新失败时放弃本次（等下次剪贴板变化）', async () => {
  const b = useBucket('xime-test-conflict2');
  host.config.set('lastEtag', '"stale"');
  __ximeMock.addHttpResponse('PUT', b.url, { status: 412 });
  __ximeMock.addHttpResponse('HEAD', b.url, { status: 500 });

  assert.ok(await plugin().clipboardSync.push(textProfile()) === false);
  assert.equal(host.config.get('lastEtag'), '"stale"', '刷新失败不应污染本地缓存');
});

// ============================================================
// 连接测试
// ============================================================

test('test()：缺配置时给出明确提示', async () => {
  host.config.remove('endpoint');
  host.config.remove('bucket');
  host.config.remove('accessKeyId');
  host.config.remove('secretAccessKey');
  assert.equal(await plugin().clipboardSync.test(), '未配置 S3 端点（endpoint）');

  __ximeMock.setConfig('endpoint', 'acc123.r2.cloudflarestorage.com');
  const bad = await plugin().clipboardSync.test();
  assert.ok(bad !== null && bad.indexOf('格式不对') >= 0, '端点缺 scheme 应被指出');
});

test('test()：成功路径 = HEAD bucket + 写探针 + 删除探针 + 探测远端对象', async () => {
  const b = useBucket('xime-test-ok');
  __ximeMock.addHttpResponse('HEAD', b.bucketUrl, { status: 200 });
  __ximeMock.addHttpResponse('PUT', b.probe, { status: 200 });
  __ximeMock.addHttpResponse('DELETE', b.probe, { status: 204 });
  __ximeMock.addHttpResponse('HEAD', b.url, { status: 404 });

  const before = requestCount();
  assert.equal(await plugin().clipboardSync.test(), null, '应返回 null 表示成功');
  assert.equal(requestCount() - before, 4, '四个探测请求');
  assert.equal(requestAt(before).url, b.bucketUrl, 'HEAD bucket：验证签名与桶存在');
  assert.equal(requestAt(before + 1).url, b.probe, 'PUT 探针：验证真的有写权限');
  assert.equal(requestAt(before + 1).headers['Content-Type'], 'text/plain');
  assert.equal(requestAt(before + 2).method, 'DELETE', '探针用完即删');
  assert.equal(requestAt(before + 3).url, b.url, '再探测元数据对象是否已存在');
});

test('test()：读得通但无权写时明确报"无权写入"', async () => {
  const b = useBucket('xime-test-readonly');
  __ximeMock.addHttpResponse('HEAD', b.bucketUrl, { status: 200 });
  __ximeMock.addHttpResponse('PUT', b.probe, {
    status: 403,
    text: '<Error><Code>AccessDenied</Code><Message>Access Denied</Message></Error>',
  });

  const message = await plugin().clipboardSync.test();
  assert.ok(message !== null && message.indexOf('无权写入') >= 0, '应指出写权限问题');
  assert.ok(message !== null && message.indexOf('AccessDenied') >= 0, '带上 S3 错误码便于排查');
});

test('test()：HEAD bucket 403 给出排查方向（HEAD 无 body，只能按状态码给）', async () => {
  const b = useBucket('xime-test-badsign');
  // 真机上 HEAD 响应没有 body（HTTP 层不为 HEAD 解析 body），所以这里也不能造 body：
  // 否则断言的是"只有 mock 才有的错误码"，比真机乐观
  __ximeMock.addHttpResponse('HEAD', b.bucketUrl, { status: 403 });

  const message = await plugin().clipboardSync.test();
  assert.ok(message !== null && message.indexOf('鉴权失败') >= 0);
  assert.ok(message !== null && message.indexOf('Secret Access Key') >= 0, '提示检查密钥');
  assert.ok(message !== null && message.indexOf('auto') >= 0, '提示 R2 的 region 用 auto');
});

test('test()：bucket 不存在时明确报错', async () => {
  const b = useBucket('xime-test-nobucket');
  __ximeMock.addHttpResponse('HEAD', b.bucketUrl, {
    status: 404,
    text: '<Error><Code>NoSuchBucket</Code><Message>The specified bucket does not exist</Message></Error>',
  });

  const message = await plugin().clipboardSync.test();
  assert.ok(message !== null && message.indexOf('bucket 不存在') >= 0);
  assert.ok(message !== null && message.indexOf('NoSuchBucket') >= 0);
});

// ============================================================
// 回环：push 写出的元数据必须能被 pull 原样解析回 profile
// （两台设备互相同步的核心契约：写入端 wire JSON ↔ 读取端解码）
// ============================================================

test('回环：文本 push 写出的元数据可被 pull 还原为同一 profile', async () => {
  const b = useBucket('xime-test-roundtrip');
  let written = '';
  __ximeMock.addHttpResponse('PUT', b.url, (req: XimeTestMockRequest) => {
    written = req.text === null ? '' : req.text;
    return { status: 200, headers: { ETag: '"rt"' } };
  });
  // 把刚写入的内容当作"对端设备"读到的版本回放
  __ximeMock.addHttpResponse('GET', b.url, () => {
    return { status: 200, headers: { ETag: '"rt"' }, text: written };
  });

  const pushed = {
    type: 'text', hash: 'hash-rt', text: '回环文本', hasData: false, dataName: null,
    size: 12, source: 'phone-a',
  };
  assert.ok(await plugin().clipboardSync.push(pushed) === true);
  assert.ok(written !== '', 'push 应写出元数据');

  host.config.remove('lastEtag'); // 模拟另一台设备首次拉取
  const pulled = await plugin().clipboardSync.pull();
  assert.ok(pulled !== null, '应能解析自己写出的元数据');
  assert.equal(pulled.type, 'text');
  assert.equal(pulled.text, pushed.text);
  assert.equal(pulled.hash, pushed.hash);
  assert.equal(pulled.size, pushed.size);
  assert.equal(pulled.source, pushed.source);
  assert.equal(pulled.hasData, false);
});

test('回环：图片 push 写出的元数据 + blob 可被 pull 还原为同一份原图字节', async () => {
  const b = useBucket('xime-test-roundtrip-img');
  let written = '';
  __ximeMock.addHttpResponse('PUT', b.blob(IMAGE_NAME), { status: 200 });
  __ximeMock.addHttpResponse('PUT', b.url, (req: XimeTestMockRequest) => {
    written = req.text === null ? '' : req.text;
    return { status: 200, headers: { ETag: '"rt-img"' } };
  });
  __ximeMock.addHttpResponse('GET', b.url, () => {
    return { status: 200, headers: { ETag: '"rt-img"' }, text: written };
  });
  __ximeMock.addHttpResponse('GET', b.blob(IMAGE_NAME), { status: 200, body: PNG_MAGIC });

  const pushed = {
    type: 'image', hash: IMAGE_HASH, text: '', hasData: true, dataName: IMAGE_NAME,
    size: PNG_MAGIC.length, source: null, data: PNG_MAGIC,
  };
  assert.ok(await plugin().clipboardSync.push(pushed) === true);

  host.config.remove('lastEtag');
  const pulled = await plugin().clipboardSync.pull();
  assert.ok(pulled !== null, '应能解析自己写出的图片条目');
  assert.equal(pulled.type, 'image');
  assert.equal(pulled.hasData, true);
  assert.equal(pulled.dataName, IMAGE_NAME);
  assert.equal(pulled.data.length, PNG_MAGIC.length, '附件字节长度一致');
  let identical = true;
  for (let i = 0; i < PNG_MAGIC.length; i++) {
    if (pulled.data[i] !== PNG_MAGIC[i]) identical = false;
  }
  assert.ok(identical, '原图字节应逐字节一致（未被文本解码破坏）');
});

// ============================================================
// 审查修复回归：签名 host 一致性、不可信附件名、状态自愈
// ============================================================

test('签名 host 归一化：大写域名/默认端口与 HTTP 层实际发出的 Host 头一致', async () => {
  // HTTP 层发出的 Host 头 = 域名小写 + 省略 scheme 默认端口；签名必须逐字节一致，否则必然 403
  __ximeMock.setConfig('endpoint', 'https://MinIO.Lan:9000');
  __ximeMock.setConfig('bucket', 'xime-test-host');
  __ximeMock.setConfig('dir', 'xime');
  __ximeMock.setConfig('accessKeyId', AKID);
  __ximeMock.setConfig('secretAccessKey', SECRET);
  host.config.remove('lastEtag');
  __ximeMock.setClock(FIXED_EPOCH);
  const url = 'https://minio.lan:9000/xime-test-host/xime/clipboard.json';
  __ximeMock.addHttpResponse('PUT', url, { status: 200 });

  assert.ok(await plugin().clipboardSync.push(textProfile()) === true);
  const normalized = lastRequest();
  assert.equal(normalized.url, url, '域名统一小写、保留非默认端口');

  // 换成全小写端点再推一次：签名应逐字节相同（说明归一化真的作用于签名，而不只是 URL）
  __ximeMock.setConfig('endpoint', 'https://minio.lan:9000');
  __ximeMock.addHttpResponse('PUT', url, { status: 200 });
  assert.ok(await plugin().clipboardSync.push(textProfile()) === true);
  assert.equal(lastRequest().headers.Authorization, normalized.headers.Authorization,
    '大小写不同的端点必须产生同一签名');

  // https 的默认端口 443 必须省略，否则与 Host 头不一致
  __ximeMock.setConfig('endpoint', 'https://s3.example.com:443');
  const defaultPortUrl = 'https://s3.example.com/xime-test-host/xime/clipboard.json';
  __ximeMock.addHttpResponse('PUT', defaultPortUrl, { status: 200 });
  assert.ok(await plugin().clipboardSync.push(textProfile()) === true);
  assert.equal(lastRequest().url, defaultPortUrl, ':443 已省略');
});

test('非 ASCII 域名：直接报错，不发出必然签名失败的请求', async () => {
  useBucket('xime-test-idn');
  __ximeMock.setConfig('endpoint', 'https://中文.example.com');
  const before = requestCount();

  const message = await plugin().clipboardSync.test();
  assert.ok(message !== null && message.indexOf('punycode') >= 0, '应提示改填 xn-- 形式');
  assert.equal(requestCount(), before, '不应发出请求');
});

test('目录含 ".." 段：拒绝（HTTP 层会归一化路径，签名必然对不上）', async () => {
  useBucket('xime-test-dotseg', 'xime/../evil');
  const before = requestCount();

  assert.ok(await plugin().clipboardSync.push(textProfile()) === false);
  assert.equal(requestCount(), before, '不应发出请求');
});

test('目录留空：收拢到默认目录，绝不写桶根', async () => {
  __ximeMock.setConfig('endpoint', ENDPOINT);
  __ximeMock.setConfig('bucket', 'xime-test-defaultdir');
  __ximeMock.setConfig('dir', '   ');
  __ximeMock.setConfig('accessKeyId', AKID);
  __ximeMock.setConfig('secretAccessKey', SECRET);
  host.config.remove('lastEtag');
  __ximeMock.setClock(FIXED_EPOCH);
  const url = ENDPOINT + '/xime-test-defaultdir/xime/clipboard.json';
  __ximeMock.addHttpResponse('PUT', url, { status: 200 });

  assert.ok(await plugin().clipboardSync.push(textProfile()) === true);
  assert.equal(lastRequest().url, url, '留空即 xime/clipboard.json：不允许把对象散落到桶根');
});

test('目录归一化：前后斜杠与重复斜杠被收拢', async () => {
  __ximeMock.setConfig('endpoint', ENDPOINT);
  __ximeMock.setConfig('bucket', 'xime-test-dirnorm');
  __ximeMock.setConfig('dir', '/xime//sub/');
  __ximeMock.setConfig('accessKeyId', AKID);
  __ximeMock.setConfig('secretAccessKey', SECRET);
  host.config.remove('lastEtag');
  __ximeMock.setClock(FIXED_EPOCH);
  const url = ENDPOINT + '/xime-test-dirnorm/xime/sub/clipboard.json';
  __ximeMock.addHttpResponse('PUT', url, { status: 200 });

  assert.ok(await plugin().clipboardSync.push(textProfile()) === true);
  assert.equal(lastRequest().url, url, '归一化为 xime/sub/clipboard.json');
});

test('push 遇到 404（远端对象已被删）：刷新 ETag 后按新建语义重试', async () => {
  const b = useBucket('xime-test-404');
  host.config.set('lastEtag', '"gone"');
  // mock 的路由是「首个匹配且不消费」，多次响应要用函数 handler 按请求头分支
  let putCount = 0;
  __ximeMock.addHttpResponse('PUT', b.url, (req: XimeTestMockRequest) => {
    putCount = putCount + 1;
    if (req.headers['If-None-Match'] === '*') {
      return { status: 200, headers: { ETag: '"reborn"' } };
    }
    // 拿旧 ETag 去 If-Match 一个已被删除的对象：S3/R2 返回 404（也可能是 412），两条都要能自愈
    return { status: 404 };
  });
  __ximeMock.addHttpResponse('HEAD', b.url, { status: 404 });

  assert.ok(await plugin().clipboardSync.push(textProfile()) === true, '重试后应成功');
  assert.equal(putCount, 2, 'PUT 恰好两次');
  assert.equal(lastRequest().headers['If-None-Match'], '*', '对象不存在 ⇒ 按新建处理');
  assert.equal(host.config.get('lastEtag'), '"reborn"');
});

test('pull 遇到 404（远端对象已被删）：清掉过期 ETag', async () => {
  const b = useBucket('xime-test-pull404');
  host.config.set('lastEtag', '"stale"');
  __ximeMock.addHttpResponse('GET', b.url, { status: 404 });

  assert.equal(await plugin().clipboardSync.pull(), null);
  const etag = host.config.get('lastEtag');
  assert.ok(etag === null || etag === '',
    '必须清掉 ETag，否则下次 push 会拿旧 ETag 去 If-Match 一个不存在的对象');
});

test('pull：远端附件名含 ".." 段时清 ETag 且不抛异常（不可信输入）', async () => {
  const b = useBucket('xime-test-badname');
  __ximeMock.addHttpResponse('GET', b.url, {
    status: 200,
    headers: { ETag: '"badname"' },
    text: JSON.stringify({
      type: 'image', hash: 'h', has_data: true, data_name: '../evil.png', size: 1,
    }),
  });

  assert.equal(await plugin().clipboardSync.pull(), null, '不能拿不可信附件名去拼对象地址');
  const etag = host.config.get('lastEtag');
  assert.ok(etag === null || etag === '', '必须清 ETag，否则坏记录被 304 永久跳过');
});

// ============================================================
// 限流退避（会污染模块级计数 ⇒ 放最后）
// ============================================================

test('pull 遇限流（429）后进入退避：后续 pull 不再发请求', async () => {
  const b = useBucket('xime-test-throttle');
  __ximeMock.addHttpResponse('GET', b.url, { status: 429 });

  assert.ok(await plugin().clipboardSync.pull() === null);
  const after = requestCount();
  assert.ok(await plugin().clipboardSync.pull() === null);
  assert.equal(requestCount(), after, '退避期内不应再发请求');
});

test('push 遇限流（503 SlowDown）后进入退避：后续 push 不再发请求', async () => {
  const b = useBucket('xime-test-throttle2');
  __ximeMock.addHttpResponse('PUT', b.url, {
    status: 503,
    text: '<Error><Code>SlowDown</Code><Message>Please reduce your request rate</Message></Error>',
  });

  assert.ok(await plugin().clipboardSync.push(textProfile()) === false);
  const after = requestCount();
  assert.ok(await plugin().clipboardSync.push(textProfile()) === false);
  assert.equal(requestCount(), after, '退避期内不应再发请求');
});
