package com.example.clawlessexplorer.server

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class FileServer(private val rootDir: File) {
    private var server: NettyApplicationEngine? = null

    fun start(port: Int = 8080) {
        server = embeddedServer(Netty, host = "0.0.0.0", port = port) {
            install(ContentNegotiation) { json() }

            routing {
                get("/") { call.respondText(WEB_UI, ContentType.Text.Html) }

                get("/api/info") {
                    call.respondText("""{"name":"Clawless Explorer Server","root":"${rootDir.absolutePath}","lanUrl":"http://${lanIp()}:${port}/"}""")
                }

                get("/api/files") {
                    val path = call.request.queryParameters["path"] ?: "/"
                    val directory = File(rootDir, path.trimStart('/'))
                    if (!directory.exists() || !directory.isDirectory) {
                        call.respond(emptyList<Map<String, Any>>())
                        return@get
                    }
                    val showHidden = call.request.queryParameters["hidden"] == "true"
                    val files = (directory.listFiles() ?: emptyArray())
                        .filter { showHidden || !it.name.startsWith(".") }
                        .map {
                            mapOf(
                                "name" to it.name,
                                "isDirectory" to it.isDirectory,
                                "size" to it.length(),
                                "lastModified" to it.lastModified(),
                                "path" to it.absolutePath.removePrefix(rootDir.absolutePath).trimStart('/')
                            )
                        }
                    call.respond(files)
                }

                get("/api/tree") {
                    val path = call.request.queryParameters["path"] ?: "/"
                    val maxDepth = (call.request.queryParameters["depth"]?.toIntOrNull() ?: 2).coerceIn(1, 6)
                    val directory = File(rootDir, path.trimStart('/'))
                    val tree = if (directory.exists() && directory.isDirectory) {
                        buildTree(directory, maxDepth)
                    } else emptyMap()
                    call.respond(tree)
                }

                get("/api/search") {
                    val q = call.request.queryParameters["q"]?.lowercase().orEmpty()
                    if (q.isBlank()) { call.respond(emptyList<Map<String, Any>>()); return@get }
                    val hits = mutableListOf<Map<String, Any>>()
                    rootDir.walkTopDown()
                        .maxDepth(5)
                        .filter { it.name.lowercase().contains(q) }
                        .take(200)
                        .forEach {
                            hits.add(mapOf(
                                "name" to it.name,
                                "isDirectory" to it.isDirectory,
                                "size" to it.length(),
                                "path" to it.absolutePath.removePrefix(rootDir.absolutePath).trimStart('/')
                            ))
                        }
                    call.respond(hits)
                }

                get("/download") {
                    val path = call.request.queryParameters["path"] ?: return@get call.respondText(
                        "Path missing", status = HttpStatusCode.BadRequest
                    )
                    val file = resolveSafe(path) ?: return@get call.respondText(
                        "Invalid path", status = HttpStatusCode.BadRequest
                    )
                    if (!file.exists() || !file.isFile) {
                        call.respondText("File not found", status = HttpStatusCode.NotFound); return@get
                    }
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, file.name).toString()
                    )
                    call.respondFile(file)
                }

                post("/api/copy") {
                    val body = call.receive<CopyRequest>()
                    val src = resolveSafe(body.src) ?: return@post call.respondText(
                        "Invalid src", status = HttpStatusCode.BadRequest
                    )
                    val dstDir = resolveSafe(body.dst) ?: return@post call.respondText(
                        "Invalid dst", status = HttpStatusCode.BadRequest
                    )
                    if (!src.exists()) {
                        call.respondText("Source not found", status = HttpStatusCode.NotFound); return@post
                    }
                    if (!dstDir.isDirectory) {
                        call.respondText("Destination is not a folder", status = HttpStatusCode.BadRequest); return@post
                    }
                    val target = File(dstDir, src.name)
                    if (target.exists() && !body.overwrite) {
                        call.respondText("Target already exists", status = HttpStatusCode.Conflict); return@post
                    }
                    val ok = withContext(Dispatchers.IO) { copyRecursively(src, target) }
                    if (ok) call.respondText("""{"ok":true,"path":"${target.absolutePath.removePrefix(rootDir.absolutePath).trimStart('/')}"}""")
                    else call.respondText("Copy failed", status = HttpStatusCode.InternalServerError)
                }

                post("/api/move") {
                    val body = call.receive<CopyRequest>()
                    val src = resolveSafe(body.src) ?: return@post call.respondText(
                        "Invalid src", status = HttpStatusCode.BadRequest
                    )
                    val dstDir = resolveSafe(body.dst) ?: return@post call.respondText(
                        "Invalid dst", status = HttpStatusCode.BadRequest
                    )
                    if (!src.exists()) {
                        call.respondText("Source not found", status = HttpStatusCode.NotFound); return@post
                    }
                    if (!dstDir.isDirectory) {
                        call.respondText("Destination is not a folder", status = HttpStatusCode.BadRequest); return@post
                    }
                    val target = File(dstDir, src.name)
                    if (target.exists() && !body.overwrite) {
                        call.respondText("Target already exists", status = HttpStatusCode.Conflict); return@post
                    }
                    if (src.renameTo(target)) {
                        call.respondText("""{"ok":true,"path":"${target.absolutePath.removePrefix(rootDir.absolutePath).trimStart('/')}"}""")
                    } else {
                        call.respondText("Move failed", status = HttpStatusCode.InternalServerError)
                    }
                }

                delete("/api/delete") {
                    val path = call.request.queryParameters["path"] ?: return@delete call.respondText(
                        "Path missing", status = HttpStatusCode.BadRequest
                    )
                    val target = resolveSafe(path) ?: return@delete call.respondText(
                        "Invalid path", status = HttpStatusCode.BadRequest
                    )
                    if (!target.exists()) {
                        call.respondText("Not found", status = HttpStatusCode.NotFound); return@delete
                    }
                    val ok = withContext(Dispatchers.IO) { target.deleteRecursively() }
                    if (ok) call.respondText("""{"ok":true}""")
                    else call.respondText("Delete failed", status = HttpStatusCode.InternalServerError)
                }

                put("/api/upload") {
                    val name = call.request.queryParameters["name"]
                        ?: return@put call.respondText("name missing", status = HttpStatusCode.BadRequest)
                    val relPath = call.request.queryParameters["path"].orEmpty().trimStart('/')
                    val destDir = if (relPath.isEmpty()) rootDir else (resolveSafe(relPath) ?: return@put call.respondText(
                        "Invalid path", status = HttpStatusCode.BadRequest
                    ))
                    if (!destDir.isDirectory) {
                        call.respondText("Destination is not a folder", status = HttpStatusCode.BadRequest); return@put
                    }
                    val safeName = name.replace("/", "_").replace("..", "_")
                    val target = File(destDir, safeName)
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            call.receiveStream().use { input ->
                                target.outputStream().use { output -> input.copyTo(output) }
                            }
                            true
                        }.getOrDefault(false)
                    }
                    if (ok) call.respondText("""{"ok":true,"path":"${target.absolutePath.removePrefix(rootDir.absolutePath).trimStart('/')}","size":${target.length()}""".let { "$it}" })
                    else call.respondText("Upload failed", status = HttpStatusCode.InternalServerError)
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
    }

    /** Resolve a relative path safely, blocking escape above the root. */
    private fun resolveSafe(relPath: String): File? {
        val cleaned = relPath.trimStart('/').replace("..", "")
        val resolved = File(rootDir, cleaned).canonicalFile
        val root = rootDir.canonicalFile
        return if (resolved.absolutePath.startsWith(root.absolutePath)) resolved else null
    }

    private fun copyRecursively(src: File, dst: File): Boolean {
        if (src.isDirectory) {
            if (!dst.exists() && !dst.mkdirs()) return false
            src.listFiles()?.forEach { child ->
                if (!copyRecursively(child, File(dst, child.name))) return false
            }
            return true
        }
        return try {
            src.inputStream().use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) { false }
    }

    private fun buildTree(dir: File, maxDepth: Int): Map<String, Any> {
        val children = if (maxDepth > 0) {
            dir.listFiles()
                ?.filter { !it.name.startsWith(".") }
                ?.map { buildTree(it, maxDepth - 1) }
                ?: emptyList()
        } else emptyList()
        return mapOf(
            "name" to dir.name,
            "isDirectory" to true,
            "size" to dir.length(),
            "children" to children
        )
    }

    private fun lanIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        } catch (e: Exception) { null }
    }

    @kotlinx.serialization.Serializable
    private data class CopyRequest(val src: String, val dst: String, val overwrite: Boolean = false)

    companion object {
        private val WEB_UI = """
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Clawless Explorer</title>
<style>
  :root {
    --bg: #0E0F18; --surface: #16171F; --surface-2: #1E1F28;
    --on-surface: #E8E9F0; --on-variant: #A8AAB8;
    --primary: #5B5BF6; --primary-container: #2D2D80; --on-primary: #FFFFFF;
    --secondary: #FF6B9D; --tertiary: #00C8B4;
    --error: #FF6B6B; --success: #10B981;
    --outline: #2D2E3A;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { background: var(--bg); color: var(--on-surface); font: 14px/1.5 -apple-system, "Segoe UI", system-ui, sans-serif; }
  header {
    background: linear-gradient(135deg, var(--primary) 0%, #7B5BF6 50%, var(--secondary) 100%);
    padding: 20px 24px 28px; border-radius: 0 0 28px 28px;
    box-shadow: 0 4px 16px rgba(91,91,246,0.4);
  }
  header h1 { font-size: 22px; font-weight: 800; letter-spacing: -0.02em; }
  header p { margin-top: 4px; font-size: 13px; opacity: 0.85; }
  .path-bar {
    margin: 18px 24px 12px; display: flex; gap: 6px; align-items: center;
    font-size: 13px; color: var(--on-variant); overflow-x: auto;
  }
  .path-bar span.crumb { background: var(--surface-2); padding: 6px 12px; border-radius: 999px; cursor: pointer; white-space: nowrap; }
  .path-bar span.crumb:hover { background: var(--primary); color: white; }
  .toolbar { margin: 0 24px 12px; display: flex; gap: 8px; flex-wrap: wrap; }
  .toolbar input[type=text] {
    flex: 1; min-width: 200px; background: var(--surface-2); color: var(--on-surface);
    border: 1px solid var(--outline); border-radius: 12px; padding: 10px 14px; font-size: 14px;
  }
  .toolbar button {
    background: var(--primary); color: white; border: none; border-radius: 12px;
    padding: 10px 16px; font-size: 14px; font-weight: 600; cursor: pointer; letter-spacing: 0.01em;
  }
  .toolbar button.secondary { background: var(--surface-2); color: var(--on-surface); }
  .list { margin: 0 24px 32px; background: var(--surface); border: 1px solid var(--outline); border-radius: 16px; overflow: hidden; }
  .row {
    display: flex; align-items: center; gap: 14px; padding: 12px 16px;
    border-bottom: 1px solid var(--outline); cursor: pointer; transition: background 0.12s;
  }
  .row:last-child { border-bottom: none; }
  .row:hover { background: var(--surface-2); }
  .row .icon { width: 36px; height: 36px; border-radius: 10px; display: grid; place-items: center; flex-shrink: 0; }
  .row .icon.folder { background: #FEF3C7; color: #F59E0B; }
  .row .icon.file { background: #1E1F28; color: var(--on-variant); }
  .row .name { flex: 1; font-weight: 500; font-size: 15px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .row .meta { color: var(--on-variant); font-size: 12px; }
  .row .actions { display: flex; gap: 4px; }
  .row .actions a, .row .actions button {
    background: transparent; color: var(--on-variant); border: none; cursor: pointer;
    padding: 6px 10px; border-radius: 8px; font-size: 12px; text-decoration: none; font-weight: 500;
  }
  .row .actions a:hover, .row .actions button:hover { background: var(--primary-container); color: white; }
  .row .actions button.danger:hover { background: var(--error); color: white; }
  .upload {
    margin: 0 24px 24px; padding: 20px; background: var(--surface); border: 1px dashed var(--outline);
    border-radius: 16px; text-align: center; color: var(--on-variant);
  }
  .upload form { display: flex; flex-direction: column; gap: 8px; align-items: center; }
  .upload input[type=file] { color: var(--on-variant); }
  .toast { position: fixed; bottom: 24px; left: 50%; transform: translateX(-50%); background: var(--surface-2); border: 1px solid var(--outline); padding: 10px 18px; border-radius: 999px; font-size: 13px; opacity: 0; transition: opacity 0.2s; pointer-events: none; }
  .toast.show { opacity: 1; }
</style>
</head>
<body>
<header>
  <h1>Clawless Explorer</h1>
  <p id="rootInfo">LAN file server</p>
</header>
<div class="path-bar" id="pathBar"></div>
<div class="toolbar">
  <input type="text" id="searchInput" placeholder="Search files…">
  <button onclick="doSearch()">Search</button>
  <button class="secondary" onclick="goUp()">↑ Up</button>
  <button class="secondary" onclick="goHome()">Home</button>
</div>
<div class="upload">
  <form id="uploadForm">
    <strong>Upload a file</strong>
    <input type="file" name="file" id="fileInput">
    <button type="button" onclick="uploadFile()">Upload here</button>
    <small id="uploadStatus"></small>
  </form>
</div>
<div class="list" id="fileList"></div>
<div class="toast" id="toast"></div>
<script>
let currentPath = '';

async function loadInfo() {
  const r = await fetch('/api/info'); const j = await r.json();
  document.getElementById('rootInfo').textContent = 'LAN access: ' + j.lanUrl;
}

function buildPathBar() {
  const bar = document.getElementById('pathBar');
  const parts = currentPath.split('/').filter(Boolean);
  let html = '<span class="crumb" onclick="goHome()">Home</span>';
  let acc = '';
  for (const p of parts) {
    acc += '/' + p;
    html += '<span class="crumb" onclick="navigate(`' + acc + '`)">' + p + '</span>';
  }
  bar.innerHTML = html;
}

async function list(path) {
  currentPath = path || '';
  buildPathBar();
  const r = await fetch('/api/files?path=' + encodeURIComponent(currentPath));
  const files = await r.json();
  const list = document.getElementById('fileList');
  if (!files.length) { list.innerHTML = '<div class="row" style="cursor:default;color:var(--on-variant);justify-content:center;">Empty folder</div>'; return; }
  files.sort((a, b) => (b.isDirectory - a.isDirectory) || a.name.localeCompare(b.name));
  list.innerHTML = files.map(f => {
    const isDir = f.isDirectory;
    const path = currentPath + '/' + f.name;
    const size = isDir ? '' : formatSize(f.size);
    const meta = isDir ? 'Folder' : size;
    const icon = isDir ? 'folder' : 'file';
    return `
      <div class="row" onclick="${isDir ? "navigate('" + path + "')" : "void(0)"}">
        <div class="icon ${icon}">${isDir ? '📁' : '📄'}</div>
        <div class="name">${f.name}</div>
        <div class="meta">${meta}</div>
        <div class="actions">
          ${!isDir ? '<a href="/download?path=' + encodeURIComponent(path) + '">Download</a>' : ''}
          <button onclick="event.stopPropagation();copyFile('${path}')">Copy</button>
          <button class="danger" onclick="event.stopPropagation();deleteFile('${path}')">Delete</button>
        </div>
      </div>`;
  }).join('');
}

function navigate(path) { list(path); }
function goHome() { list(''); }
function goUp() {
  const p = currentPath.split('/').filter(Boolean);
  p.pop();
  list(p.join('/'));
}

function formatSize(b) {
  if (b < 1024) return b + ' B';
  if (b < 1048576) return (b/1024).toFixed(1) + ' KB';
  if (b < 1073741824) return (b/1048576).toFixed(1) + ' MB';
  return (b/1073741824).toFixed(2) + ' GB';
}

async function copyFile(path) {
  const dst = prompt('Copy to folder (relative path, e.g. DCIM):', '');
  if (dst === null) return;
  const r = await fetch('/api/copy', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ src: path, dst: dst, overwrite: false })
  });
  if (r.ok) { toast('Copied'); list(currentPath); }
  else toast('Failed: ' + (await r.text()));
}

async function deleteFile(path) {
  if (!confirm('Delete ' + path + '?')) return;
  const r = await fetch('/api/delete?path=' + encodeURIComponent(path), { method: 'DELETE' });
  if (r.ok) { toast('Deleted'); list(currentPath); }
  else toast('Failed: ' + (await r.text()));
}

async function doSearch() {
  const q = document.getElementById('searchInput').value.trim();
  if (!q) return list(currentPath);
  const r = await fetch('/api/search?q=' + encodeURIComponent(q));
  const hits = await r.json();
  const list = document.getElementById('fileList');
  if (!hits.length) { list.innerHTML = '<div class="row" style="cursor:default;color:var(--on-variant);justify-content:center;">No matches</div>'; return; }
  list.innerHTML = hits.map(f => {
    const isDir = f.isDirectory;
    return `<div class="row" onclick="${isDir ? "navigate('" + f.path + "')" : "void(0)"}">
      <div class="icon ${isDir ? 'folder' : 'file'}">${isDir ? '📁' : '📄'}</div>
      <div class="name">${f.name}</div>
      <div class="meta">${f.path}</div>
      <div class="actions">
        ${!isDir ? '<a href="/download?path=' + encodeURIComponent(f.path) + '">Download</a>' : ''}
        <button class="danger" onclick="event.stopPropagation();deleteFile('${f.path}')">Delete</button>
      </div>
    </div>`;
  }).join('');
}

async function uploadFile() {
  const file = document.getElementById('fileInput').files[0];
  if (!file) { toast('Pick a file first'); return; }
  const status = document.getElementById('uploadStatus');
  status.textContent = 'Uploading…';
  // Use fetch with form data — server endpoint is missing a real multipart parser,
  // so for simplicity we send the raw file body and the destination as a header.
  const r = await fetch('/api/upload?name=' + encodeURIComponent(file.name) + '&path=' + encodeURIComponent(currentPath), {
    method: 'PUT',
    headers: { 'Content-Type': file.type || 'application/octet-stream' },
    body: file
  });
  if (r.ok) { status.textContent = 'Uploaded'; toast('Uploaded ' + file.name); list(currentPath); }
  else status.textContent = 'Failed: ' + (await r.text());
}

function toast(msg) {
  const t = document.getElementById('toast');
  t.textContent = msg; t.classList.add('show');
  setTimeout(() => t.classList.remove('show'), 1800);
}

loadInfo();
list('');
</script>
</body>
</html>
        """.trimIndent()
    }
}
