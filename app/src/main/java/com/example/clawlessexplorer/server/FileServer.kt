package com.example.clawlessexplorer.server

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FileServer(private val rootDir: File) {
    private var server: NettyApplicationEngine? = null

    fun start(port: Int = 8080) {
        server = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                json()
            }
            routing {
                get("/") {
                    call.respondText("Clawless Explorer Server Running")
                }
                get("/files") {
                    val path = call.parameters["path"] ?: "/"
                    val directory = File(rootDir, path)
                    if (directory.exists() && directory.isDirectory) {
                        val files = directory.listFiles()?.map { 
                            mapOf("name" to it.name, "isDirectory" to it.isDirectory, "size" to it.length())
                        } ?: emptyList()
                        call.respond(files)
                    } else {
                        call.respond(listOf<Map<String, Any>>())
                    }
                }
                get("/download") {
                    val path = call.parameters["path"] ?: return@get call.respondText("Path missing", status = io.ktor.http.HttpStatusCode.BadRequest)
                    val file = File(rootDir, path)
                    if (file.exists() && file.isFile) {
                        call.response.header(
                            io.ktor.http.HttpHeaders.ContentDisposition,
                            io.ktor.http.ContentDisposition.Attachment.withParameter(io.ktor.http.ContentDisposition.Parameters.FileName, file.name).toString()
                        )
                        call.respondFile(file)
                    } else {
                        call.respondText("File not found", status = io.ktor.http.HttpStatusCode.NotFound)
                    }
                }
            }
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
    }
}
