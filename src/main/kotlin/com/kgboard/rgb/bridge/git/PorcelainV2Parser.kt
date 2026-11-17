package com.kgboard.rgb.bridge.git

import com.kgboard.rgb.runtime.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Parse NUL records with bounded prefix splits. Paths never enter the domain model. */
object PorcelainV2Parser {
    private val oidPattern = Regex("(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})")
    private val modePattern = Regex("[0-7]{6}")
    private val xyPattern = Regex("[.MADRCUT?!]{2}")
    private val subPattern = Regex("(?:N\\.\\.\\.|S[.C][.M][.U])")
    private val abPattern = Regex("\\+(0|[1-9][0-9]*) -(0|[1-9][0-9]*)")
    fun parse(bytes: ByteArray, repositoryId: String, nowMs: Long): RepositoryFacts {
        val unknown = RepositoryFacts.unknown(repositoryId, nowMs)
        if (bytes.isEmpty() || bytes.size > 16 * 1024 * 1024 || bytes.last() != 0.toByte()) return unknown
        // Latin-1 retains arbitrary path bytes; only published branch headers are UTF-8 decoded.
        val tokens = bytes.toString(Charsets.ISO_8859_1).split('\u0000').dropLast(1)
        var oid: String? = null;
        var head: String? = null;
        var upstream: String? = null;
        var counts: Pair<Int, Int>? = null
        var dirty = false;
        var conflicts = 0;
        var i = 0
        val seen = mutableSetOf<String>()
        fun utf8(text: String): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(text.toByteArray(Charsets.ISO_8859_1))).toString()
        try {
            while (i < tokens.size) {
                val line = tokens[i++]
                if (line.startsWith("# ")) {
                    val fields = line.substring(2).split(' ', limit = 2)
                    if (fields[0] in setOf("branch.oid", "branch.head", "branch.upstream", "branch.ab")) {
                        if (fields.size != 2 || fields[1].isEmpty() || !seen.add(fields[0])) return unknown
                        when (fields[0]) {
                            "branch.oid" -> {
                                if (fields[1] != "(initial)" && !oidPattern.matches(fields[1])) return unknown; oid =
                                    fields[1]
                            }

                            "branch.head" -> {
                                head = utf8(fields[1]); if (head!!.any { it < ' ' || it == '\u007f' }) return unknown
                            }

                            "branch.upstream" -> upstream = utf8(fields[1])
                            "branch.ab" -> {
                                val m = abPattern.matchEntire(fields[1]) ?: return unknown
                                counts = (m.groupValues[1].toIntOrNull()
                                    ?: return unknown) to (m.groupValues[2].toIntOrNull() ?: return unknown)
                            }
                        }
                    }
                    continue
                }
                if (line.startsWith("? ") || line.startsWith("! ")) {
                    if (line.length <= 2) return unknown
                    if (line[0] == '?') dirty = true
                    continue
                }
                val kind = line.firstOrNull() ?: return unknown
                val size = when (kind) {
                    '1' -> 9; '2' -> 10; 'u' -> 11; else -> return unknown
                }
                val f = line.split(' ', limit = size)
                if (f.size != size || f[0] != kind.toString() || !xyPattern.matches(f[1]) || !subPattern.matches(f[2]) || f.last()
                        .isEmpty()
                ) return unknown
                val modes = if (kind == 'u') 3..6 else 3..5
                val hashes = if (kind == 'u') 7..9 else 6..7
                if (modes.any { !modePattern.matches(f[it]) } || hashes.any { !oidPattern.matches(f[it]) }) return unknown
                if (kind == '2') {
                    if (!Regex("[RC](?:100|[1-9]?[0-9])").matches(f[8]) || i >= tokens.size || tokens[i++].isEmpty()) return unknown
                }
                dirty = true; if (kind == 'u') conflicts++
            }
        } catch (_: java.nio.charset.CharacterCodingException) {
            return unknown
        }
        if (oid == null || head == null || counts != null && upstream == null) return unknown
        val state = when {
            oid == "(initial)" -> HeadState.UNBORN; head == "(detached)" -> HeadState.DETACHED; else -> HeadState.BRANCH
        }
        return RepositoryFacts(
            repositoryId,
            oid.takeUnless { it == "(initial)" },
            head.takeIf { state == HeadState.BRANCH || state == HeadState.UNBORN },
            state,
            if (upstream == null) UpstreamState.NONE else UpstreamState.PRESENT,
            dirty,
            conflicts,
            counts?.first,
            counts?.second,
            RepositoryOperation.NONE,
            Freshness.FRESH,
            nowMs
        )
    }
}
