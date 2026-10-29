// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.core.rules

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.move.Move as LibMove
import org.chesstrainer.core.model.CastlingRights
import org.chesstrainer.core.model.ChessError
import org.chesstrainer.core.model.ChessResult
import org.chesstrainer.core.model.Color
import org.chesstrainer.core.model.Fen
import org.chesstrainer.core.model.Move
import org.chesstrainer.core.model.Piece
import org.chesstrainer.core.model.PieceType
import org.chesstrainer.core.model.Position
import org.chesstrainer.core.model.Square
import org.chesstrainer.core.model.Uci
import com.github.bhlangonijr.chesslib.Piece as LibPiece
import com.github.bhlangonijr.chesslib.Side as LibSide
import com.github.bhlangonijr.chesslib.Square as LibSquare

/**
 * [ChessRules] поверх chesslib (генерація легальних ходів). Усе інше (модель позиції, FEN, SAN,
 * застосування ходу, ключ позиції, недостатній матеріал) реалізовано тут, щоб не залежати від
 * поведінки chesslib поза генерацією ходів. Екземпляр не має стану й потокобезпечний.
 */
class ChesslibRules : ChessRules {

    private val start: Position by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val parsed = parseFen(START_FEN)
        (parsed as ChessResult.Ok).value
    }

    override fun startPosition(): Position = start

    // ---------------------------------------------------------------- FEN

    override fun parseFen(fen: String): ChessResult<Position> {
        val fields = fen.trim().split(WHITESPACE)
        if (fields.size !in 4..6) return invalidFen("expected 4 to 6 fields, got ${fields.size}")

        val board = arrayOfNulls<Piece>(64)
        val ranks = fields[0].split('/')
        if (ranks.size != 8) return invalidFen("expected 8 ranks, got ${ranks.size}")
        for ((i, rankText) in ranks.withIndex()) {
            val rank = 7 - i
            var file = 0
            for (ch in rankText) {
                if (ch in '1'..'8') {
                    file += ch - '0'
                } else {
                    val piece = ChessPosition.pieceOf(ch) ?: return invalidFen("unknown piece '$ch'")
                    if (file > 7) return invalidFen("rank ${rank + 1} is too long")
                    if (piece.type == PieceType.PAWN && (rank == 0 || rank == 7)) {
                        return invalidFen("pawn on the first or last rank")
                    }
                    board[rank * 8 + file] = piece
                    file++
                }
                if (file > 8) return invalidFen("rank ${rank + 1} is too long")
            }
            if (file != 8) return invalidFen("rank ${rank + 1} does not have 8 squares")
        }
        for (color in Color.entries) {
            val kings = board.count { it == Piece(color, PieceType.KING) }
            if (kings != 1) return invalidFen("expected exactly one $color king, found $kings")
        }

        val side = when (fields[1]) {
            "w" -> Color.WHITE
            "b" -> Color.BLACK
            else -> return invalidFen("bad side to move '${fields[1]}'")
        }

        val castlingText = fields[2]
        val castling = parseCastling(castlingText) ?: return invalidFen("bad castling field '$castlingText'")
        castlingConsistencyError(board, castling)?.let { return invalidFen(it) }

        val epText = fields[3]
        val ep: Square? = if (epText == "-") {
            null
        } else {
            val sq = Square.parse(epText) ?: return invalidFen("bad en passant square '$epText'")
            epConsistencyError(board, sq, side)?.let { return invalidFen(it) }
            sq
        }

        val halfmove = if (fields.size > 4) fields[4].toIntOrNull() else 0
        if (halfmove == null || halfmove < 0) return invalidFen("bad halfmove clock '${fields[4]}'")
        val fullmove = if (fields.size > 5) fields[5].toIntOrNull() else 1
        if (fullmove == null || fullmove < 1) return invalidFen("bad fullmove number '${fields[5]}'")

        val position = newPosition(board, side, castling, ep, halfmove, fullmove)

        // Сторона, яка щойно походила, не може лишити свого короля під боєм... і навпаки:
        // король супротивника, чий хід не наступний, не має бути під шахом.
        val libBoard = try {
            loadBoard(position)
        } catch (e: Exception) {
            return invalidFen("rejected by the move generator: ${e.message}")
        }
        val waiting = side.opposite
        val waitingKing = board.indexOfFirst { it == Piece(waiting, PieceType.KING) }
        if (libBoard.squareAttackedBy(libSquare(waitingKing), libSide(side)) != 0L) {
            return invalidFen("the side not to move is in check")
        }
        return ChessResult.Ok(position)
    }

    private fun invalidFen(reason: String): ChessResult.Err = ChessResult.Err(ChessError.InvalidFen(reason))

    private fun parseCastling(text: String): CastlingRights? {
        if (text == "-") return CastlingRights(false, false, false, false)
        if (text.isEmpty() || text.toSet().size != text.length || text.any { it !in "KQkq" }) return null
        return CastlingRights(
            whiteKingSide = 'K' in text,
            whiteQueenSide = 'Q' in text,
            blackKingSide = 'k' in text,
            blackQueenSide = 'q' in text,
        )
    }

    private fun castlingConsistencyError(board: Array<Piece?>, rights: CastlingRights): String? {
        fun has(square: String, color: Color, type: PieceType) =
            board[Square.parse(square)!!.index] == Piece(color, type)
        fun check(flag: Boolean, king: String, rook: String, color: Color, name: String): String? =
            if (flag && !(has(king, color, PieceType.KING) && has(rook, color, PieceType.ROOK))) {
                "castling right $name without king and rook on their squares"
            } else {
                null
            }
        return check(rights.whiteKingSide, "e1", "h1", Color.WHITE, "K")
            ?: check(rights.whiteQueenSide, "e1", "a1", Color.WHITE, "Q")
            ?: check(rights.blackKingSide, "e8", "h8", Color.BLACK, "k")
            ?: check(rights.blackQueenSide, "e8", "a8", Color.BLACK, "q")
    }

    private fun epConsistencyError(board: Array<Piece?>, ep: Square, side: Color): String? {
        // Чий хід, того взяття; пішак супротивника щойно пішов із другої (сьомої) горизонталі.
        val (epRank, pawnRank, fromRank, pawnColor) =
            if (side == Color.WHITE) EpGeometry(5, 4, 6, Color.BLACK) else EpGeometry(2, 3, 1, Color.WHITE)
        if (ep.rank != epRank) return "en passant square ${ep.name} is on the wrong rank"
        if (board[ep.index] != null || board[Square.of(ep.file, fromRank).index] != null) {
            return "en passant square ${ep.name} is not consistent with a double pawn move"
        }
        if (board[Square.of(ep.file, pawnRank).index] != Piece(pawnColor, PieceType.PAWN)) {
            return "no pawn behind the en passant square ${ep.name}"
        }
        return null
    }

    private data class EpGeometry(val epRank: Int, val pawnRank: Int, val fromRank: Int, val pawnColor: Color)

    private fun newPosition(
        board: Array<Piece?>,
        side: Color,
        castling: CastlingRights,
        ep: Square?,
        halfmove: Int,
        fullmove: Int,
    ): ChessPosition = ChessPosition(board, side, castling, ep, halfmove, fullmove, ::isEnPassantLegal)

    private fun isEnPassantLegal(position: ChessPosition): Boolean {
        val ep = position.enPassant ?: return false
        return legalMoves(position).any { it.to == ep && position.pieceAt(it.from)?.type == PieceType.PAWN }
    }

    // ---------------------------------------------------------------- Ходи

    override fun legalMoves(position: Position): List<Move> =
        loadBoard(position).legalMoves().map(::toMove)

    override fun legalMovesFrom(position: Position, from: Square): List<Move> =
        legalMoves(position).filter { it.from == from }

    override fun apply(position: Position, move: Move): ChessResult<Position> {
        if (move !in legalMoves(position)) return ChessResult.Err(ChessError.IllegalMove(toUci(move).value))
        return ChessResult.Ok(applyUnchecked(position, move))
    }

    /** Будує позицію після [move]; хід має бути легальним. */
    private fun applyUnchecked(position: Position, move: Move): ChessPosition {
        val board = arrayOfNulls<Piece>(64)
        for (i in 0..63) board[i] = position.pieceAt(Square(i))
        val piece = board[move.from.index] ?: error("No piece on ${move.from.name}")
        val captured = board[move.to.index]
        val side = position.sideToMove

        var capturedAny = captured != null
        board[move.from.index] = null
        board[move.to.index] = if (move.promotion != null) Piece(side, move.promotion) else piece

        if (piece.type == PieceType.PAWN && captured == null && move.from.file != move.to.file) {
            board[Square.of(move.to.file, move.from.rank).index] = null // взяття на проході
            capturedAny = true
        }
        if (piece.type == PieceType.KING && kotlin.math.abs(move.to.file - move.from.file) == 2) {
            val rank = move.from.rank
            val kingSide = move.to.file > move.from.file
            val rookFrom = Square.of(if (kingSide) 7 else 0, rank).index
            val rookTo = Square.of(if (kingSide) 5 else 3, rank).index
            board[rookTo] = board[rookFrom]
            board[rookFrom] = null
        }

        val old = position.castling
        var wk = old.whiteKingSide
        var wq = old.whiteQueenSide
        var bk = old.blackKingSide
        var bq = old.blackQueenSide
        if (piece.type == PieceType.KING) {
            if (side == Color.WHITE) { wk = false; wq = false } else { bk = false; bq = false }
        }
        for (index in intArrayOf(move.from.index, move.to.index)) {
            when (index) {
                0 -> wq = false
                7 -> wk = false
                56 -> bq = false
                63 -> bk = false
            }
        }

        val ep = if (piece.type == PieceType.PAWN && kotlin.math.abs(move.to.rank - move.from.rank) == 2) {
            Square.of(move.from.file, (move.from.rank + move.to.rank) / 2)
        } else {
            null
        }
        val halfmove = if (piece.type == PieceType.PAWN || capturedAny) 0 else position.halfmoveClock + 1
        val fullmove = position.fullmoveNumber + if (side == Color.BLACK) 1 else 0
        return newPosition(board, side.opposite, CastlingRights(wk, wq, bk, bq), ep, halfmove, fullmove)
    }

    override fun parseUci(position: Position, uci: String): ChessResult<Move> {
        val illegal = ChessResult.Err(ChessError.IllegalMove(uci))
        if (uci.length != 4 && uci.length != 5) return illegal
        val from = Square.parse(uci.substring(0, 2)) ?: return illegal
        val to = Square.parse(uci.substring(2, 4)) ?: return illegal
        val promotion = if (uci.length == 5) {
            when (uci[4]) {
                'q' -> PieceType.QUEEN
                'r' -> PieceType.ROOK
                'b' -> PieceType.BISHOP
                'n' -> PieceType.KNIGHT
                else -> return illegal
            }
        } else {
            null
        }
        val move = Move(from, to, promotion)
        return if (move in legalMoves(position)) ChessResult.Ok(move) else illegal
    }

    override fun toUci(move: Move): Uci {
        val promo = when (move.promotion) {
            null -> ""
            PieceType.QUEEN -> "q"
            PieceType.ROOK -> "r"
            PieceType.BISHOP -> "b"
            PieceType.KNIGHT -> "n"
            else -> throw IllegalArgumentException("Invalid promotion piece: ${move.promotion}")
        }
        return Uci("${move.from.name}${move.to.name}$promo")
    }

    // ---------------------------------------------------------------- SAN

    override fun toSan(position: Position, move: Move): String {
        val moves = legalMoves(position)
        require(move in moves) { "Illegal move ${toUci(move).value} in ${position.fen.value}" }
        val piece = position.pieceAt(move.from)!!
        val isCapture = position.pieceAt(move.to) != null ||
            (piece.type == PieceType.PAWN && move.from.file != move.to.file)

        val body = when {
            piece.type == PieceType.KING && kotlin.math.abs(move.to.file - move.from.file) == 2 ->
                if (move.to.file > move.from.file) "O-O" else "O-O-O"
            piece.type == PieceType.PAWN -> buildString {
                if (isCapture) append("${'a' + move.from.file}x")
                append(move.to.name)
                move.promotion?.let { append('=').append(pieceLetter(it)) }
            }
            else -> buildString {
                append(pieceLetter(piece.type))
                val rivals = moves.filter {
                    it != move && it.to == move.to && position.pieceAt(it.from) == piece
                }
                if (rivals.isNotEmpty()) {
                    val sameFile = rivals.any { it.from.file == move.from.file }
                    val sameRank = rivals.any { it.from.rank == move.from.rank }
                    if (!sameFile) {
                        append('a' + move.from.file)
                    } else if (!sameRank) {
                        append('1' + move.from.rank)
                    } else {
                        append(move.from.name)
                    }
                }
                if (isCapture) append('x')
                append(move.to.name)
            }
        }

        val after = applyUnchecked(position, move)
        val afterBoard = loadBoard(after)
        val suffix = when {
            !afterBoard.isKingAttacked() -> ""
            afterBoard.legalMoves().isEmpty() -> "#"
            else -> "+"
        }
        return body + suffix
    }

    private fun pieceLetter(type: PieceType): Char = when (type) {
        PieceType.KNIGHT -> 'N'
        PieceType.BISHOP -> 'B'
        PieceType.ROOK -> 'R'
        PieceType.QUEEN -> 'Q'
        PieceType.KING -> 'K'
        PieceType.PAWN -> throw IllegalArgumentException("Pawn has no SAN letter")
    }

    override fun parseSan(position: Position, san: String): ChessResult<Move> {
        val illegal = ChessResult.Err(ChessError.IllegalMove(san))
        val text = san.trim().trimEnd('+', '#', '!', '?').replace('0', 'O')
        val moves = legalMoves(position)

        if (text == "O-O" || text == "O-O-O") {
            val kingSide = text == "O-O"
            val found = moves.filter {
                position.pieceAt(it.from)?.type == PieceType.KING &&
                    it.to.file - it.from.file == if (kingSide) 2 else -2
            }
            return if (found.size == 1) ChessResult.Ok(found[0]) else illegal
        }

        val match = SAN_PATTERN.matchEntire(text) ?: return illegal
        val (pieceText, fileHint, rankHint, targetText, promoText) = match.destructured
        val target = Square.parse(targetText) ?: return illegal
        val type = if (pieceText.isEmpty()) PieceType.PAWN else typeOfLetter(pieceText[0])
        val promotion = if (promoText.isEmpty()) null else typeOfLetter(promoText[0])
        if (promotion != null && type != PieceType.PAWN) return illegal

        val found = moves.filter { m ->
            val p = position.pieceAt(m.from)
            m.to == target &&
                p?.type == type &&
                m.promotion == promotion &&
                (fileHint.isEmpty() || m.from.file == fileHint[0] - 'a') &&
                (rankHint.isEmpty() || m.from.rank == rankHint[0] - '1') &&
                (type != PieceType.PAWN || fileHint.isNotEmpty() || m.from.file == m.to.file)
        }
        return if (found.size == 1) ChessResult.Ok(found[0]) else illegal
    }

    private fun typeOfLetter(letter: Char): PieceType = when (letter) {
        'N' -> PieceType.KNIGHT
        'B' -> PieceType.BISHOP
        'R' -> PieceType.ROOK
        'Q' -> PieceType.QUEEN
        else -> PieceType.KING
    }

    // ---------------------------------------------------------------- Статус

    override fun isInCheck(position: Position): Boolean = loadBoard(position).isKingAttacked()

    override fun needsPromotion(position: Position, from: Square, to: Square): Boolean =
        legalMovesFrom(position, from).any { it.to == to && it.promotion != null }

    override fun isCheckmate(position: Position): Boolean {
        val board = loadBoard(position)
        return board.isKingAttacked() && board.legalMoves().isEmpty()
    }

    override fun isStalemate(position: Position): Boolean {
        val board = loadBoard(position)
        return !board.isKingAttacked() && board.legalMoves().isEmpty()
    }

    /** Правило CONTRACTS 3.2: мат неможливий за жодної послідовності ходів (без аналізу заблокованих пішаків). */
    override fun isInsufficientMaterial(position: Position): Boolean {
        var knights = 0
        var bishopsOnLight = 0
        var bishopsOnDark = 0
        for (i in 0..63) {
            val piece = position.pieceAt(Square(i)) ?: continue
            when (piece.type) {
                PieceType.KING -> Unit
                PieceType.KNIGHT -> knights++
                PieceType.BISHOP -> {
                    val sq = Square(i)
                    if ((sq.file + sq.rank) % 2 == 1) bishopsOnLight++ else bishopsOnDark++
                }
                else -> return false
            }
        }
        val bishops = bishopsOnLight + bishopsOnDark
        return when {
            knights == 0 && bishops == 0 -> true
            knights == 1 && bishops == 0 -> true
            knights == 0 -> bishopsOnLight == 0 || bishopsOnDark == 0
            else -> false
        }
    }

    // ---------------------------------------------------------------- chesslib

    private fun loadBoard(position: Position): Board = Board().also { it.loadFromFen(position.fen.value) }

    private fun toMove(move: LibMove): Move {
        val promotion = move.promotion
        return Move(
            from = square(move.from),
            to = square(move.to),
            promotion = if (promotion == LibPiece.NONE) null else pieceType(promotion),
        )
    }

    private fun square(square: LibSquare): Square = Square.parse(square.name.lowercase())!!

    private fun libSquare(index: Int): LibSquare = LIB_SQUARES[index]

    private fun libSide(color: Color): LibSide = if (color == Color.WHITE) LibSide.WHITE else LibSide.BLACK

    private fun pieceType(piece: LibPiece): PieceType = when (piece.pieceType!!.name) {
        "KNIGHT" -> PieceType.KNIGHT
        "BISHOP" -> PieceType.BISHOP
        "ROOK" -> PieceType.ROOK
        "QUEEN" -> PieceType.QUEEN
        "KING" -> PieceType.KING
        else -> PieceType.PAWN
    }

    private companion object {
        const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        val WHITESPACE = Regex("\\s+")
        val SAN_PATTERN = Regex("([NBRQK])?([a-h])?([1-8])?x?([a-h][1-8])(?:=?([NBRQ]))?")
        val LIB_SQUARES: Array<LibSquare> = Array(64) { LibSquare.valueOf(Square(it).name.uppercase()) }
    }
}
