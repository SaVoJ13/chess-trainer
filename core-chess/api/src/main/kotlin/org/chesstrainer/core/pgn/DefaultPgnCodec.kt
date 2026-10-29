// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.pgn

import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.rules.ChessRules

/**
 * Читання й запис PGN (CONTRACTS 3.3). Лише головна лінія: варіанти `( … )` пропускаються.
 *
 * Розбір:
 * - `parse` ліниво, по одній партії; помилка партії (`PgnSyntax`, `PgnIllegalMove`) не зупиняє наступні:
 *   розбір пропускає решту зіпсованої партії до її результату або до наступного блоку тегів;
 * - `gameIndex` у помилках нумерується з 0 у межах переданого тексту, `ply` з 1;
 * - заголовки: Seven Tag Roster першим (відсутні теги отримують значення за стандартом), далі решта;
 *   `Result` береться з тексту ходів (а якщо його там немає, з тегу); теги `SetUp` і `FEN` у `headers`
 *   не потрапляють, їх несе [PgnGame.startFen];
 * - `san` у [PgnMove] це канонічний SAN з [ChessRules.toSan], а не рядок із файлу;
 * - коментарі `{…}` і `;…` зберігаються без переносів (послідовності пробілів стискаються в один),
 *   суфікси `!`, `?` стають NAG 1–6, `$n` зберігаються як є. Коментар до першого ходу не зберігається.
 *
 * Запис: UTF-8 текст, рядки ходів не довші за 80 символів (окремі слова довші за 80 не розриваються,
 * рядки тегів не переносяться). `}` у коментарі замінюється на `)`.
 */
class DefaultPgnCodec(private val rules: ChessRules) : PgnCodec {

    // ------------------------------------------------------------------ parse

    override fun parse(text: String): Sequence<ChessResult<PgnGame>> = sequence {
        val scanner = Scanner(text.removePrefix("﻿"))
        var index = 0
        while (true) {
            scanner.skipWhitespace()
            if (scanner.atEnd()) break
            yield(parseGame(scanner, index))
            index++
        }
    }

    private class MoveBuilder(val san: String, val uci: org.chesstrainer.core.model.Uci) {
        var comment: String? = null
        val nags = mutableListOf<Int>()
        fun build() = PgnMove(san, uci, comment, nags.toList())
    }

    private fun parseGame(scanner: Scanner, gameIndex: Int): ChessResult<PgnGame> {
        val raw = LinkedHashMap<String, String>()

        while (true) {
            scanner.skipWhitespace()
            if (scanner.atEnd() || scanner.peek() != '[') break
            val error = scanner.readTag(raw)
            if (error != null) {
                scanner.recover(passedTags = false)
                return ChessResult.Err(ChessError.PgnSyntax(gameIndex, error.line, error.reason))
            }
        }

        fun syntax(line: Int, reason: String): ChessResult<PgnGame> {
            scanner.recover(passedTags = true)
            return ChessResult.Err(ChessError.PgnSyntax(gameIndex, line, reason))
        }

        val startFenText = raw["FEN"]
        var position: Position = if (startFenText == null) {
            rules.startPosition()
        } else {
            when (val parsed = rules.parseFen(startFenText)) {
                is ChessResult.Ok -> parsed.value
                is ChessResult.Err -> return syntax(scanner.line, "invalid FEN tag: ${startFenText}")
            }
        }

        val moves = ArrayList<MoveBuilder>()
        var terminator: PgnResult? = null

        loop@ while (true) {
            val line = scanner.line
            when (val token = scanner.nextToken()) {
                Token.Eof, Token.TagStart -> break@loop
                is Token.Comment -> if (moves.isNotEmpty() && token.text.isNotEmpty()) {
                    val last = moves.last()
                    last.comment = last.comment?.let { "$it ${token.text}" } ?: token.text
                }
                is Token.Nag -> moves.lastOrNull()?.nags?.add(token.value)
                Token.Open -> scanner.skipVariation()?.let { return syntax(line, it) }
                Token.Close -> return syntax(line, "unexpected ')'")
                is Token.Bad -> return syntax(line, token.reason)
                is Token.Symbol -> {
                    resultOf(token.text)?.let {
                        terminator = it
                        break@loop
                    }
                    val body = token.text.replace(MOVE_NUMBER, "")
                    if (body.isEmpty()) continue@loop
                    val suffix = ANNOTATION_SUFFIX.find(body)?.value.orEmpty()
                    val san = body.removeSuffix(suffix)
                    val ply = moves.size + 1
                    val move = (rules.parseSan(position, san) as? ChessResult.Ok)?.value
                    if (move == null) {
                        scanner.recover(passedTags = true)
                        return ChessResult.Err(ChessError.PgnIllegalMove(gameIndex, ply, san.ifEmpty { token.text }))
                    }
                    val builder = MoveBuilder(rules.toSan(position, move), rules.toUci(move))
                    ANNOTATION_NAGS[suffix]?.let { builder.nags.add(it) }
                    moves.add(builder)
                    position = (rules.apply(position, move) as ChessResult.Ok).value
                }
            }
        }

        val result = terminator ?: PgnResult.entries.firstOrNull { it.tag == raw["Result"] } ?: PgnResult.UNKNOWN
        val headers = LinkedHashMap<String, String>()
        for ((tag, default) in SEVEN_TAG_ROSTER) headers[tag] = raw[tag] ?: default
        headers["Result"] = result.tag
        for ((tag, value) in raw) if (tag !in headers && tag != "FEN" && tag != "SetUp") headers[tag] = value

        return ChessResult.Ok(PgnGame(headers, startFenText?.let(::Fen), moves.map { it.build() }, result))
    }

