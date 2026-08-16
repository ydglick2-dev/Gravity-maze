#pragma once

#include <atomic>
#include <cstddef>
#include <cstring>
#include <memory>

namespace kolan {

/**
 * Single-producer / single-consumer lock-free FIFO of floats.
 *
 * The capacity is rounded up to a power of two so that index wrapping is a mask instead of a
 * modulo. Exactly one thread may call write() and exactly one (different) thread may call
 * read(); with that discipline no lock is needed and neither side ever blocks the other.
 *
 * The allocation happens in the constructor only. Both write() and read() are allocation-free,
 * so they are safe to call from an audio callback.
 */
class RingBuffer {
public:
    RingBuffer() = default;

    explicit RingBuffer(size_t minCapacity) { resize(minCapacity); }

    /** Allocates. Never call this while the audio callback is running. */
    void resize(size_t minCapacity) {
        size_t cap = 2;
        while (cap < minCapacity) cap <<= 1;
        capacity_ = cap;
        mask_ = cap - 1;
        data_ = std::make_unique<float[]>(cap);
        std::memset(data_.get(), 0, cap * sizeof(float));
        writeIndex_.store(0, std::memory_order_relaxed);
        readIndex_.store(0, std::memory_order_relaxed);
    }

    void clear() {
        writeIndex_.store(0, std::memory_order_relaxed);
        readIndex_.store(0, std::memory_order_relaxed);
        if (data_) std::memset(data_.get(), 0, capacity_ * sizeof(float));
    }

    size_t capacity() const { return capacity_; }

    /** Frames currently readable. Safe to call from either side. */
    size_t available() const {
        const size_t w = writeIndex_.load(std::memory_order_acquire);
        const size_t r = readIndex_.load(std::memory_order_acquire);
        return w - r;
    }

    /** Frames that can still be written without overwriting unread data. */
    size_t space() const { return capacity_ - available(); }

    /**
     * Producer side. Writes up to `count` frames, returns how many were actually written
     * (short only when the buffer is full).
     */
    size_t write(const float* src, size_t count) {
        const size_t w = writeIndex_.load(std::memory_order_relaxed);
        const size_t r = readIndex_.load(std::memory_order_acquire);
        const size_t free = capacity_ - (w - r);
        const size_t n = count < free ? count : free;
        if (n == 0) return 0;

        const size_t start = w & mask_;
        const size_t first = (n < capacity_ - start) ? n : capacity_ - start;
        std::memcpy(data_.get() + start, src, first * sizeof(float));
        if (n > first) {
            std::memcpy(data_.get(), src + first, (n - first) * sizeof(float));
        }
        writeIndex_.store(w + n, std::memory_order_release);
        return n;
    }

    /**
     * Consumer side. Reads up to `count` frames, returns how many were actually read. Frames
     * that could not be filled are left untouched in `dst`.
     */
    size_t read(float* dst, size_t count) {
        const size_t r = readIndex_.load(std::memory_order_relaxed);
        const size_t w = writeIndex_.load(std::memory_order_acquire);
        const size_t avail = w - r;
        const size_t n = count < avail ? count : avail;
        if (n == 0) return 0;

        const size_t start = r & mask_;
        const size_t first = (n < capacity_ - start) ? n : capacity_ - start;
        std::memcpy(dst, data_.get() + start, first * sizeof(float));
        if (n > first) {
            std::memcpy(dst + first, data_.get(), (n - first) * sizeof(float));
        }
        readIndex_.store(r + n, std::memory_order_release);
        return n;
    }

    /** Drops the oldest frames so that at most `keep` remain. Used to recover from overrun. */
    void trimTo(size_t keep) {
        const size_t w = writeIndex_.load(std::memory_order_acquire);
        const size_t r = readIndex_.load(std::memory_order_relaxed);
        const size_t avail = w - r;
        if (avail > keep) {
            readIndex_.store(w - keep, std::memory_order_release);
        }
    }

private:
    std::unique_ptr<float[]> data_;
    size_t capacity_ = 0;
    size_t mask_ = 0;
    std::atomic<size_t> writeIndex_{0};
    std::atomic<size_t> readIndex_{0};
};

}  // namespace kolan
