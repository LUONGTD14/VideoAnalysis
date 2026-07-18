#include <jni.h>
#include <android/bitmap.h>
#include <string>
#include <vector>
#include <utility>
#include <cstring>
#include <unistd.h>
#include <fcntl.h>
#include <sys/types.h>
#include <sys/stat.h>

#include "common.h"
#include "mp4_parser.h"
#include "ebml_parser.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_luongtd14_videoanalysis_MediaBridge_parseMediaFile(
        JNIEnv* env,
        jclass /* clazz */,
        jstring filePathStr) {
        
    const char* filePath = env->GetStringUTFChars(filePathStr, nullptr);
    std::string path(filePath);
    env->ReleaseStringUTFChars(filePathStr, filePath);

    BufferReader reader(path);
    if (!reader.isValid()) {
        return env->NewStringUTF("[]");
    }

    uint8_t sig[4];
    std::string json;
    if (reader.readBytes(sig, 4)) {
        reader.seek(0);
        if (sig[0] == 0x1A && sig[1] == 0x45 && sig[2] == 0xDF && sig[3] == 0xA3) {
            json = parseEbmlStream(reader, reader.fileSize);
        } else {
            json = parseMp4BoxStream(reader, reader.fileSize);
        }
    } else {
        json = "[]";
    }

    return env->NewStringUTF(json.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_luongtd14_videoanalysis_MediaBridge_patchField(
        JNIEnv* env,
        jclass /* clazz */,
        jstring filePathStr,
        jlong offset,
        jstring typeStr,
        jdouble value) {
        
    const char* filePath = env->GetStringUTFChars(filePathStr, nullptr);
    std::string path(filePath);
    env->ReleaseStringUTFChars(filePathStr, filePath);

    const char* type = env->GetStringUTFChars(typeStr, nullptr);
    std::string t(type);
    env->ReleaseStringUTFChars(typeStr, type);

    int fd = open(path.c_str(), O_RDWR);
    if (fd < 0) {
        return JNI_FALSE;
    }

    lseek(fd, offset, SEEK_SET);
    bool success = true;

    if (t == "uint8" || t == "uint") {
        uint8_t val = (uint8_t)value;
        success = (write(fd, &val, 1) == 1);
    }
    else if (t == "uint16") {
        uint16_t val = (uint16_t)value;
        uint8_t buf[2] = { (uint8_t)(val >> 8), (uint8_t)(val & 0xFF) };
        success = (write(fd, buf, 2) == 2);
    }
    else if (t == "uint32") {
        uint32_t val = (uint32_t)value;
        uint8_t buf[4] = {
            (uint8_t)(val >> 24), (uint8_t)((val >> 16) & 0xFF),
            (uint8_t)((val >> 8) & 0xFF), (uint8_t)(val & 0xFF)
        };
        success = (write(fd, buf, 4) == 4);
    }
    else if (t == "uint64") {
        uint64_t val = (uint64_t)value;
        uint8_t buf[8];
        for (int i = 0; i < 8; i++) {
            buf[i] = (uint8_t)(val >> (56 - 8 * i));
        }
        success = (write(fd, buf, 8) == 8);
    }
    else if (t == "fixed16_16") {
        uint32_t val = (uint32_t)(value * 65536.0);
        uint8_t buf[4] = {
            (uint8_t)(val >> 24), (uint8_t)((val >> 16) & 0xFF),
            (uint8_t)((val >> 8) & 0xFF), (uint8_t)(val & 0xFF)
        };
        success = (write(fd, buf, 4) == 4);
    }
    else if (t == "float32" || t == "float") {
        float fVal = (float)value;
        uint32_t val;
        std::memcpy(&val, &fVal, 4);
        uint8_t buf[4] = {
            (uint8_t)(val >> 24), (uint8_t)((val >> 16) & 0xFF),
            (uint8_t)((val >> 8) & 0xFF), (uint8_t)(val & 0xFF)
        };
        success = (write(fd, buf, 4) == 4);
    }
    else if (t == "float64") {
        double dVal = value;
        uint64_t val;
        std::memcpy(&val, &dVal, 8);
        uint8_t buf[8];
        for (int i = 0; i < 8; i++) {
            buf[i] = (uint8_t)(val >> (56 - 8 * i));
        }
        success = (write(fd, buf, 8) == 8);
    }
    else {
        success = false;
    }

    close(fd);
    return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_luongtd14_videoanalysis_MediaBridge_patchPayload(
        JNIEnv* env,
        jclass /* clazz */,
        jstring filePathStr,
        jlong offset,
        jbyteArray payload) {
        
    const char* filePath = env->GetStringUTFChars(filePathStr, nullptr);
    std::string path(filePath);
    env->ReleaseStringUTFChars(filePathStr, filePath);

    int fd = open(path.c_str(), O_RDWR);
    if (fd < 0) {
        return JNI_FALSE;
    }

    jsize len = env->GetArrayLength(payload);
    jbyte* bytes = env->GetByteArrayElements(payload, nullptr);
    
    lseek(fd, offset, SEEK_SET);
    ssize_t written = write(fd, bytes, len);
    
    env->ReleaseByteArrayElements(payload, bytes, JNI_ABORT);
    close(fd);
    return (written == len) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_luongtd14_videoanalysis_MediaBridge_convertYUVFrame(
        JNIEnv* env,
        jclass /* clazz */,
        jstring filePathStr,
        jint frameIndex,
        jint width,
        jint height,
        jstring formatStr,
        jobject bitmap) {
        
    const char* filePath = env->GetStringUTFChars(filePathStr, nullptr);
    std::string path(filePath);
    env->ReleaseStringUTFChars(filePathStr, filePath);

    const char* format = env->GetStringUTFChars(formatStr, nullptr);
    std::string fmt(format);
    env->ReleaseStringUTFChars(formatStr, format);

    AndroidBitmapInfo info;
    void* pixels = nullptr;
    if (AndroidBitmap_getInfo(env, bitmap, &info) < 0) {
        return JNI_FALSE;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return JNI_FALSE;
    }
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) < 0) {
        return JNI_FALSE;
    }

    FILE* f = fopen(path.c_str(), "rb");
    if (!f) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return JNI_FALSE;
    }

    size_t frameSize = width * height * 3 / 2;
    fseek(f, (long)frameIndex * frameSize, SEEK_SET);

    std::vector<uint8_t> yuvBuffer(frameSize);
    size_t readBytes = fread(yuvBuffer.data(), 1, frameSize, f);
    fclose(f);

    if (readBytes < frameSize) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return JNI_FALSE;
    }

    uint8_t* yPlane = yuvBuffer.data();
    uint8_t* uPlane = nullptr;
    uint8_t* vPlane = nullptr;

    if (fmt == "I420") {
        uPlane = yPlane + (width * height);
        vPlane = uPlane + (width * height / 4);
    } else if (fmt == "NV12" || fmt == "NV21") {
        uPlane = yPlane + (width * height);
    }

    for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
            int Y = yPlane[y * width + x];
            int U, V;
            
            int uvX = x / 2;
            int uvY = y / 2;
            int uvWidth = width / 2;
            
            if (fmt == "I420") {
                U = uPlane[uvY * uvWidth + uvX];
                V = vPlane[uvY * uvWidth + uvX];
            } else if (fmt == "NV12") {
                U = uPlane[uvY * width + uvX * 2];
                V = uPlane[uvY * width + uvX * 2 + 1];
            } else {
                V = uPlane[uvY * width + uvX * 2];
                U = uPlane[uvY * width + uvX * 2 + 1];
            }
            
            int c = Y - 16;
            int d = U - 128;
            int e = V - 128;
            
            int r = (298 * c           + 409 * e + 128) >> 8;
            int g = (298 * c - 100 * d - 208 * e + 128) >> 8;
            int b = (298 * c + 516 * d           + 128) >> 8;
            
            r = r < 0 ? 0 : (r > 255 ? 255 : r);
            g = g < 0 ? 0 : (g > 255 ? 255 : g);
            b = b < 0 ? 0 : (b > 255 ? 255 : b);
            
            uint8_t* pixel = ((uint8_t*)pixels) + y * info.stride + x * 4;
            pixel[0] = r;
            pixel[1] = g;
            pixel[2] = b;
            pixel[3] = 255;
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return JNI_TRUE;
}