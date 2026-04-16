package cn.classfun.droidvm.lib.store.base;

import androidx.annotation.NonNull;

public class RingBuffer {
    private final byte[] buffer;
    public final int capacity;
    private int readPos = 0;
    private int writePos = 0;

    public RingBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = new byte[capacity + 1];
    }

    public synchronized boolean isEmpty() {
        return readPos == writePos;
    }

    public synchronized boolean isFull() {
        return (writePos + 1) % buffer.length == readPos;
    }

    public synchronized boolean add(byte value) {
        if ((writePos + 1) % buffer.length == readPos) return false;
        buffer[writePos] = value;
        writePos = (writePos + 1) % buffer.length;
        return true;
    }

    public synchronized byte pop() {
        if (readPos == writePos)
            throw new IllegalStateException("Buffer is empty");
        byte value = buffer[readPos];
        readPos = (readPos + 1) % buffer.length;
        return value;
    }

    public synchronized int size() {
        return (writePos - readPos + buffer.length) % buffer.length;
    }

    public synchronized boolean adds(@NonNull byte[] data) {
        return adds(data, 0, data.length);
    }

    public synchronized boolean adds(@NonNull byte[] data, int off, int len) {
        if (len > capacity) {
            off += len - capacity;
            len = capacity;
            readPos = 0;
            writePos = 0;
        } else {
            int free = (readPos - writePos - 1 + buffer.length) % buffer.length;
            if (free < len) {
                int discard = len - free;
                readPos = (readPos + discard) % buffer.length;
            }
        }
        int firstPart = Math.min(len, buffer.length - writePos);
        System.arraycopy(data, off, buffer, writePos, firstPart);
        if (firstPart < len)
            System.arraycopy(data, off + firstPart, buffer, 0, len - firstPart);
        writePos = (writePos + len) % buffer.length;
        return true;
    }

    public synchronized byte[] pops(int n) {
        int available = (writePos - readPos + buffer.length) % buffer.length;
        if (n > available) n = available;
        if (n == 0) return new byte[0];
        var result = new byte[n];
        int firstPart = Math.min(n, buffer.length - readPos);
        System.arraycopy(buffer, readPos, result, 0, firstPart);
        if (firstPart < n)
            System.arraycopy(buffer, 0, result, firstPart, n - firstPart);
        readPos = (readPos + n) % buffer.length;
        return result;
    }

    @NonNull
    public synchronized byte[] peekAll() {
        int available = (writePos - readPos + buffer.length) % buffer.length;
        if (available == 0) return new byte[0];
        var result = new byte[available];
        int firstPart = Math.min(available, buffer.length - readPos);
        System.arraycopy(buffer, readPos, result, 0, firstPart);
        if (firstPart < available)
            System.arraycopy(buffer, 0, result, firstPart, available - firstPart);
        return result;
    }

    public synchronized void clear() {
        readPos = 0;
        writePos = 0;
    }
}