    private fun resultOf(symbol: String): PgnResult? = when (symbol) {
        "1-0" -> PgnResult.WHITE_WINS
        "0-1" -> PgnResult.BLACK_WINS
        "1/2-1/2" -> PgnResult.DRAW
        "*" -> PgnResult.UNKNOWN
        else -> null
    }

    private sealed interface Token {
        data object Eof : Token
        data object TagStart : Token
        data object Open : Token
        data object Close : Token
        data class Comment(val text: String) : Token
        data class Nag(val value: Int) : Token
        data class Symbol(val text: String) : Token
        data class Bad(val reason: String) : Token
    }

    private class TagError(val line: Int, val reason: String)

    private class Scanner(private val s: String) {
        private var pos = 0
        var line = 1
            private set

        fun atEnd() = pos >= s.length
        fun peek() = s[pos]

        private fun advance(): Char {
            val c = s[pos++]
            if (c == '\n') line++
            return c
        }

        private fun atLineStart(): Boolean {
            var i = pos - 1
            while (i >= 0 && (s[i] == ' ' || s[i] == '\t' || s[i] == '\r')) i--
            return i < 0 || s[i] == '\n'
        }

        /** Пропускає пробіли й рядки-«escape» `%` (початок рядка). */
        fun skipWhitespace() {
            while (!atEnd()) {
                val c = peek()
                if (c.isWhitespace() || c == '﻿') {
                    advance()
                } else if (c == '%' && atLineStart()) {
                    while (!atEnd() && peek() != '\n') advance()
                } else {
                    return
                }
            }
        }

        fun readTag(into: MutableMap<String, String>): TagError? {
            val startLine = line
            advance() // '['
            skipInline()
            val name = StringBuilder()
            while (!atEnd() && (peek().isLetterOrDigit() || peek() == '_')) name.append(advance())
            if (name.isEmpty()) return TagError(startLine, "tag name expected")
            skipInline()
            if (atEnd() || peek() != '"') return TagError(startLine, "tag value in quotes expected")
            advance()
            val value = StringBuilder()
            while (true) {
                if (atEnd() || peek() == '\n') return TagError(startLine, "unterminated tag value")
                val c = advance()
                if (c == '"') break
                if (c == '\\' && !atEnd() && (peek() == '"' || peek() == '\\')) value.append(advance()) else value.append(c)
            }
            skipInline()
            if (atEnd() || peek() != ']') return TagError(startLine, "']' expected")
            advance()
            into[name.toString()] = value.toString()
            return null
        }

        private fun skipInline() {
            while (!atEnd() && (peek() == ' ' || peek() == '\t' || peek() == '\r')) advance()
        }

        fun nextToken(): Token {
            skipWhitespace()
            if (atEnd()) return Token.Eof
            when (peek()) {
                '{' -> {
                    advance()
                    val sb = StringBuilder()
                    while (true) {
                        if (atEnd()) return Token.Bad("unterminated comment")
                        val c = advance()
                        if (c == '}') break
                        sb.append(c)
                    }
                    return Token.Comment(normalize(sb))
                }
                ';' -> {
                    val sb = StringBuilder()
                    advance()
                    while (!atEnd() && peek() != '\n') sb.append(advance())
                    return Token.Comment(normalize(sb))
                }
                '(' -> { advance(); return Token.Open }
                ')' -> { advance(); return Token.Close }
                '$' -> {
                    advance()
                    val digits = StringBuilder()
                    while (!atEnd() && peek().isDigit()) digits.append(advance())
                    return digits.toString().toIntOrNull()?.let { Token.Nag(it) } ?: Token.Bad("bad NAG")
                }
                '[' -> return if (atLineStart()) Token.TagStart else { advance(); Token.Bad("unexpected '['") }
            }
            val sb = StringBuilder()
            while (!atEnd() && !peek().isWhitespace() && peek() !in SYMBOL_STOP) sb.append(advance())
            if (sb.isEmpty()) return Token.Bad("unexpected '${advance()}'")
            return Token.Symbol(sb.toString())
        }

