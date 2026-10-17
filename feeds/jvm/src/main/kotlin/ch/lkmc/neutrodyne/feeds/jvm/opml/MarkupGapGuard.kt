// SPDX-License-Identifier: Unlicense
package ch.lkmc.neutrodyne.feeds.jvm.opml

import ch.lkmc.neutrodyne.feeds.jvm.parse.HostileEntityDeclaration
import java.io.InputStream

/**
 * The streaming bounds of the OPML read path (05 Sniff and parse): a pass-through [InputStream]
 * filter that observes every byte the pull parser consumes and throws before any parser buffer can
 * grow past the caps. The document is never materialised — the guard state is a handful of counters
 * and a lookahead deque bounded at a few units.
 *
 * Two checks run over the byte stream:
 *
 * - **Markup gap.** More than [maxGapBytes] bytes without a `<` trips `TAG_TOO_LONG`: XML forbids
 *   a literal `<` in attribute values and OPML has no long text nodes, so no legitimate file comes
 *   near the bound, and kxml2 — which builds each attribute value in memory — can never grow a
 *   value past about 1 MiB of characters. `<` is detected as byte `3C` in ASCII-compatible
 *   encodings (it never occurs inside a multi-byte sequence) and as the code unit `003C` at an
 *   aligned position in UTF-16, detected by BOM or by the first bytes `3C 00`/`00 3C`.
 * - **Entity declaration.** Until the root start-tag opens, the prolog is walked through the same
 *   constructs `PrologLexer` models for relaxed kxml2 — comments, CDATA sections, processing
 *   instructions, the `<?xml` declaration, markup declarations and `&` references — and any
 *   `<!ENTITY` inside a markup declaration throws [HostileEntityDeclaration] (`ENTITY_DECLARED`).
 *   The walk is a push-model transliteration of `PrologGuard.isHostileText`: byte-for-byte the
 *   same construct boundaries, so a `<!ENTITY` hidden inside a comment or swallowed by a `&x<`
 *   reference is never flagged — the parser could not attribute it to a declaration either. Once
 *   the root tag opens the walk ends; markup declarations in the body are covered by the reader's
 *   `DOCDECL` token check.
 *
 * The `<?xml` declaration's attribute walk also bounds attribute count at [maxTagAttributes]:
 * kxml2 grows its attribute array a few slots at a time per attribute, so a flooded declaration is
 * a quadratic copy storm even when the gap bound keeps the tag under 1 MiB.
 */
