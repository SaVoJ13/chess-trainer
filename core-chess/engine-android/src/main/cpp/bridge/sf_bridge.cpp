// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2026 Chess Trainer contributors
//
// JNI-міст до Stockfish sf_19. UCI-ввід і вивід ідуть через власні streambuf замість
// stdin/stdout процесу: std::cin і std::cout підміняються буферами з черг рядків, а
// UCIEngine::loop() виконується без змін у власному потоці.
//
// Один UCIEngine на весь час життя процесу: у sf_19 OptionsMap::add має `static usize insert_order`,
// тому другий UCIEngine у тому самому процесі не друкує опції у відповідь на "uci". nativeStop()
// лише гасить пошук і повертає рушій у початковий стан; звільнити пам'ять повністю можна лише
// завершенням процесу (режим remote).

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <iostream>
#include <memory>
#include <mutex>
#include <streambuf>
#include <string>

#include "attacks.h"
#include "misc.h"
#include "position.h"
#include "tune.h"
#include "uci.h"

namespace {

// Черга рядків із блокуючим читанням. `closed` будить читача, коли рушій завершився.
void on_output_line(const std::string& line);

class LineQueue {
   public:
    void push(std::string line) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            lines_.push_back(std::move(line));
        }
        cv_.notify_one();
    }

    void close() {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            closed_ = true;
        }
        cv_.notify_all();
    }

    void reset() {
        std::lock_guard<std::mutex> lock(mutex_);
        lines_.clear();
        closed_ = false;
    }

    enum class Result { Line, Timeout, Closed };

    // timeoutMs < 0: чекати без обмеження.
    Result pop(std::string& out, long timeoutMs) {
        std::unique_lock<std::mutex> lock(mutex_);
        auto ready = [this] { return !lines_.empty() || closed_; };
        if (timeoutMs < 0)
            cv_.wait(lock, ready);
        else if (!cv_.wait_for(lock, std::chrono::milliseconds(timeoutMs), ready))
            return Result::Timeout;
        if (lines_.empty())
            return Result::Closed;
        out = std::move(lines_.front());
        lines_.pop_front();
        return Result::Line;
    }

   private:
    std::mutex              mutex_;
    std::condition_variable cv_;
    std::deque<std::string> lines_;
    bool                    closed_ = false;
};

LineQueue gInput;   // Kotlin -> Stockfish
LineQueue gOutput;  // Stockfish -> Kotlin

// Читає рядки з gInput. Завершення (closed) читач бачить як EOF.
class InputBuf : public std::streambuf {
   protected:
    int_type underflow() override {
        std::string line;
        if (gInput.pop(line, -1) != LineQueue::Result::Line)
            return traits_type::eof();
        buffer_ = std::move(line);
        buffer_.push_back('\n');
        setg(buffer_.data(), buffer_.data(), buffer_.data() + buffer_.size());
        return traits_type::to_int_type(*gptr());
    }

   private:
    std::string buffer_;
};

// Збирає символи в рядки й кладе їх у gOutput. Stockfish пише через sync_cout під власним мьютексом.
class OutputBuf : public std::streambuf {
   protected:
    int_type overflow(int_type ch) override {
        if (traits_type::eq_int_type(ch, traits_type::eof()))
            return traits_type::not_eof(ch);
        put(static_cast<char>(ch));
        return ch;
    }

    std::streamsize xsputn(const char* s, std::streamsize n) override {
        for (std::streamsize i = 0; i < n; ++i)
            put(s[i]);
        return n;
    }

   private:
    void put(char c) {
        if (c == '\n')
        {
            if (!line_.empty() && line_.back() == '\r')
                line_.pop_back();
            on_output_line(line_);
            gOutput.push(std::move(line_));
            line_.clear();
        }
        else
            line_.push_back(c);
    }

    std::string line_;
};

InputBuf  gInputBuf;
OutputBuf gOutputBuf;

// Стан рушія: скільки `go` ще не відповіли `bestmove`, і лічильник `readyok`.
std::mutex              gSyncMutex;
std::condition_variable gSyncCv;
int                     gInFlight    = 0;
unsigned                gReadyOkSeen = 0;

void on_output_line(const std::string& line) {
    std::lock_guard<std::mutex> lock(gSyncMutex);
    if (line.rfind("bestmove", 0) == 0 && gInFlight > 0)
        --gInFlight;
    else if (line == "readyok")
        ++gReadyOkSeen;
    gSyncCv.notify_all();
}

std::mutex      gLifecycle;
pthread_t       gThread;
bool            gThreadCreated = false;
bool            gSessionOpen   = false;
std::atomic<bool> gThreadAlive{false};