        private fun normalize(text: CharSequence): String = text.trim().replace(WHITESPACE_RUN, " ")

        /** Пропускає варіант після `(`; повертає причину помилки або `null`. */
        fun skipVariation(): String? {
            var depth = 1
            while (depth > 0) {
                when (val t = nextToken()) {
                    Token.Open -> depth++
                    Token.Close -> depth--
                    Token.Eof -> return "unterminated variation"
                    Token.TagStart -> return "unterminated variation"
                    is Token.Bad -> return t.reason
                    else -> Unit
                }
            }
            return null
        }

        /**
         * Пропускає решту зіпсованої партії: тег-рядки (якщо помилка була в них), потім усе до результату
         * (споживається) або до початку наступного блоку тегів (не споживається).
         */
        fun recover(passedTags: Boolean) {
            if (!passedTags) {
                while (true) {
                    skipWhitespace()
                    if (atEnd() || peek() != '[') break
                    while (!atEnd() && peek() != '\n') advance()
                }
            }
            while (true) {
                when (val t = nextToken()) {
                    Token.Eof, Token.TagStart -> return
                    is Token.Symbol -> if (t.text in RESULT_TOKENS) return
                    else -> Unit
                }
            }
        }
    }

    // ------------------------------------------------------------------ write

    override fun write(game: PgnGame): String {
        val out = StringBuilder()
        for ((tag, default) in SEVEN_TAG_ROSTER) {
            val value = if (tag == "Result") game.result.tag else game.headers[tag] ?: default
            out.appendTag(tag, value)
        }
        if (game.startFen != null) {
            out.appendTag("SetUp", "1")
            out.appendTag("FEN", game.startFen.value)
        }
        for ((tag, value) in game.headers) {
            if (tag in ROSTER_NAMES || tag == "FEN" || tag == "SetUp") continue
            out.appendTag(tag, value)
        }
        out.append('\n')

        val start = game.startFen?.let { (rules.parseFen(it.value) as? ChessResult.Ok)?.value }
        val startSide = start?.sideToMove ?: Color.WHITE
        val startNumber = start?.fullmoveNumber ?: 1
        val atoms = ArrayList<String>()
        var afterComment = true
        for ((i, move) in game.moves.withIndex()) {
            val side = if (i % 2 == 0) startSide else startSide.opposite
            val number = startNumber + (i + if (startSide == Color.BLACK) 1 else 0) / 2
            if (side == Color.WHITE) {
                atoms.add("$number.")
            } else if (afterComment) {
                atoms.add("$number...")
            }
            atoms.add(move.san)
            for (nag in move.nags) atoms.add("\$$nag")
            afterComment = false
            val comment = move.comment?.replace('}', ')')?.trim()?.replace(WHITESPACE_RUN, " ")
            if (!comment.isNullOrEmpty()) {
                val words = comment.split(' ')
                atoms.add("{${words.first()}" + if (words.size == 1) "}" else "")
                for ((w, word) in words.drop(1).withIndex()) atoms.add(if (w == words.size - 2) "$word}" else word)
                afterComment = true
            }
        }
        atoms.add(game.result.tag)

        var lineLength = 0
        for (atom in atoms) {
            if (lineLength == 0) {
                out.append(atom)
                lineLength = atom.length
            } else if (lineLength + 1 + atom.length <= MAX_LINE) {
                out.append(' ').append(atom)
                lineLength += 1 + atom.length
            } else {
                out.append('\n').append(atom)
                lineLength = atom.length
            }
        }
        out.append("\n\n")
        return out.toString()
    }

    private fun StringBuilder.appendTag(name: String, value: String) {
        append('[').append(name).append(" \"")
        append(value.replace("\\", "\\\\").replace("\"", "\\\""))
        append("\"]\n")
    }

    private companion object {
        const val MAX_LINE = 80
        val SEVEN_TAG_ROSTER = listOf(
            "Event" to "?",
            "Site" to "?",
            "Date" to "????.??.??",
            "Round" to "?",
            "White" to "?",
            "Black" to "?",
            "Result" to "*",
        )
        val ROSTER_NAMES = SEVEN_TAG_ROSTER.map { it.first }.toSet()
        val RESULT_TOKENS = setOf("1-0", "0-1", "1/2-1/2", "*")
        const val SYMBOL_STOP = "{}();$[]\""
        val WHITESPACE_RUN = Regex("\\s+")
        val MOVE_NUMBER = Regex("^\\d+\\.+|^\\.+|^\\d+$")
        val ANNOTATION_SUFFIX = Regex("[!?]{1,2}$")
        val ANNOTATION_NAGS = mapOf("!" to 1, "?" to 2, "!!" to 3, "??" to 4, "!?" to 5, "?!" to 6)
    }
}
