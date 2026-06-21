package com.adshield.vpn

/**
 * Minimal hand-rolled IPv4 / UDP / DNS helpers.
 *
 * We only ever deal with IPv4 UDP packets destined for our virtual DNS server,
 * so a full packet library would be overkill. UDP checksums are written as 0,
 * which IPv4 explicitly permits, so we only have to compute the IPv4 header
 * checksum.
 */
object PacketUtil {

    const val PROTO_UDP = 17

    /** Result of inspecting an inbound IPv4/UDP/DNS packet read from the TUN. */
    class DnsRequest(
        val srcIp: ByteArray,
        val dstIp: ByteArray,
        val srcPort: Int,
        val dstPort: Int,
        val dnsPayload: ByteArray,
    )

    /**
     * Returns the DNS request carried by [packet] (length [len]), or null if the
     * packet is not an IPv4 UDP datagram aimed at port 53.
     */
    fun parseDnsRequest(packet: ByteArray, len: Int): DnsRequest? {
        if (len < 28) return null
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (ihl < 20 || len < ihl + 8) return null
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != PROTO_UDP) return null

        val srcIp = packet.copyOfRange(12, 16)
        val dstIp = packet.copyOfRange(16, 20)

        val udp = ihl
        val srcPort = readU16(packet, udp)
        val dstPort = readU16(packet, udp + 2)
        if (dstPort != 53) return null

        val udpLen = readU16(packet, udp + 4)
        val payloadLen = udpLen - 8
        val payloadStart = udp + 8
        if (payloadLen <= 0 || payloadStart + payloadLen > len) return null

        val payload = packet.copyOfRange(payloadStart, payloadStart + payloadLen)
        return DnsRequest(srcIp, dstIp, srcPort, dstPort, payload)
    }

    /**
     * Wrap a DNS [responsePayload] into an IPv4/UDP packet that is the reply to
     * [req] (source/destination swapped).
     */
    fun buildResponsePacket(req: DnsRequest, responsePayload: ByteArray): ByteArray {
        val ihl = 20
        val udpLen = 8 + responsePayload.size
        val totalLen = ihl + udpLen
        val out = ByteArray(totalLen)

        // ----- IPv4 header -----
        out[0] = 0x45                       // version 4, IHL 5
        out[1] = 0                          // DSCP / ECN
        writeU16(out, 2, totalLen)          // total length
        writeU16(out, 4, 0)                 // identification
        writeU16(out, 6, 0x4000)            // flags: don't fragment
        out[8] = 64                         // TTL
        out[9] = PROTO_UDP.toByte()         // protocol
        writeU16(out, 10, 0)                // checksum (filled below)
        // swap addresses: response goes from the original destination back to src
        System.arraycopy(req.dstIp, 0, out, 12, 4)
        System.arraycopy(req.srcIp, 0, out, 16, 4)
        writeU16(out, 10, checksum(out, 0, ihl))

        // ----- UDP header -----
        writeU16(out, ihl, req.dstPort)     // src port = original dst (53)
        writeU16(out, ihl + 2, req.srcPort) // dst port = original src
        writeU16(out, ihl + 4, udpLen)      // length
        writeU16(out, ihl + 6, 0)           // checksum 0 = not computed (legal in IPv4)

        System.arraycopy(responsePayload, 0, out, ihl + 8, responsePayload.size)
        return out
    }

    // ---------------------------------------------------------------------
    // DNS message helpers
    // ---------------------------------------------------------------------

    class DnsQuestion(val name: String, val qtype: Int, val questionEnd: Int)

    /** Parse the first question of a DNS query payload. Null if malformed. */
    fun parseQuestion(dns: ByteArray): DnsQuestion? {
        if (dns.size < 12) return null
        val qdCount = readU16(dns, 4)
        if (qdCount < 1) return null
        val sb = StringBuilder()
        var pos = 12
        while (true) {
            if (pos >= dns.size) return null
            val labelLen = dns[pos].toInt() and 0xFF
            if (labelLen == 0) {
                pos += 1
                break
            }
            // Compression pointers never appear in a query's question section.
            if (labelLen and 0xC0 != 0) return null
            if (pos + 1 + labelLen > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            for (i in 0 until labelLen) {
                sb.append((dns[pos + 1 + i].toInt() and 0xFF).toChar())
            }
            pos += 1 + labelLen
        }
        if (pos + 4 > dns.size) return null
        val qtype = readU16(dns, pos)
        return DnsQuestion(sb.toString(), qtype, pos + 4)
    }

    const val TYPE_A = 1
    const val TYPE_AAAA = 28

    /**
     * Build a DNS response that blocks [question]. For A queries we answer with
     * 0.0.0.0; for everything else we return an empty (NODATA) answer, which
     * makes clients give up quickly.
     */
    fun buildBlockedResponse(query: ByteArray, question: DnsQuestion): ByteArray {
        val answerA = question.qtype == TYPE_A

        val answer: ByteArray = if (answerA) {
            byteArrayOf(
                0xC0.toByte(), 0x0C,            // name pointer to offset 12
                0x00, 0x01,                     // type A
                0x00, 0x01,                     // class IN
                0x00, 0x00, 0x00, 0x1E,         // TTL = 30s
                0x00, 0x04,                     // RDLENGTH = 4
                0x00, 0x00, 0x00, 0x00,         // RDATA = 0.0.0.0
            )
        } else {
            ByteArray(0)
        }

        val out = ByteArray(question.questionEnd + answer.size)
        // Copy header + original question verbatim.
        System.arraycopy(query, 0, out, 0, question.questionEnd)

        // Flags: QR=1 (response), preserve opcode + RD, clear AA/TC, set RA, RCODE=0.
        val origByte2 = query[2].toInt() and 0xFF
        out[2] = (0x80 or (origByte2 and 0x79)).toByte() // keep opcode (bits 3-6) and RD (bit0)
        out[3] = 0x80.toByte()                            // RA=1, Z=0, RCODE=0 (NOERROR)

        // ANCOUNT
        writeU16(out, 6, if (answerA) 1 else 0)
        // NSCOUNT / ARCOUNT = 0 (any EDNS OPT in the query is dropped)
        writeU16(out, 8, 0)
        writeU16(out, 10, 0)

        if (answer.isNotEmpty()) {
            System.arraycopy(answer, 0, out, question.questionEnd, answer.size)
        }
        return out
    }

    // ---------------------------------------------------------------------
    // primitives
    // ---------------------------------------------------------------------

    private fun readU16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun writeU16(b: ByteArray, off: Int, value: Int) {
        b[off] = ((value ushr 8) and 0xFF).toByte()
        b[off + 1] = (value and 0xFF).toByte()
    }

    private fun checksum(b: ByteArray, off: Int, len: Int): Int {
        var sum = 0L
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += (((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)).toLong()
            i += 2
        }
        if (i < end) sum += ((b[i].toInt() and 0xFF) shl 8).toLong()
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}
