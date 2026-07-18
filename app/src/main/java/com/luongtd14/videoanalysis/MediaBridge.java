package com.luongtd14.videoanalysis;

public class MediaBridge {
    static {
        System.loadLibrary("videoanalysis");
    }

    // Returns JSON representation of the parsed box/element tree
    public static native String parseMediaFile(String filePath);

    // Patches a numeric field in-place
    public static native boolean patchField(String filePath, long offset, String type, double value);

    // Patches a raw payload in-place
    public static native boolean patchPayload(String filePath, long offset, byte[] payload);

    // Converts a YUV frame from a raw file and writes directly into the Bitmap's pixel buffer
    public static native boolean convertYUVFrame(String filePath, int frameIndex, int width, int height, String format, android.graphics.Bitmap bitmap);
}
