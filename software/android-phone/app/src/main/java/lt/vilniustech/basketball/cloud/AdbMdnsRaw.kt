package lt.vilniustech.basketball.cloud

import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Direct mDNS query — more reliable than NsdManager on Samsung / Wear. */
object AdbMdnsRaw {
    private const val TAG = "AdbMdnsRaw"
    private val MDNS = InetAddress.getByName("224.0.0.251")
    private const val MDNS_PORT = 5353

    fun query(timeoutMs: Long): AdbMdnsDiscovery.Snapshot {
        val connect = linkedSetOf<AdbMdnsDiscovery.Endpoint>()
        val pairing = linkedSetOf<AdbMdnsDiscovery.Endpoint>()
        val localIps = localIpv4s()
        Log.i(TAG, "local ips=$localIps")

        val questions = listOf(
            "_adb-tls-connect._tcp.local" to connect,
            "_adb-tls-pairing._tcp.local" to pairing,
            "_adb._tcp.local" to connect,
        )

        for (local in localIps) {
            val localAddr = InetAddress.getByName(local)
            val nif = runCatching { NetworkInterface.getByInetAddress(localAddr) }.getOrNull()
            val sock = runCatching {
                MulticastSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(localAddr, 0))
                    soTimeout = 250
                    timeToLive = 255
                    if (nif != null) {
                        networkInterface = nif
                        joinGroup(InetSocketAddress(MDNS, MDNS_PORT), nif)
                    }
                }
            }.getOrNull() ?: continue

            sock.use { socket ->
                for ((qname, _) in questions) {
                    val payload = buildPtrQuery(qname)
                    runCatching {
                        socket.send(DatagramPacket(payload, payload.size, MDNS, MDNS_PORT))
                    }
                }
                val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1500L)
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buf, buf.size)
                    val got = runCatching {
                        socket.receive(packet)
                        true
                    }.getOrElse {
                        if (it is SocketTimeoutException) false else {
                            Log.w(TAG, "recv ${it.message}")
                            false
                        }
                    }
                    if (!got) continue
                    val from = packet.address?.hostAddress ?: continue
                    if (from in localIps) continue
                    parseAnswers(packet.data, packet.length, from, connect, pairing)
                }
            }
        }
        Log.i(TAG, "raw connect=$connect pairing=$pairing")
        return AdbMdnsDiscovery.Snapshot(connect.toList(), pairing.toList())
    }

    fun localIpv4s(): Set<String> {
        val out = linkedSetOf<String>()
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { nif ->
                if (!nif.isUp || nif.isLoopback) return@forEach
                nif.inetAddresses.toList().forEach { addr ->
                    val host = addr.hostAddress ?: return@forEach
                    if (host.contains(':') || host.startsWith("127.")) return@forEach
                    out += host
                }
            }
        }
        return out
    }

    private fun buildPtrQuery(qname: String): ByteArray {
        val name = encodeName(qname)
        val buf = ByteBuffer.allocate(12 + name.size + 4).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(0) // id
        buf.putShort(0) // flags
        buf.putShort(1) // questions
        buf.putShort(0)
        buf.putShort(0)
        buf.putShort(0)
        buf.put(name)
        buf.putShort(12) // PTR
        buf.putShort(1) // IN
        return buf.array()
    }

    private fun encodeName(name: String): ByteArray {
        val labels = name.trimEnd('.').split('.')
        val out = java.io.ByteArrayOutputStream()
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun parseAnswers(
        data: ByteArray,
        length: Int,
        from: String,
        connect: MutableSet<AdbMdnsDiscovery.Endpoint>,
        pairing: MutableSet<AdbMdnsDiscovery.Endpoint>,
    ) {
        if (length < 12) return
        val buf = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        buf.short // id
        buf.short // flags
        val qd = buf.short.toInt() and 0xFFFF
        val an = buf.short.toInt() and 0xFFFF
        val ns = buf.short.toInt() and 0xFFFF
        val ar = buf.short.toInt() and 0xFFFF
        repeat(qd) { skipName(buf); buf.short; buf.short }
        val srvByHost = mutableMapOf<String, Pair<Int, String>>() // target -> port, ownerName
        val aByHost = mutableMapOf<String, String>()
        val ptrNames = mutableListOf<String>()
        repeat(an + ns + ar) {
            val owner = readName(buf, data) ?: return@repeat
            if (buf.remaining() < 10) return
            val type = buf.short.toInt() and 0xFFFF
            buf.short // class
            buf.int // ttl
            val rdlen = buf.short.toInt() and 0xFFFF
            if (rdlen < 0 || buf.remaining() < rdlen) return
            val start = buf.position()
            when (type) {
                12 -> { // PTR
                    readName(buf, data)?.let { ptrNames += it.lowercase() }
                }
                33 -> { // SRV
                    if (rdlen >= 6) {
                        buf.short
                        buf.short
                        val port = buf.short.toInt() and 0xFFFF
                        val target = readName(buf, data) ?: from
                        srvByHost[target.lowercase()] = port to owner
                    }
                }
                1 -> { // A
                    if (rdlen == 4) {
                        val ip = "${buf.get().toInt() and 0xFF}.${buf.get().toInt() and 0xFF}." +
                            "${buf.get().toInt() and 0xFF}.${buf.get().toInt() and 0xFF}"
                        aByHost[owner.lowercase()] = ip
                    }
                }
            }
            buf.position(start + rdlen)
        }

        fun bucket(owner: String): MutableSet<AdbMdnsDiscovery.Endpoint>? {
            val n = owner.lowercase()
            return when {
                n.contains("adb-tls-pairing") -> pairing
                n.contains("adb-tls-connect") || n.contains("_adb._tcp") -> connect
                else -> null
            }
        }

        for ((target, portOwner) in srvByHost) {
            val (port, owner) = portOwner
            val ip = aByHost[target] ?: from
            if (ip.contains(':')) continue
            val list = bucket(owner) ?: continue
            list += AdbMdnsDiscovery.Endpoint(ip, port, owner)
        }
        // PTR-only fallback: use source IP if SRV missing (rare)
        if (srvByHost.isEmpty()) {
            for (ptr in ptrNames) {
                val list = bucket(ptr) ?: continue
                Log.i(TAG, "PTR without SRV $ptr from=$from")
            }
        }
    }

    private fun skipName(buf: ByteBuffer) {
        readName(buf, buf.array())
    }

    private fun readName(buf: ByteBuffer, data: ByteArray): String? {
        val labels = mutableListOf<String>()
        var jumped = false
        var pos = buf.position()
        var guard = 0
        while (guard++ < 32 && pos < data.size) {
            val len = data[pos].toInt() and 0xFF
            when {
                len == 0 -> {
                    pos++
                    if (!jumped) buf.position(pos)
                    break
                }
                len and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= data.size) return null
                    val pointer = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    if (!jumped) buf.position(pos + 2)
                    jumped = true
                    pos = pointer
                }
                else -> {
                    pos++
                    if (pos + len > data.size) return null
                    labels += String(data, pos, len, Charsets.UTF_8)
                    pos += len
                }
            }
        }
        return labels.joinToString(".")
    }
}
