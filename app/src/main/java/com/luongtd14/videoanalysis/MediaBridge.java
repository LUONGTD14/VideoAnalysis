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
}
