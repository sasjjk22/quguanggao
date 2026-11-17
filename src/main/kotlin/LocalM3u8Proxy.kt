package com.myplugin

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

object LocalM3u8Proxy {

    private const val MAX_CACHE_SIZE = 20
    private val playlistStore = ConcurrentHashMap<String, String>()
    private val keyQueue = ArrayDeque<String>()

    @Volatile
    private var port: Int = -1

    @Volatile
    private var started = false

    private val executor = Executors.newCachedThreadPool()

    @Synchronized
    private fun ensureStarted() {
        if (started && port > 0) return
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        port = server.localPort
        started = true

        Thread({
            while (!server.isClosed) {
                runCatching {
                    val client = server.accept()
                    executor.execute { handleClient(client) }
                }
            }
        }, "CatM3u8Proxy").apply { isDaemon = true }.start()
    }

    /**
     * 注册一段清洗后的 M3U8 内容，返回可交给播放器直接播放的本地 URL
     */
    fun register(m3u8Content: String): String {
        ensureStarted()
        val id = UUID.randomUUID().toString().replace("-", "")
        synchronized(keyQueue) {
            keyQueue.addLast(id)
            while (keyQueue.size > MAX_CACHE_SIZE) {
                val oldest = keyQueue.removeFirst()
                playlistStore.remove(oldest)
            }
        }
        playlistStore[id] = m3u8Content
        return "http://127.0.0.1:$port/m3u8/$id.m3u8"
    }

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return

            // 读完客户端请求头，避免连接重置
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0)?.uppercase() ?: "GET"
            val path = parts.getOrNull(1)?.substringBefore("?") ?: ""
            val id = path.removePrefix("/m3u8/").removeSuffix(".m3u8")
            val bodyText = playlistStore[id]

            val out = s.getOutputStream()
            if (bodyText == null) {
                val msg = "Not Found".toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 404 Not Found\r\n" +
                    "Content-Length: ${msg.size}\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.US_ASCII))
                if (method != "HEAD") out.write(msg)
            } else {
                val bytes = bodyText.toByteArray(Charsets.UTF_8)
                val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/vnd.apple.mpegurl; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Cache-Control: no-cache\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(header.toByteArray(Charsets.US_ASCII))
                if (method != "HEAD") out.write(bytes)
            }
            out.flush()
        }
    }
}
