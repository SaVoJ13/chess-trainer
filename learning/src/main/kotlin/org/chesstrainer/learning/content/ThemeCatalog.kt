// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.learning.content

enum class ThemeKind { TACTIC, PHASE }

/** Тема, що потрапляє в дані застосунку. [id] це `ThemeId` (CONTRACTS 4.1), ключ рядка `puzzle_theme_<id>`. */
data class ThemeInfo(val id: String, val kind: ThemeKind, val lichessTag: String)

/** Що робити з тегом Lichess під час підготовки. */
enum class TagDisposition {
    /** Перетворюється на [ThemeInfo]. */
    ACCEPTED,

    /** Тег не переноситься, але задача лишається (метадані, оцінка результату, довжина, стратегічні ярлики). */
    STRIPPED,

    /** Задача відкидається: розв'язок спирається на ідею, зайву для початківця (MVP). */
    TOO_COMPLEX,
}

/**
 * Каталог тем для MVP (`content/README.md`, розділ «Теми»). Порядок у [accepted] визначає «головну» тему
 * задачі при розподілі вибірки: спочатку специфічні шаблони, потім загальніші (мат у N, жертва), потім фази.
 * Тег, якого тут немає, вважається невідомим: задача відхиляється й рахується окремо
 * (нові теги Lichess потрапляють у каталог лише свідомим рішенням).
 */
object ThemeCatalog {
    val accepted: List<ThemeInfo> = listOf(
        ThemeInfo("smothered_mate", ThemeKind.TACTIC, "smotheredMate"),
        ThemeInfo("back_rank_mate", ThemeKind.TACTIC, "backRankMate"),
        ThemeInfo("double_check", ThemeKind.TACTIC, "doubleCheck"),
        ThemeInfo("discovered_check", ThemeKind.TACTIC, "discoveredCheck"),
        ThemeInfo("fork", ThemeKind.TACTIC, "fork"),
        ThemeInfo("pin", ThemeKind.TACTIC, "pin"),
        ThemeInfo("skewer", ThemeKind.TACTIC, "skewer"),
        ThemeInfo("discovered_attack", ThemeKind.TACTIC, "discoveredAttack"),
        ThemeInfo("deflection", ThemeKind.TACTIC, "deflection"),
        ThemeInfo("attraction", ThemeKind.TACTIC, "attraction"),
        ThemeInfo("trapped_piece", ThemeKind.TACTIC, "trappedPiece"),
        ThemeInfo("hanging_piece", ThemeKind.TACTIC, "hangingPiece"),
        ThemeInfo("promotion", ThemeKind.TACTIC, "promotion"),
        ThemeInfo("mate_in_1", ThemeKind.TACTIC, "mateIn1"),
        ThemeInfo("mate_in_2", ThemeKind.TACTIC, "mateIn2"),
        ThemeInfo("mate_in_3", ThemeKind.TACTIC, "mateIn3"),
        ThemeInfo("sacrifice", ThemeKind.TACTIC, "sacrifice"),
        ThemeInfo("opening", ThemeKind.PHASE, "opening"),
        ThemeInfo("middlegame", ThemeKind.PHASE, "middlegame"),
        ThemeInfo("endgame", ThemeKind.PHASE, "endgame"),
    )

    /** Теги, що не переносяться, але задачу не відкидають. */
    val stripped: Set<String> = setOf(
        // оцінка результату й довжина (довжину дає solution)
        "crushing", "advantage", "equality", "mate", "short", "long", "veryLong", "oneMove",
        // походження позиції
        "master", "masterVsMaster", "superGM",
        // стратегічні ярлики
        "kingsideAttack", "queensideAttack", "exposedKing", "defensiveMove", "advancedPawn", "attackingF2F7",
        // тип ендшпілю: окрема тема курсу ендшпілів (Пізніше)
        "pawnEndgame", "rookEndgame", "bishopEndgame", "knightEndgame", "queenEndgame", "queenRookEndgame",
        // назви матових шаблонів: показуємо як mate_in_N; окремі теми Пізніше
        "anastasiaMate", "arabianMate", "bodenMate", "doubleBishopMate", "dovetailMate", "hookMate", "killBoxMate",
        "vukovicMate", "swallowstailMate", "triangleMate", "balestraMate", "blindSwineMate", "cornerMate",
        "epauletteMate", "morphysMate", "operaMate", "pillsburysMate",
    )

    /** Теги, за якими задача відкидається (причини в `content/README.md`). */
    val tooComplex: Set<String> = setOf(
        "capturingDefender", "clearance", "interference", "xRayAttack", "quietMove", "zugzwang", "intermezzo",
        "underPromotion", "enPassant", "castling", "mateIn4", "mateIn5", "collinearMove",
    )

    private val byTag: Map<String, ThemeInfo> = accepted.associateBy { it.lichessTag }
    private val byId: Map<String, ThemeInfo> = accepted.associateBy { it.id }

    val acceptedIds: Set<String> = byId.keys
    val tacticIds: List<String> = accepted.filter { it.kind == ThemeKind.TACTIC }.map { it.id }

    fun isAccepted(id: String): Boolean = id in byId
    fun info(id: String): ThemeInfo? = byId[id]
    fun themeForTag(tag: String): ThemeInfo? = byTag[tag]

    /** null = тег невідомий каталогу. */
    fun classify(tag: String): TagDisposition? = when {
        tag in byTag -> TagDisposition.ACCEPTED
        tag in stripped -> TagDisposition.STRIPPED
        tag in tooComplex -> TagDisposition.TOO_COMPLEX
        else -> null
    }

    /** Порядок головної теми: індекс у [accepted] (менше = вищий пріоритет). */
    fun priority(id: String): Int = accepted.indexOfFirst { it.id == id }.let { if (it < 0) Int.MAX_VALUE else it }
}