void* engine_main(void*) {
    Stockfish::Attacks::init();
    Stockfish::Position::init();

    char  arg0[] = "stockfish";
    char* argv[] = {arg0, nullptr};

    auto uci = std::make_unique<Stockfish::UCIEngine>(Stockfish::CommandLine(1, argv));
    Stockfish::Tune::init(uci->engine_options());
    uci->loop();
    uci.reset();

    gThreadAlive = false;
    gOutput.close();
    return nullptr;
}

jstring to_jstring(JNIEnv* env, const std::string& s) { return env->NewStringUTF(s.c_str()); }

// Чекає, доки [pred] стане true, не довше за timeoutMs.
template<typename Pred>
bool wait_for(int timeoutMs, Pred pred) {
    std::unique_lock<std::mutex> lock(gSyncMutex);
    return gSyncCv.wait_for(lock, std::chrono::milliseconds(timeoutMs), pred);
}

}  // namespace

extern "C" {

// 0 — сесію відкрито; від'ємне — помилка (-1 не вдалося створити потік, -2 рушій завершився).
JNIEXPORT jint JNICALL Java_org_chesstrainer_engine_NativeEngine_nativeStart(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(gLifecycle);
    if (gSessionOpen)
        return 0;

    gInput.reset();
    gOutput.reset();
    if (!gThreadCreated)
    {
        std::cin.rdbuf(&gInputBuf);
        std::cout.rdbuf(&gOutputBuf);
        std::cin.clear();
        std::cout.clear();

        // UCI-потік із запасом стека: на головному потоці Stockfish звично має 8 МБ.
        pthread_attr_t attr;
        pthread_attr_init(&attr);
        pthread_attr_setstacksize(&attr, 8u * 1024u * 1024u);
        gThreadAlive = true;
        const int rc = pthread_create(&gThread, &attr, engine_main, nullptr);
        pthread_attr_destroy(&attr);
        if (rc != 0)
        {
            gThreadAlive = false;
            return -1;
        }
        gThreadCreated = true;
    }
    else if (!gThreadAlive)
        return -2;

    gSessionOpen = true;
    return 0;
}

JNIEXPORT void JNICALL Java_org_chesstrainer_engine_NativeEngine_nativeSend(JNIEnv* env, jclass,
                                                                           jstring line) {
    const char* chars = env->GetStringUTFChars(line, nullptr);
    if (chars == nullptr)
        return;
    std::string text(chars);
    env->ReleaseStringUTFChars(line, chars);
    if (text.rfind("go", 0) == 0)
    {
        std::lock_guard<std::mutex> lock(gSyncMutex);
        ++gInFlight;
    }
    gInput.push(std::move(text));
}

// Рядок виводу; null — таймаут; "\u0001EOF" — сесію закрито й черга порожня.
JNIEXPORT jstring JNICALL Java_org_chesstrainer_engine_NativeEngine_nativeReadLine(JNIEnv* env, jclass,
                                                                                  jint timeoutMs) {
    std::string line;
    switch (gOutput.pop(line, timeoutMs))
    {
    case LineQueue::Result::Line :
        return to_jstring(env, line);
    case LineQueue::Result::Closed :
        return to_jstring(env, "\x01" "EOF");
    case LineQueue::Result::Timeout :
        break;
    }
    return nullptr;
}

// Закриває сесію: гасить пошук, чекає його `bestmove`, повертає рушій у чистий стан (звільняє таблицю
// транспозицій) і будить читача сигналом EOF. UCIEngine і потік лишаються (див. коментар угорі).
JNIEXPORT void JNICALL Java_org_chesstrainer_engine_NativeEngine_nativeStop(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(gLifecycle);
    if (!gSessionOpen)
        return;
    if (gThreadAlive)
    {
        gInput.push("stop");
        wait_for(5000, [] { return gInFlight == 0; });
        unsigned before;
        {
            std::lock_guard<std::mutex> sync(gSyncMutex);
            before = gReadyOkSeen;
        }
        gInput.push("setoption name Threads value 1");
        gInput.push("setoption name Hash value 1");
        gInput.push("ucinewgame");
        gInput.push("isready");
        wait_for(10000, [before] { return gReadyOkSeen != before; });
    }
    {
        std::lock_guard<std::mutex> sync(gSyncMutex);
        gInFlight = 0;
    }
    gOutput.close();
    gSessionOpen = false;
}

// Лише для debug-збірки застосунку (ENGINE_TEST_CRASH): імітує збій нативного коду.
JNIEXPORT jboolean JNICALL Java_org_chesstrainer_engine_NativeEngine_nativeCrash(JNIEnv*, jclass) {
#ifdef ENGINE_TEST_CRASH
    abort();
#else
    return JNI_FALSE;
#endif
}

}  // extern "C"
