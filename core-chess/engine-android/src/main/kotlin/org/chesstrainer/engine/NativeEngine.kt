// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors

package org.chesstrainer.engine

/**
 * JNI-межа до Stockfish (`cpp/bridge/sf_bridge.cpp`). У процесі живе один рушій.
 * Бібліотеку обирає [load] за HWCAP `asimddp`; примусовий вибір базової потрібен лише для налагодження.
 */
object NativeEngine {
    const val LIB_BASELINE = "stockfish_armv8"
    const val LIB_DOTPROD = "stockfish_armv8_dotprod"

    private val lock = Any()
    private var loadedName: String? = null

    /** Ім'я завантаженої бібліотеки рушія; `null`, доки [load] не викликали. */
    val loadedLibrary: String? get() = synchronized(lock) { loadedName }

    /**
     * Завантажує рушій для цього процесу й повертає ім'я бібліотеки. Повторний виклик повертає вже
     * завантажену (бібліотеку не можна замінити в межах процесу, тому [forceBaseline] тоді ігнорується).
     */
    fun load(forceBaseline: Boolean = false): String = synchronized(lock) {
        loadedName?.let { return it }
        System.loadLibrary("sfprobe")
        val name = if (!forceBaseline && nativeHasDotProd()) LIB_DOTPROD else LIB_BASELINE
        System.loadLibrary(name)
        loadedName = name
        name
    }

    @JvmStatic external fun nativeHasDotProd(): Boolean

    /** 0 — запущено, 1 — уже запущено, від'ємне — помилка. */
    @JvmStatic external fun nativeStart(): Int

    @JvmStatic external fun nativeSend(line: String)

    /** Рядок виводу; `null` — таймаут; [EOF] — рушій завершився. */
    @JvmStatic external fun nativeReadLine(timeoutMs: Int): String?

    @JvmStatic external fun nativeStop()

    /** Викликає `abort()` лише в debug-збірці застосунку; у release повертає `false`. */
    @JvmStatic external fun nativeCrash(): Boolean

    const val EOF = "\u0001EOF"
}
