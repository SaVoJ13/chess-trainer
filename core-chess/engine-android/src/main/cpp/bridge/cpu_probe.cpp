// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors
//
// Визначає, чи має процесор інструкції dot product (asimddp), через getauxval(AT_HWCAP).
// Окрема мала бібліотека: рушій у варіанті dotprod не можна навіть завантажувати наосліп.

#include <jni.h>
#include <sys/auxv.h>

#ifndef HWCAP_ASIMDDP
#define HWCAP_ASIMDDP (1 << 20)  // arch/arm64/include/uapi/asm/hwcap.h
#endif

extern "C" JNIEXPORT jboolean JNICALL
Java_org_chesstrainer_engine_NativeEngine_nativeHasDotProd(JNIEnv*, jclass) {
#if defined(__aarch64__)
    return (getauxval(AT_HWCAP) & HWCAP_ASIMDDP) != 0 ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}
