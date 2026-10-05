import React, { useCallback, useEffect, useRef, useState } from 'react'
import logo from './assets/logo.png'

const ACCEPT = '.yaml,.zip,.tar.gz,.tgz,.gram,.jpg,.jpeg,.png,.xipk,.ttf,.otf,.woff,.woff2'
const SUPPORTED = /\.(yaml|zip|tar\.gz|tgz|gram|jpe?g|png|xipk|ttf|otf|woff2?)$/i

function supported(name) {
  return SUPPORTED.test(name)
}

function uid() {
  return Math.random().toString(36).slice(2, 10)
}

function statusText(status, progress) {
  switch (status) {
    case 'uploading': return `上传中 ${progress}%`
    case 'ok': return '已完成'
    case 'fail': return '失败'
    default: return '待上传'
  }
}

const badgeClass = {
  pending: 'text-gray-400',
  uploading: 'text-blue-500',
  ok: 'text-green-600',
  fail: 'text-red-500',
}

function formatSize(bytes) {
  const b = Number(bytes)
  if (!Number.isFinite(b) || b < 0) return '0 B'
  if (b < 1024) return `${b} B`
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`
  return `${(b / 1024 / 1024).toFixed(2)} MB`
}

function formatTime(ms) {
  if (!ms) return ''
  const d = new Date(ms)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function typeIcon(name, isDir) {
  if (isDir) return '📁'
  const lower = name.toLowerCase()
  if (lower.endsWith('.schema.yaml')) return '⚙️'
  if (lower.endsWith('.dict.yaml') || lower.endsWith('.yaml')) return '📘'
  if (lower.endsWith('.zip')) return '📦'
  if (lower.endsWith('.tar.gz') || lower.endsWith('.tgz')) return '🗜️'
  if (lower.endsWith('.txt') || lower.endsWith('.md')) return '📝'
  if (lower.endsWith('.db')) return '🗄️'
  if (lower.endsWith('.png') || lower.endsWith('.jpg') || lower.endsWith('.webp')) return '🖼️'
  if (lower.endsWith('.ttf') || lower.endsWith('.otf') || lower.endsWith('.woff') || lower.endsWith('.woff2')) return '🔤'
  return '📄'
}

function typeBadge(name, isDir) {
  if (isDir) return '目录'
  const lower = name.toLowerCase()
  if (lower.endsWith('.schema.yaml')) return '方案'
  if (lower.endsWith('.dict.yaml')) return '词典'
  if (lower.endsWith('.zip')) return 'ZIP'
  if (lower.endsWith('.xipk')) return '插件'
  if (lower.endsWith('.tar.gz') || lower.endsWith('.tgz')) return '归档'
  if (lower.endsWith('.yaml')) return '配置'
  if (lower.endsWith('.txt')) return '文本'
  if (lower.endsWith('.png') || lower.endsWith('.jpg') || lower.endsWith('.jpeg') || lower.endsWith('.webp')) return '图片'
  if (lower.endsWith('.ttf') || lower.endsWith('.otf') || lower.endsWith('.woff') || lower.endsWith('.woff2')) return '字体'
  if (lower.endsWith('.xipk')) return '插件'
  return '文件'
}

function countStats(node) {
  let dirs = 0
  let files = 0
  let totalBytes = 0
  const walk = (n) => {
    if (n.isDir) {
      dirs += 1
      n.children?.forEach(walk)
    } else {
      files += 1
      totalBytes += (Number(n.size) || 0)
    }
  }
  walk(node)
  return { dirs, files, totalBytes }
}

export default function App() {
  const inputRef = useRef(null)
  const [files, setFiles] = useState([])
  const [dragOver, setDragOver] = useState(false)
  const [uploading, setUploading] = useState(false)
  const [summary, setSummary] = useState(null)

  const [tree, setTree] = useState(null)
  const [treeErr, setTreeErr] = useState(null)
  const [expanded, setExpanded] = useState({})
  const [preview, setPreview] = useState(null)
  const [loading, setLoading] = useState(false)
  const [stats, setStats] = useState({ dirs: 0, files: 0, totalBytes: 0 })

  const addFiles = useCallback((list) => {
    const invalid = []
    const newFiles = []
    for (const f of list) {
      if (!supported(f.name)) {
        invalid.push(f.name)
        continue
      }
      if (!files.some((x) => x.name === f.name)) {
        newFiles.push({ id: uid(), name: f.name, file: f, status: 'pending', progress: 0 })
      }
    }
    if (invalid.length) alert('不支持的类型: ' + invalid.join(', '))
    if (newFiles.length) setFiles((prev) => [...prev, ...newFiles])
  }, [files])

  const onDrop = useCallback((e) => {
    e.preventDefault()
    setDragOver(false)
    if (e.dataTransfer?.files) addFiles(Array.from(e.dataTransfer.files))
  }, [addFiles])

  const update = useCallback((id, patch) => {
    setFiles((prev) => prev.map((f) => (f.id === id ? { ...f, ...patch } : f)))
  }, [])

  const uploadOne = useCallback((file, id) => {
    const fd = new FormData()
    fd.append('file', file)
    const xhr = new XMLHttpRequest()
    xhr.open('POST', '/upload', true)
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) update(id, { progress: Math.round((e.loaded / e.total) * 100) })
    }
    xhr.onload = () => {
      let ok = false
      try {
        ok = JSON.parse(xhr.responseText)?.success === true
      } catch (_) { /* ignore */ }
      update(id, { progress: 100, status: ok ? 'ok' : 'fail' })
    }
    xhr.onerror = () => update(id, { progress: 100, status: 'fail' })
    update(id, { status: 'uploading', progress: 0 })
    xhr.send(fd)
  }, [update])

  const refreshTree = useCallback(async () => {
    setLoading(true)
    setTreeErr(null)
    try {
      const res = await fetch('/tree')
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      const data = await res.json()
      setTree(data)
      setStats(countStats(data))
    } catch (e) {
      setTreeErr(String(e.message || e))
    } finally {
      setLoading(false)
    }
  }, [])

  const upload = useCallback(() => {
    if (!files.length || uploading) return
    setUploading(true)
    setSummary(null)
    const pending = files.filter((f) => f.status !== 'ok')
    pending.forEach((f) => uploadOne(f.file, f.id))

    const timer = setInterval(() => {
      setFiles((prev) => {
        const done = prev.filter((f) => f.status === 'ok' || f.status === 'fail').length
        const total = prev.length
        if (done === total) {
          clearInterval(timer)
          const ok = prev.filter((f) => f.status === 'ok').length
          const fail = total - ok
          setSummary({ ok, fail })
          setUploading(false)
          refreshTree()
          if (fail === 0) return []
        }
        return prev
      })
    }, 200)
  }, [files, uploading, uploadOne, refreshTree])

  useEffect(() => {
    refreshTree()
    const t = setInterval(refreshTree, 5000)
    return () => clearInterval(t)
  }, [refreshTree])

  const toggleDir = useCallback((node) => {
    if (!node.isDir) return
    setExpanded((prev) => ({ ...prev, [node.path]: !prev[node.path] }))
  }, [])

  const openFile = useCallback(async (node) => {
    try {
      const res = await fetch(`/read?path=${encodeURIComponent(node.path)}`)
      if (!res.ok) throw new Error(`HTTP ${res.status}`)
      setPreview({ node, text: await res.text() })
    } catch (e) {
      alert('打开文件失败: ' + (e.message || e))
    }
  }, [])

  const downloadFile = useCallback((node) => {
    const a = document.createElement('a')
    a.href = `/download?path=${encodeURIComponent(node.path)}`
    a.download = node.name
    document.body.appendChild(a)
    a.click()
    a.remove()
  }, [])

  const deleteNode = useCallback(async (node) => {
    const label = node.isDir ? '目录' : '文件'
    if (!window.confirm(`确定删除${label} ${node.name} 吗？`)) return
    try {
      const res = await fetch('/delete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path: node.path }),
      })
      const data = await res.json()
      if (data.success) {
        refreshTree()
      } else {
        alert('删除失败: ' + (data.error || 'unknown'))
      }
    } catch (e) {
      alert('删除失败: ' + (e.message || e))
    }
  }, [refreshTree])

  return (
    <div className="flex h-full">
      {/* 左栏：Logo + 上传，贴左、占满高度 */}
      <div className="flex h-full w-[480px] shrink-0 flex-col border-r border-gray-200 bg-white p-8">
        <div className="mb-8 flex flex-col items-center gap-3 text-center">
          <img src={logo} alt="Xime 输入法" className="h-28 w-28 rounded-3xl object-contain" />
          <div>
            <div className="text-xl font-semibold text-gray-900">Xime 输入法</div>
            <div className="text-sm text-gray-400">无线导入方案</div>
          </div>
        </div>

        <h1 className="mb-2 text-center text-xl font-semibold text-gray-900">导入输入方案</h1>
        <p className="mb-6 text-center text-sm text-gray-500">
          将方案压缩包或者 xime.custom.yaml 拖拽到下方，或点击选择
        </p>

        <div
          onClick={() => inputRef.current?.click()}
          onDragOver={(e) => { e.preventDefault(); setDragOver(true) }}
          onDragLeave={() => setDragOver(false)}
          onDrop={onDrop}
          className={
            'flex min-h-[20rem] cursor-pointer flex-col items-center justify-center rounded-xl border-2 border-dashed p-10 text-center transition-all ' +
            (dragOver
              ? 'border-blue-500 bg-blue-50'
              : files.length
                ? 'border-green-500 bg-green-50'
                : 'border-gray-300')
          }
        >
          <div className="mb-3 text-4xl text-gray-400">📄</div>
          <div className="text-sm text-gray-400">拖拽文件到此处</div>
          <div className="mt-2 text-xs text-gray-300">          支持 .yaml / .zip / .tar.gz / 插件(.xipk) / 图片(.jpg/.png) / 字体(.ttf/.otf)</div>
        </div>

        <input
          ref={inputRef}
          type="file"
          accept={ACCEPT}
          multiple
          className="hidden"
          onChange={(e) => { if (e.target.files) addFiles(Array.from(e.target.files)); e.target.value = '' }}
        />

        {files.length > 0 && (
          <div className="mt-4 space-y-2">
            {files.map((f) => (
              <div key={f.id} className="relative flex items-center justify-between overflow-hidden rounded-lg bg-gray-100 px-3 py-2 text-xs">
                <div className="absolute inset-y-0 left-0 bg-blue-200 transition-all" style={{ width: `${f.progress}%` }} />
                <span className="relative z-10 text-gray-900">{f.name}</span>
                <span className={'relative z-10 ml-2 whitespace-nowrap ' + badgeClass[f.status]}>
                  {statusText(f.status, f.progress)}
                </span>
              </div>
            ))}
          </div>
        )}

        <button
          disabled={!files.length || uploading}
          onClick={upload}
          className="mt-4 w-full rounded-xl bg-blue-500 py-3 text-base font-medium text-white transition-colors hover:bg-blue-600 disabled:cursor-default disabled:bg-gray-300"
        >
          {uploading ? '上传中...' : '上传'}
        </button>

        <div className="mt-4 rounded-lg bg-gray-100 p-2.5 text-left text-xs leading-relaxed text-gray-400">
          <div>💡 上传文件会自动分类放置：</div>
          <div>· 方案压缩包(.zip/.tar.gz) → market 目录</div>
          <div>· 配置文件(.yaml) → rime 目录</div>
          <div>· 图片(.jpg/.png) → themes 目录</div>
          <div>· 字体(.ttf/.otf) → fonts 目录</div>
        </div>

        {summary && (
          <div
            className={
              'mt-4 rounded-lg py-3 text-center text-sm ' +
              (summary.fail ? 'bg-red-50 text-red-500' : 'bg-green-50 text-green-600')
            }
          >
            {summary.ok}/{summary.ok + summary.fail} 个成功
            {summary.fail ? `，${summary.fail} 个失败` : '，请在手机上部署'}
          </div>
        )}
      </div>

      {/* 右栏：数据目录，填满剩余空间 */}
      <div className="flex h-full min-w-0 flex-1 flex-col p-6">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-base font-semibold text-gray-900">数据目录</h2>
          <div className="flex items-center gap-2">
            {loading && <span className="text-xs text-gray-400">刷新中...</span>}
            <button
              onClick={refreshTree}
              className="rounded-lg bg-gray-100 px-3 py-1 text-xs text-gray-600 hover:bg-gray-200"
            >
              刷新
            </button>
          </div>
        </div>

        {tree && (
          <div className="mb-4 flex gap-5 text-sm text-gray-500">
            <span className="inline-flex items-center gap-1.5"><span className="text-gray-400">📁</span>{stats.dirs} 目录</span>
            <span className="inline-flex items-center gap-1.5"><span className="text-gray-400">📄</span>{stats.files} 文件</span>
            <span className="inline-flex items-center gap-1.5"><span className="text-gray-400">📦</span>{formatSize(stats.totalBytes)}</span>
          </div>
        )}

        <div className="min-h-0 flex-1 overflow-auto rounded-2xl bg-white p-4 shadow-lg">
          {treeErr ? (
            <div className="rounded-lg bg-red-50 py-3 text-center text-sm text-red-500">加载失败: {treeErr}</div>
          ) : tree ? (
            <div>
              <FileTreeNode
                node={tree}
                depth={0}
                expanded={expanded}
                onToggle={toggleDir}
                onOpen={openFile}
                onDelete={deleteNode}
                onDownload={downloadFile}
              />
            </div>
          ) : (
            <div className="py-8 text-center text-sm text-gray-400">加载中...</div>
          )}

          {preview && (
            <div className="mt-4 rounded-xl border border-gray-200">
              <div className="flex items-center justify-between border-b border-gray-200 bg-gray-50 px-3 py-2 text-xs">
                <span className="font-medium text-gray-700">{preview.node.path}</span>
                <button onClick={() => setPreview(null)} className="text-gray-400 hover:text-gray-600">✕</button>
              </div>
              <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-all p-3 text-xs text-gray-700">
                {preview.text}
              </pre>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}

function FileTreeNode({ node, depth, expanded, onToggle, onOpen, onDelete, onDownload }) {
  const isExpanded = expanded[node.path]
  const padding = { paddingLeft: `${depth * 20 + 4}px` }

  return (
    <div>
      <div
        className="group flex items-center gap-2 rounded-md py-2 pr-2 hover:bg-gray-100"
        style={padding}
      >
        {node.isDir ? (
          <button
            onClick={() => onToggle(node)}
            className="w-5 text-center text-sm text-gray-400 hover:text-gray-600"
          >
            {isExpanded ? '▾' : '▸'}
          </button>
        ) : (
          <span className="w-5 text-center text-sm text-gray-300">·</span>
        )}
        <span className="text-base leading-none">{typeIcon(node.name, node.isDir)}</span>
        <span
          className={
            'min-w-0 flex-1 truncate text-[15px] ' +
            (node.isDir ? 'cursor-pointer font-semibold text-gray-800' : 'cursor-pointer text-gray-600')
          }
          onClick={() => (node.isDir ? onToggle(node) : onOpen(node))}
          title={node.path}
        >
          {node.name}
        </span>

        {node.isDir ? (
          <span className="shrink-0 text-xs text-gray-400">
            {node.children?.length ?? 0} 项
          </span>
        ) : (
          <>
            <span className="shrink-0 text-xs text-gray-400">{formatSize(node.size)}</span>
            <span className="hidden shrink-0 rounded bg-gray-100 px-1.5 py-0.5 text-[11px] text-gray-500 md:inline">
              {typeBadge(node.name, false)}
            </span>
          </>
        )}

        <span className="hidden shrink-0 text-xs text-gray-300 lg:inline">
          {formatTime(node.mtime)}
        </span>

        {!node.isDir && (
          <button
            onClick={() => onOpen(node)}
            className="hidden rounded px-1.5 text-sm text-blue-500 hover:bg-blue-50 group-hover:inline"
          >
            查看
          </button>
        )}
        {!node.isDir && (
          <button
            onClick={() => onDownload(node)}
            className="hidden rounded px-1.5 text-sm text-emerald-600 hover:bg-emerald-50 group-hover:inline"
          >
            下载
          </button>
        )}
        <button
          onClick={() => onDelete(node)}
          className="hidden rounded px-1.5 text-sm text-red-500 hover:bg-red-50 group-hover:inline"
        >
          删除
        </button>
      </div>
      {node.isDir && isExpanded && node.children && (
        <div>
          {node.children.length === 0 ? (
            <div className="py-1.5 text-sm text-gray-300" style={{ paddingLeft: `${(depth + 1) * 20 + 20}px` }}>
              空目录
            </div>
          ) : (
            node.children.map((c) => (
              <FileTreeNode
                key={c.path}
                node={c}
                depth={depth + 1}
                expanded={expanded}
                onToggle={onToggle}
                onOpen={onOpen}
                onDelete={onDelete}
                onDownload={onDownload}
              />
            ))
          )}
        </div>
      )}
    </div>
  )
}
