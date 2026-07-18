#ifndef COMMON_H
#define COMMON_H

#include <string>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <utility>

struct BufferReader {
    int fd;
    off_t offset;
    off_t fileSize;

    BufferReader(const std::string& filePath) : offset(0), fd(-1), fileSize(0) {
        fd = open(filePath.c_str(), O_RDONLY);
        if (fd >= 0) {
            struct stat st;
            if (fstat(fd, &st) == 0) {
                fileSize = st.st_size;
            }
        }
    }

    ~BufferReader() {
        if (fd >= 0) {
            close(fd);
        }
    }

    bool isValid() const {
        return fd >= 0;
    }

    void seek(off_t off) {
        offset = off;
        lseek(fd, off, SEEK_SET);
    }

    off_t tell() const {
        return offset;
    }

    void skip(off_t bytes) {
        seek(offset + bytes);
    }

    bool readBytes(void* dest, size_t count) {
        if (fd < 0 || offset + (off_t)count > fileSize) {
            return false;
        }
        ssize_t bytesRead = pread(fd, dest, count, offset);
        if (bytesRead < 0) return false;
        offset += bytesRead;
        return bytesRead == (ssize_t)count;
    }

    uint32_t readU32() {
        uint8_t buf[4];
        if (!readBytes(buf, 4)) return 0;
        return (buf[0] << 24) | (buf[1] << 16) | (buf[2] << 8) | buf[3];
    }

    uint64_t readU64() {
        uint8_t buf[8];
        if (!readBytes(buf, 8)) return 0;
        return ((uint64_t)buf[0] << 56) | ((uint64_t)buf[1] << 48) |
               ((uint64_t)buf[2] << 40) | ((uint64_t)buf[3] << 32) |
               ((uint64_t)buf[4] << 24) | ((uint64_t)buf[5] << 16) |
               ((uint64_t)buf[6] << 8)  | buf[7];
    }

    uint16_t readU16() {
        uint8_t buf[2];
        if (!readBytes(buf, 2)) return 0;
        return (buf[0] << 8) | buf[1];
    }

    uint8_t readU8() {
        uint8_t val = 0;
        readBytes(&val, 1);
        return val;
    }

    std::string readString(size_t len) {
        std::string s(len, '\0');
        if (readBytes(&s[0], len)) {
            return s;
        }
        return "";
    }
};

inline std::string escapeJson(const std::string& s) {
    std::string out;
    for (char c : s) {
        if (c == '"') out += "\\\"";
        else if (c == '\\') out += "\\\\";
        else if (c == '\n') out += "\\n";
        else if (c == '\r') out += "\\r";
        else if (c == '\t') out += "\\t";
        else if (c >= 0 && c < 32) {}
        else out += c;
    }
    return out;
}

inline std::pair<int64_t, int> readVint(BufferReader& reader, bool isId) {
    uint8_t firstByte = reader.readU8();
    if (firstByte == 0) {
        return {-1, 1};
    }

    int length = 1;
    uint8_t mask = 0x80;
    while (mask > 0) {
        if ((firstByte & mask) != 0) {
            break;
        }
        length++;
        mask >>= 1;
    }

    if (length > 8) {
        return {-1, length};
    }

    int64_t value = firstByte;
    if (!isId) {
        value &= (mask - 1);
    }

    for (int i = 0; i < length - 1; i++) {
        uint8_t nextByte = reader.readU8();
        value = (value << 8) | nextByte;
    }

    if (!isId) {
        int64_t allOnes = (1LL << (7 * length)) - 1;
        if (value == allOnes) {
            return {-1, length};
        }
    }

    return {value, length};
}

#endif // COMMON_H
