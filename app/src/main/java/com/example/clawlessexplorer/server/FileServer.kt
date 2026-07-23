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
                get("/") { call.respondText(loadWebUi(), ContentType.Text.Html) }

                get("/api/info") {
                    call.respondText(
                        """{"name":"Clawless Explorer Server","root":"${rootDir.absolutePath}","lanUrl":"http://${lanIp()}:${port}/"}"""
                    )
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
                            hits.add(
                                mapOf(
                                    "name" to it.name,
                                    "isDirectory" to it.isDirectory,
                                    "size" to it.length(),
                                    "path" to it.absolutePath.removePrefix(rootDir.absolutePath).trimStart('/')
                                )
                            )
                        }
                    call.respond(hits)
                }

                get("/download") {
                    val path = call.request.queryParameters["path"]
                        ?: return@get call.respondText("Path missing", status = HttpStatusCode.BadRequest)
                    val file = resolveSafe(path)
                        ?: return@get call.respondText("Invalid path", status = HttpStatusCode.BadRequest)
                    if (!file.exists() || !file.isFile) {
                        call.respondText("File not found", status = HttpStatusCode.NotFound); return@get
                    }
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment
                            .withParameter(ContentDisposition.Parameters.FileName, file.name)
                            .toString()
                    )
                    call.respondFile(file)
                }

                post("/api/copy") {
                    val body = call.receive<CopyRequest>()
                    val src = resolveSafe(body.src)
                        ?: return@post call.respondText("Invalid src", status = HttpStatusCode.BadRequest)
                    val dstDir = resolveSafe(body.dst)
                        ?: return@post call.respondText("Invalid dst", status = HttpStatusCode.BadRequest)
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
                    if (ok) call.respondText(
                        """{"ok":true,"path":"${target.absolutePath.removePrefix(rootDir.absolutePath).trimStart('/')}"}"""
                    ) else call.respondText("Copy failed", status = HttpStatusCode.InternalServerError)
                }

                post("/api/move") {
                    val body = call.receive<CopyRequest>()
                    val src = resolveSafe(body.src)
                        ?: return@post call.respondText("Invalid src", status = HttpStatusCode.BadRequest)
                    val dstDir = resolveSafe(body.dst)
                        ?: return@post call.respondText("Invalid dst", status = HttpStatusCode.BadRequest)
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
                        call.respondText(
                            """{"ok":true,"path":"${target.absolutePath.removePrefix(rootDir.absolutePath).trimStart('/')}"}"""
                        )
                    } else {
                        call.respondText("Move failed", status = HttpStatusCode.InternalServerError)
                    }
                }

                delete("/api/delete") {
                    val path = call.request.queryParameters["path"]
                        ?: return@delete call.respondText("Path missing", status = HttpStatusCode.BadRequest)
                    val target = resolveSafe(path)
                        ?: return@delete call.respondText("Invalid path", status = HttpStatusCode.BadRequest)
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
                    val destDir = if (relPath.isEmpty()) rootDir else (resolveSafe(relPath)
                        ?: return@put call.respondText("Invalid path", status = HttpStatusCode.BadRequest))
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
                    if (ok) {
                        val rel = target.absolutePath.removePrefix(rootDir.absolutePath).trimStart('/')
                        call.respondText("""{"ok":true,"path":"$rel","size":${target.length()}}""")
                    } else call.respondText("Upload failed", status = HttpStatusCode.InternalServerError)
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
    }

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

    private fun loadWebUi(): String =
        FileServer::class.java.classLoader!!.getResourceAsStream("web/index.html")!!
            .bufferedReader()
            .use { it.readText() }

    @kotlinx.serialization.Serializable
    private data class CopyRequest(val src: String, val dst: String, val overwrite: Boolean = false)
}