internal class MarkupGapGuard(
    private val input: InputStream,
    private val maxGapBytes: Long,
    private val maxTagAttributes: Int,
) : InputStream() {
    private enum class Mode {
        /** First two bytes not yet seen; the BOM/`3C 00`/`00 3C` signature is undecided. */
        PENDING,

        /** ASCII-compatible encodings: every byte is one unit. */
        BYTE,

        /** UTF-16LE (and UTF-32LE, whose `<`/`?` units land on the same aligned pairs). */
        LE,

        /** UTF-16BE. */
        BE,
    }

    private enum class St {
        /** Prolog dispatch: text, whitespace, `&` references and `<` openers. */
        TEXT,

        /** Consumed `<`; the next unit picks the construct. */
        LT,

        /** Consumed `<!`; the next unit picks comment, CDATA or declaration. */
        BANG,

        /** A markup declaration: the blind `DOCTYPE` region, then the depth scan. */
        DECL,

        COMMENT,
        CDATA,

        /** Consumed `<?`; up to four lookahead units decide `<?xml` vs PI. */
        PI_TARGET,

        PI,

        /** Inside `<?xml`: the name (one unconditional char, then name chars), then XLOOP. */
        XNAME,

        /** The `<?xml` attribute loop: whitespace, `?` ends (plus one char), else an attribute. */
        XLOOP,

        /** An attribute name (one unconditional char, then name chars). */
        XATTR,

        /** After an attribute name: whitespace, then an optional `=`. */
        XEQ,

        /** After `=`: whitespace, then the value opener. */
        XVWS,

        XQVAL,
        XUVAL,

        /** The char kxml2 blindly consumes expecting `>` after an xml declaration's `?`. */
        XTAIL,

        /** `&…` reference: ref chars, then one terminator consumed whatever it is. */
        REF,

        /** `</`: a name (one unconditional char, then name chars), whitespace, one more char. */
        ENDTAG,

        /** The root tag opened; the walk is off, only the gap counter still runs. */
        DONE,
    }

    private var gapBytes = 0L

    private var mode = Mode.PENDING
    private val head = IntArray(2)
    private var headLen = 0
    private var holdByte = -1 // first byte of an unpaired UTF-16 unit
    private val pending = ArrayDeque<Int>()

    private var st = St.TEXT
    private var blind = 0 // required-region / unconditional-name units still swallowed blindly
    private var tail = 0 // consecutive '-' or ']' toward a close marker
    private var prevQ = false // last unit was '?' inside a PI
    private var declDepth = 0
    private var quoted = false
    private var matched = 0 // matched prefix length toward "<!entity"
    private var quote = 0
    private var attrCount = 0
    private var etWs = false // an ENDTAG name is done and whitespace is being consumed
    private var refResume = St.TEXT
    private var atEof = false

    override fun read(): Int {
        val b = input.read()
        if (b < 0) {
            flush()
        } else {
            push(b)
        }
        return b
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        val n = input.read(b, off, len)
        if (n < 0) {
            flush()
        } else {
            for (i in off until off + n) push(b[i].toInt() and 0xFF)
        }
        return n
    }

    override fun close() = input.close()

    /** One delivered byte: gap accounting, then unit assembly per the detected mode. */
    private fun push(b: Int) {
        gapBytes++
        if (gapBytes > maxGapBytes) throw MarkupBoundExceeded("over $maxGapBytes bytes without a '<'")
        when (mode) {
            Mode.PENDING -> {
                head[headLen++] = b
                if (headLen == head.size) {
                    mode = detectMode(head[0], head[1])
                    pushHead()
                }
            }

            Mode.BYTE -> {
                emitUnit(b)
            }

            Mode.LE -> {
                if (holdByte < 0) {
                    holdByte = b
                } else {
                    emitUnit(holdByte or (b shl 8))
                    holdByte = -1
                }
            }

            Mode.BE -> {
                if (holdByte < 0) {
                    holdByte = b
                } else {
                    emitUnit((holdByte shl 8) or b)
                    holdByte = -1
                }
            }
        }
    }

    private fun pushHead() {
        when (mode) {
            Mode.LE -> {
                emitUnit(head[0] or (head[1] shl 8))
            }

            Mode.BE -> {
                emitUnit((head[0] shl 8) or head[1])
            }

            else -> {
                emitUnit(head[0])
                emitUnit(head[1])
            }
        }
    }

    /** One decoded unit: a `<` resets the gap; until DONE every unit feeds the prolog walk. */
    private fun emitUnit(u: Int) {
        if (u == LT) gapBytes = 0
        if (st == St.DONE) return
        pending.addLast(u)
        while (st != St.DONE && step()) {
            // one automaton step per iteration; false means "needs a unit that has not arrived"
        }
    }

    /**
     * One automaton step over [pending]. Every construct arm is re-entrant: it consumes only after
     * peeking, so a stalled arm resumes where it left off when more units arrive. Returns false
     * when the next decision needs a unit that has not arrived; at EOF such constructs simply end —
     * an unterminated prolog construct is the parser's error, not the guard's.
     */
    private fun step(): Boolean {
        when (st) {
            St.TEXT -> {
                when (takeOrWait()) {
                    WAIT -> {
                        return false
                    }

                    EOF -> {
                        return false
                    }

                    AMP -> {
                        st = ref(St.TEXT)
                    }

                    LT -> {
                        st = St.LT
                    }

                    else -> {}
                }
            }

            St.LT -> {
                when (peekOrWait()) {
                    WAIT -> {
                        return false
                    }

                    EOF -> {
                        // A lone '<' at document end is a text char, never a tag open.
                        st = St.TEXT
                    }

                    BANG -> {
                        take()
                        st = St.BANG
                    }

                    QM -> {
                        take()
                        st = St.PI_TARGET
                    }

                    SLASH -> {
                        take()
                        st = St.ENDTAG
                        blind = 1
                        etWs = false
                    }

                    else -> {
                        // Any other char opens the root start tag: the prolog — and with it this
                        // walk — ends here; the parser owns tag lexing from now on.
                        st = St.DONE
                    }
                }
            }

            St.BANG -> {
                when (peekOrWait()) {
                    WAIT -> {
                        return false
                    }

                    DASH -> {
                        take()
                        st = blindThen(St.COMMENT, 1)
                    }

                    LBRACKET -> {
                        take()
                        st = blindThen(St.CDATA, CDATA_BLIND)
                    }

                    else -> {
                        // The dispatch char stays pending: it is the first of the seven required
                        // `DOCTYPE` region chars kxml2 consumes blindly.
                        st = blindThen(St.DECL, DOCTYPE_BLIND)
                        enterDecl()
                    }
                }
            }

            St.DECL -> {
                while (blind > 0) {
                    when (val u = takeOrWait()) {
                        WAIT -> {
                            return false
                        }

                        EOF -> {
                            st = St.TEXT
                            return true
                        }

                        else -> {
                            blind--
                            feedMatcher(u)
                        }
                    }
                }
                when (val u = takeOrWait()) {
                    WAIT -> {
                        return false
                    }

                    EOF -> {
                        st = St.TEXT
                    }

                    else -> {
                        feedMatcher(u)
                        if (quoted) {
                            if (u == SQUOTE) quoted = false
                        } else {
                            when (u) {
                                SQUOTE -> {
                                    quoted = true
                                }

                                LT -> {
                                    declDepth++
                                }

                                GT -> {
                                    declDepth--
                                    if (declDepth == 0) st = St.TEXT
                                }
                            }
                        }
                    }
                }
            }

            St.COMMENT, St.CDATA -> {
                while (blind > 0) {
                    when (takeOrWait()) {
                        WAIT -> {
                            return false
                        }

                        EOF -> {
                            st = St.TEXT
                            return true
                        }

                        else -> {
                            blind--
                        }
                    }
                }
                val close = if (st == St.COMMENT) DASH else RBRACKET
                when (val u = takeOrWait()) {
                    WAIT -> {
                        return false
                    }

                    EOF -> {
                        st = St.TEXT
                    }

                    GT -> {
                        if (tail >= 2) st = St.TEXT else tail = 0
                    }

                    else -> {
                        tail = if (u == close) tail + 1 else 0
                    }
                }
            }

            St.PI_TARGET -> {
                // `<?xml` followed by whitespace or EOF takes kxml2's attribute loop; any other
                // target is a plain PI whose speculative `xm` probe is still consumed.
                val have = pending.size
                val isXml =
                    have >= XML_NAME_UNITS &&
                        pending[0].ciEq('x') && pending[1].ciEq('m') && pending[2].ciEq('l')
                when {
                    isXml && have > XML_NAME_UNITS -> {
                        st =
                            if (pending[XML_NAME_UNITS] <= SPACE) {
                                enterXmlDecl()
                            } else {
                                piSkip(true)
                            }
                    }

                    isXml && !atEof -> {
                        return false // a fourth unit may still prove a PI
                    }

                    isXml -> {
                        st = enterXmlDecl()
                    }

                    have >= XML_NAME_UNITS || atEof -> {
                        val probe = have >= 2 && pending[0].ciEq('x') && pending[1].ciEq('m')
                        st = piSkip(probe)
                    }

                    else -> {
                        return false
                    }
                }
            }

            St.PI -> {
                while (blind > 0) {
                    when (takeOrWait()) {
                        WAIT -> {
                            return false
                        }

                        EOF -> {
                            st = St.TEXT
                            return true
                        }

                        else -> {
                            blind--
                        }
                    }
                }
                when (val u = takeOrWait()) {
                    WAIT -> {
                        return false
                    }

                    EOF -> {
                        st = St.TEXT
                    }

                    QM -> {
                        prevQ = true
                    }

                    GT -> {
                        if (prevQ) st = St.TEXT else prevQ = false
                    }

                    else -> {
                        prevQ = false
                    }
                }
            }

            St.XNAME -> {
                if (!nameLoop()) return false
                st = St.XLOOP
            }

            St.XLOOP -> {
                while (pending.isNotEmpty() && pending.first() <= SPACE) take()
                when (peekOrWait()) {
                    WAIT -> {
                        return false
                    }

                    EOF -> {
                        return false
                    }

                    QM -> {
                        take()
                        // kxml2 reads one more char expecting '>'; swallowing it in TEXT would
                        // let a '<' there open a phantom construct, so it waits for its own take.
                        st = St.XTAIL
                    }

                    else -> {
                        st = St.XATTR
                        blind = 1
                        attrCount++
                        if (attrCount > maxTagAttributes) {
                            throw MarkupBoundExceeded("xml declaration over $maxTagAttributes attributes")
                        }
                    }
                }
            }

            St.XATTR -> {
                if (!nameLoop()) return false
                st = St.XEQ
            }

            St.XEQ -> {
                while (pending.isNotEmpty() && pending.first() <= SPACE) take()
                when (peekOrWait()) {
                    WAIT -> {
                        return false
                    }

                    EOF -> {
                        return false
                    }

                    EQ -> {
                        take()
                        st = St.XVWS
                    }

                    else -> {
                        st = St.XLOOP
                    }
                }
            }

            St.XVWS -> {
                while (pending.isNotEmpty() && pending.first() <= SPACE) take()
                when (val u = peekOrWait()) {
                    WAIT -> {
                        return false
                    }

                    EOF -> {
                        return false
                    }

                    DQUOTE, SQUOTE -> {
                        take()
                        quote = u
                        st = St.XQVAL
                    }

                    else -> {
                        st = St.XUVAL
                    }
                }
            }

            St.XQVAL -> {
                when (val u = peekOrWait()) {
                    WAIT -> {
                        return false
                    }

                    EOF -> {
                        return false
                    }

                    AMP -> {
                        take()
                        st = ref(St.XQVAL)
                    }

                    else -> {
                        take()
                        if (u == quote) st = St.XLOOP
                    }
                }
            }

            St.XUVAL -> {
                when (val u = peekOrWait()) {
                    WAIT -> {
                        return false
                    }

                    EOF -> {
                        return false
                    }

                    AMP -> {
                        take()
                        st = ref(St.XUVAL)
                    }

                    else -> {
                        // kxml2's unquoted delimiter is ' ': whitespace or '>' ends the value and
                        // the delimiter itself is left for the attribute loop.
                        if (u <= SPACE || u == GT) {
                            st = St.XLOOP
                        } else {
                            take()
                        }
                    }
                }
            }

            St.REF -> {
                while (pending.isNotEmpty() && isRefChar(pending.first())) take()
                when (takeOrWait()) {
                    WAIT -> return false
                    else -> st = refResume
                }
            }

            St.XTAIL -> {
                when (takeOrWait()) {
                    WAIT -> return false
                    else -> st = St.TEXT
                }
            }

            St.ENDTAG -> {
                while (true) {
                    val u = peekOrWait()
                    if (u == WAIT) return false
                    if (u == EOF) {
                        st = St.TEXT
                        return true
                    }
                    when {
                        blind > 0 -> {
                            take()
                            blind = 0
                        }

                        !etWs && isNameChar(u) -> {
                            take()
                        }

                        u <= SPACE -> {
                            take()
                            etWs = true
                        }

                        else -> {
                            take()
                            st = St.TEXT
                            return true
                        }
                    }
                }
            }

            St.DONE -> {
                return false
            }
        }
        return true
    }

    /**
     * kxml2's nameEnd: [blind] = 1 covers the unconditional first char, then name chars. Only
     * peeks before consuming, so a pending-starved loop resumes without miscounting.
     */
    private fun nameLoop(): Boolean {
        while (true) {
            val u = peekOrWait()
            if (u == WAIT) return false
            if (u == EOF) return true
            if (blind > 0) {
                take()
                blind = 0
                continue
            }
            if (!isNameChar(u)) return true
            take()
        }
    }

    /** Enters a markup declaration: its `<` counts toward the depth; the matcher sees `<!`. */
    private fun enterDecl() {
        declDepth = 1
        quoted = false
        matched = 0
        feedMatcher(LT)
        feedMatcher(BANG)
    }

    private fun enterXmlDecl(): St {
        blind = 1
        attrCount = 0
        return St.XNAME
    }

    private fun blindThen(
        next: St,
        count: Int,
    ): St {
        blind = count
        return next
    }

    /**
     * A PI's payload scan begins past the consumed `xm` probe and one more char: the terminator
     * `?>` can only match strictly after the first payload char.
     */
    private fun piSkip(probeConsumed: Boolean): St {
        blind = (if (probeConsumed) XML_PROBE_CHARS else 0) + 1
        prevQ = false
        return St.PI
    }

    /** `&` in text or a value: ref chars, then one terminator consumed whatever it is. */
    private fun ref(resume: St): St {
        refResume = resume
        return St.REF
    }

    /** The `<!entity` marker, case-insensitive, inside a declaration span (KMP restart on `<`). */
    private fun feedMatcher(u: Int) {
        val lc = if (u in 'A'.code..'Z'.code) u + CASE_GAP else u
        if (lc == ENTITY_MARKER[matched]) {
            matched++
            if (matched == ENTITY_MARKER.size) throw HostileEntityDeclaration()
        } else {
            matched = if (lc == LT) 1 else 0
        }
    }

    private fun peekOrWait(): Int {
        if (pending.isNotEmpty()) return pending.first()
        return if (atEof) EOF else WAIT
    }

    private fun takeOrWait(): Int = peekOrWait().also { if (it >= 0) take() }

    private fun take() {
        pending.removeFirst()
    }

    /** Source is exhausted: promote the undecided head and lone pair bytes, then drain. */
    private fun flush() {
        if (atEof) return
        if (mode == Mode.PENDING) {
            mode = Mode.BYTE
            for (i in 0 until headLen) emitUnit(head[i])
        } else if (holdByte >= 0) {
            emitUnit(holdByte)
            holdByte = -1
        }
        atEof = true
        while (st != St.DONE && step()) {
            // drain pending with EOF semantics
        }
    }

    private fun Int.ciEq(c: Char): Boolean = this == c.code || (this in 'A'.code..'Z'.code && this + CASE_GAP == c.code)

    private fun isNameChar(u: Int): Boolean =
        u in 'a'.code..'z'.code || u in 'A'.code..'Z'.code || u in '0'.code..'9'.code ||
            u == '_'.code || u == '-'.code || u == ':'.code || u == '.'.code || u >= 0xB7

    private fun isRefChar(u: Int): Boolean =
        u in 'a'.code..'z'.code || u in 'A'.code..'Z'.code || u in '0'.code..'9'.code ||
            u == '_'.code || u == '#'.code || u == '-'.code || u >= 0x80

    private fun detectMode(
        b0: Int,
        b1: Int,
    ): Mode =
        when {
            b0 == 0xFF && b1 == 0xFE -> Mode.LE
            b0 == 0xFE && b1 == 0xFF -> Mode.BE
            b0 == LT && b1 == 0x00 -> Mode.LE
            b0 == 0x00 && b1 == LT -> Mode.BE
            else -> Mode.BYTE
        }

    private companion object {
        const val LT = 0x3C
        const val GT = 0x3E
        const val QM = 0x3F
        const val BANG = 0x21
        const val SLASH = 0x2F
        const val DASH = 0x2D
        const val LBRACKET = 0x5B
        const val RBRACKET = 0x5D
        const val AMP = 0x26
        const val EQ = 0x3D
        const val SQUOTE = 0x27
        const val DQUOTE = 0x22
        const val SPACE = 0x20

        /** No unit buffered and the stream is still open — the automaton waits for more input. */
        const val WAIT = -1

        /** No unit buffered at end of input — the construct ends where the document does. */
        const val EOF = -2

        const val CASE_GAP = 'a' - 'A'

        /**
         * The seven required `DOCTYPE` region chars a markup declaration consumes blindly after
         * `<!` — the dispatch char itself stays pending and counts as the first of them.
         */
        const val DOCTYPE_BLIND = 7

        /** The rest of the required `[CDATA[` region after the `<![` dispatch char. */
        const val CDATA_BLIND = 6

        const val XML_NAME_UNITS = 3
        const val XML_PROBE_CHARS = 2

        val ENTITY_MARKER = "<!entity".map { it.code }.toIntArray()
    }
}

