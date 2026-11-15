package com.askew137.gcwatch.core.communication

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * How a `.map` file travels phone → watch, so that a long transfer can survive an interruption
 * and a truncated file can never end up as a "finished" map:
 *
 *  1. phone → watch message [RESUME_QUERY] `"<name>|<size>"`,
 *  2. watch → phone message [RESUME_ANSWER] `"<name>|<offset>"`: how many bytes of this file the watch
 *     already has (0 = start over) or [NO_SPACE],
 *  3. phone opens the channel [channelPath] and streams the bytes from `offset`,
 *  4. watch → phone message [STORED] `"<name>|<size>|ok"` once the complete file is in place
 *     (`|incomplete` otherwise; the partial file is kept for the next try).
 */
object MapTransferProtocol {
    const val CHANNEL_PREFIX = "/map_transfer2/"
    const val RESUME_QUERY = "/map_resume_query"
    const val RESUME_ANSWER = "/map_resume_answer"
    const val STORED = "/map_stored"

    /** [RESUME_ANSWER] offset meaning "not enough free space on the watch". */
    const val NO_SPACE = -2L

    data class Transfer(val name: String, val offset: Long, val size: Long)

    fun channelPath(name: String, offset: Long, size: Long): String =
        "$CHANNEL_PREFIX$offset/$size/${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}"

    fun parseChannelPath(path: String): Transfer? {
        if (!path.startsWith(CHANNEL_PREFIX)) return null
        val parts = path.removePrefix(CHANNEL_PREFIX).split('/', limit = 3)
        if (parts.size != 3) return null
        val offset = parts[0].toLongOrNull() ?: return null
        val size = parts[1].toLongOrNull() ?: return null
        val name = try { URLDecoder.decode(parts[2], "UTF-8") } catch (e: Exception) { return null }
        if (!isSafeMapName(name) || offset < 0 || size <= 0 || offset >= size) return null
        return Transfer(name, offset, size)
    }

    /** Only a plain `*.map` file name: never a path, so a remote peer cannot write elsewhere. */
    fun isSafeMapName(name: String): Boolean =
        name.endsWith(".map") && name.length > 4 &&
            '/' !in name && '\\' !in name && '|' !in name && ".." !in name

    fun encode(vararg parts: Any): ByteArray = parts.joinToString("|").toByteArray(Charsets.UTF_8)

    /** `"<name>|<number>[|<text>]"` as used by all three messages; null when malformed. */
    fun decode(payload: ByteArray): Triple<String, Long, String>? {
        val p = String(payload, Charsets.UTF_8).split('|', limit = 3)
        if (p.size < 2) return null
        val n = p[1].toLongOrNull() ?: return null
        return Triple(p[0], n, p.getOrElse(2) { "" })
    }
}
