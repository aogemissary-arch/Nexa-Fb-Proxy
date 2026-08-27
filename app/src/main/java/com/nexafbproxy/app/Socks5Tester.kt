package com.nexafbproxy.app

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

object Socks5Tester {

    data class TestResult(
        val exitIp: String,
        val latencyMs: Long
    )

    @Throws(IOException::class)
    fun test(config: ProxyConfig, timeoutMs: Int = 12_000): TestResult {
        val started = System.currentTimeMillis()

        Socket().use { socket ->
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(config.host, config.port), timeoutMs)

            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            // SOCKS5 greeting. Offer username/password auth (0x02).
            output.write(byteArrayOf(0x05, 0x01, 0x02))
            output.flush()

            val ver = readByte(input)
            val method = readByte(input)
            if (ver != 0x05) throw IOException("Máy chủ không trả SOCKS5.")
            if (method == 0xFF) throw IOException("SOCKS5 từ chối phương thức xác thực.")
            if (method != 0x02 && method != 0x00) {
                throw IOException("SOCKS5 yêu cầu kiểu xác thực chưa hỗ trợ: $method")
            }

            if (method == 0x02) {
                val user = config.username.toByteArray(StandardCharsets.UTF_8)
                val pass = config.password.toByteArray(StandardCharsets.UTF_8)
                require(user.size in 1..255) { "Username SOCKS5 không hợp lệ." }
                require(pass.size in 1..255) { "Password SOCKS5 không hợp lệ." }

                output.write(0x01)
                output.write(user.size)
                output.write(user)
                output.write(pass.size)
                output.write(pass)
                output.flush()

                val authVer = readByte(input)
                val authStatus = readByte(input)
                if (authVer != 0x01 || authStatus != 0x00) {
                    throw IOException("Sai username/password hoặc proxy từ chối đăng nhập.")
                }
            }

            // CONNECT api.ipify.org:80 with domain resolved by the SOCKS5 server.
            val domain = "api.ipify.org".toByteArray(StandardCharsets.US_ASCII)
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x03))
            output.write(domain.size)
            output.write(domain)
            output.write(byteArrayOf(0x00, 0x50)) // port 80
            output.flush()

            val responseVer = readByte(input)
            val reply = readByte(input)
            readByte(input) // RSV
            val atyp = readByte(input)

            if (responseVer != 0x05) throw IOException("Phản hồi SOCKS5 không hợp lệ.")
            if (reply != 0x00) throw IOException("SOCKS5 CONNECT thất bại, mã: $reply")

            skipBoundAddress(input, atyp)
            readByte(input) // BND.PORT high
            readByte(input) // BND.PORT low

            val request = buildString {
                append("GET / HTTP/1.1\r\n")
                append("Host: api.ipify.org\r\n")
                append("User-Agent: NexaFbProxy/1.0\r\n")
                append("Accept: text/plain\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.US_ASCII)

            output.write(request)
            output.flush()

            val raw = ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                raw.write(buffer, 0, read)
            }

            val http = raw.toString(StandardCharsets.UTF_8.name())
            val split = http.indexOf("\r\n\r\n")
            if (split < 0) throw IOException("Không đọc được phản hồi kiểm tra IP.")

            val statusLine = http.substringBefore("\r\n")
            if (!statusLine.contains(" 200 ")) {
                throw IOException("Dịch vụ kiểm tra IP trả: $statusLine")
            }

            val body = http.substring(split + 4).trim()
            if (!looksLikeIp(body)) {
                throw IOException("Exit IP trả về không hợp lệ: $body")
            }

            return TestResult(
                exitIp = body,
                latencyMs = System.currentTimeMillis() - started
            )
        }
    }

    private fun skipBoundAddress(input: BufferedInputStream, atyp: Int) {
        when (atyp) {
            0x01 -> repeat(4) { readByte(input) } // IPv4
            0x03 -> {
                val length = readByte(input)
                repeat(length) { readByte(input) }
            }
            0x04 -> repeat(16) { readByte(input) } // IPv6
            else -> throw IOException("SOCKS5 ATYP không hỗ trợ: $atyp")
        }
    }

    private fun readByte(input: BufferedInputStream): Int {
        val value = input.read()
        if (value < 0) throw IOException("Proxy đóng kết nối.")
        return value
    }

    private fun looksLikeIp(value: String): Boolean {
        val ipv4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")
        val ipv6 = value.contains(":")
        if (ipv6) return true
        if (!ipv4.matches(value)) return false
        return value.split(".").all { it.toIntOrNull() in 0..255 }
    }
}