/** Thrown by [MarkupGapGuard] when a streaming bound trips; maps to `TAG_TOO_LONG`. */
internal class MarkupBoundExceeded(
    message: String,
) : RuntimeException(message)

/**
 * Drops ASCII whitespace bytes before the first byte of markup, preserving a BOM — the streaming
 * equivalent of `XmlPullFeedParser`'s `withoutLeadingWhitespace` (05 Sniff and parse: tolerated by
 * relaxed kxml2 but refused by the AOSP parser, so the strict pass skips it too).
 */
internal class LeadingWhitespaceSkipper(
    private val input: InputStream,
) : InputStream() {
    private var skipping = true

    override fun read(): Int {
        if (!skipping) return input.read()
        var b = input.read()
        while (b >= 0 && isWs(b)) b = input.read()
        skipping = false
        return b
    }

    override fun read(
        b: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        if (len == 0) return 0
        if (!skipping) return input.read(b, off, len)
        var first = input.read()
        while (first >= 0 && isWs(first)) first = input.read()
        skipping = false
        if (first < 0) return -1
        b[off] = first.toByte()
        val rest = input.read(b, off + 1, len - 1)
        return if (rest < 0) 1 else rest + 1
    }

    override fun close() = input.close()

    private fun isWs(b: Int): Boolean = b == 0x20 || b == 0x09 || b == 0x0D || b == 0x0A
}
