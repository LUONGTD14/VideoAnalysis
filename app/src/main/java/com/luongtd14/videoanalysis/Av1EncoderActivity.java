package com.luongtd14.videoanalysis;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import com.luongtd14.videoanalysis.databinding.ActivityAv1EncoderBinding;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class Av1EncoderActivity extends AppCompatActivity {

    private ActivityAv1EncoderBinding binding;
    private String yuvSourcePath = "";
    private String outputBitstreamPath = "";

    private int width = 1280;
    private int height = 720;
    private String inputYuvFormat = "I420";
    private String codecType = "AV1"; // "AV1" or "VP9"
    private String outputBitstreamFormat = "OBU Stream";

    private volatile boolean isEncoding = false;
    private Thread encodeThread = null;

    private final ActivityResultLauncher<Intent> sourcePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception ignored) {}

                        String path = PathUtils.getPathFromUri(this, uri);
                        if (path != null) {
                            yuvSourcePath = path;
                            binding.tvYuvSourceName.setText("Tên tệp: " + new File(path).getName());
                            checkCanStart();
                        } else {
                            Toast.makeText(this, "Không thể lấy đường dẫn tệp tin tuyệt đối!", Toast.LENGTH_LONG).show();
                        }
                    }
                }
            }
    );

    private final ActivityResultLauncher<Intent> destinationPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(uri,
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        } catch (Exception ignored) {}

                        String path = PathUtils.getPathFromUri(this, uri);
                        if (path != null) {
                            outputBitstreamPath = path;
                            binding.tvEncodeDestinationPath.setText("Nơi lưu: " + path);
                            checkCanStart();
                        } else {
                            Toast.makeText(this, "Không thể lấy đường dẫn lưu tệp tin!", Toast.LENGTH_LONG).show();
                        }
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAv1EncoderBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupSpinners();

        String passedPath = getIntent().getStringExtra("file_path");
        if (passedPath != null && passedPath.endsWith(".yuv")) {
            yuvSourcePath = passedPath;
            binding.tvYuvSourceName.setText("Tên tệp: " + new File(passedPath).getName());
        }

        binding.tvYuvSourceName.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            sourcePickerLauncher.launch(intent);
        });

        binding.btnSelectEncodeDestination.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("video/*");
            intent.putExtra(Intent.EXTRA_TITLE, "output." + (codecType.equals("VP9") ? "ivf" : "obu"));
            destinationPickerLauncher.launch(intent);
        });

        binding.btnToggleEncode.setOnClickListener(v -> {
            if (isEncoding) {
                stopEncoding();
            } else {
                startEncoding();
            }
        });

        checkCanStart();
    }

    private void setupSpinners() {
        // Input YUV Color Format
        List<String> inputFormats = new ArrayList<>();
        inputFormats.add("I420");
        inputFormats.add("NV12");
        inputFormats.add("NV21");
        ArrayAdapter<String> inputAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, inputFormats);
        inputAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spInputYuvFormat.setAdapter(inputAdapter);

        // Codecs
        List<String> codecs = new ArrayList<>();
        codecs.add("AV1 (AOMedia Video 1)");
        codecs.add("VP9 (Google Video 9)");
        ArrayAdapter<String> codecAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, codecs);
        codecAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spCodecType.setAdapter(codecAdapter);

        binding.spCodecType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                codecType = (position == 0) ? "AV1" : "VP9";
                updateOutputFormatSpinner();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        updateOutputFormatSpinner();
    }

    private void updateOutputFormatSpinner() {
        List<String> outputFormats = new ArrayList<>();
        if (codecType.equals("AV1")) {
            outputFormats.add("Low Overhead OBU Stream");
            outputFormats.add("AV1 Annex B (LEB128 Size Prefixed)");
        } else {
            outputFormats.add("IVF Container (DKIF Header)");
            outputFormats.add("Raw Frame Packets");
        }
        ArrayAdapter<String> outputAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, outputFormats);
        outputAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spOutputBitstreamFormat.setAdapter(outputAdapter);
    }

    private void checkCanStart() {
        boolean canStart = !yuvSourcePath.isEmpty() && !outputBitstreamPath.isEmpty();
        binding.btnToggleEncode.setEnabled(canStart);
    }

    private void startEncoding() {
        try {
            width = Integer.parseInt(binding.etEncodeWidth.getText().toString().trim());
            height = Integer.parseInt(binding.etEncodeHeight.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Độ phân giải không hợp lệ!", Toast.LENGTH_SHORT).show();
            return;
        }

        int bitrateKbps;
        int fps;
        int gopSeconds;
        try {
            bitrateKbps = Integer.parseInt(binding.etBitrateKbps.getText().toString().trim());
            fps = Integer.parseInt(binding.etFps.getText().toString().trim());
            gopSeconds = Integer.parseInt(binding.etGopSeconds.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Thông số mã hóa không hợp lệ!", Toast.LENGTH_SHORT).show();
            return;
        }

        inputYuvFormat = binding.spInputYuvFormat.getSelectedItem().toString();
        
        if (codecType.equals("AV1")) {
            outputBitstreamFormat = binding.spOutputBitstreamFormat.getSelectedItemPosition() == 0 ? "OBU Stream" : "AV1 Annex B";
        } else {
            outputBitstreamFormat = binding.spOutputBitstreamFormat.getSelectedItemPosition() == 0 ? "IVF Container" : "Raw Packets";
        }

        isEncoding = true;
        binding.btnToggleEncode.setText("⏹ Hủy Mã Hóa");
        binding.btnSelectEncodeDestination.setEnabled(false);
        binding.tvYuvSourceName.setEnabled(false);
        binding.spCodecType.setEnabled(false);
        binding.cvEncodeProgress.setVisibility(View.VISIBLE);
        binding.pbEncode.setProgress(0);

        final int finalBitrateBps = bitrateKbps * 1000;
        final int finalFps = fps;
        final int finalGop = gopSeconds;

        encodeThread = new Thread(() -> runEncoderLoop(finalBitrateBps, finalFps, finalGop));
        encodeThread.start();
    }

    private void stopEncoding() {
        isEncoding = false;
        if (encodeThread != null) {
            try {
                encodeThread.join(2000);
            } catch (InterruptedException ignored) {}
        }
        binding.btnToggleEncode.setText("🎬 Bắt Đầu Mã Hóa");
        binding.btnSelectEncodeDestination.setEnabled(true);
        binding.tvYuvSourceName.setEnabled(true);
        binding.spCodecType.setEnabled(true);
        binding.cvEncodeProgress.setVisibility(View.GONE);
        Toast.makeText(this, "Đã dừng mã hóa!", Toast.LENGTH_SHORT).show();
    }

    private void runEncoderLoop(int bitrateBps, int fps, int gopSeconds) {
        MediaCodec encoder = null;
        BufferedInputStream inStream = null;
        OutputStream outStream = null;

        try {
            File sourceFile = new File(yuvSourcePath);
            long totalLength = sourceFile.length();
            int frameSize = width * height * 3 / 2;
            int totalFrames = (int) (totalLength / frameSize);

            if (totalFrames <= 0) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Kích thước tệp nguồn quá nhỏ!", Toast.LENGTH_LONG).show();
                    stopEncoding();
                });
                return;
            }

            inStream = new BufferedInputStream(new FileInputStream(sourceFile));
            outStream = new FileOutputStream(outputBitstreamPath);

            String mimeType = codecType.equals("VP9") ? MediaFormat.MIMETYPE_VIDEO_VP9 : MediaFormat.MIMETYPE_VIDEO_AV1;

            // 1. Write IVF Header if encoding VP9 to IVF Container
            if (codecType.equals("VP9") && outputBitstreamFormat.equals("IVF Container")) {
                byte[] fileHeader = new byte[32];
                fileHeader[0] = 'D'; fileHeader[1] = 'K'; fileHeader[2] = 'I'; fileHeader[3] = 'F';
                fileHeader[4] = 0; fileHeader[5] = 0; // version 0
                fileHeader[6] = 32; fileHeader[7] = 0; // length of header (32)
                fileHeader[8] = 'V'; fileHeader[9] = 'P'; fileHeader[10] = '9'; fileHeader[11] = '0'; // fourcc 'VP90'

                // Width (16-bit little endian)
                fileHeader[12] = (byte) (width & 0xFF);
                fileHeader[13] = (byte) ((width >> 8) & 0xFF);
                // Height (16-bit little endian)
                fileHeader[14] = (byte) (height & 0xFF);
                fileHeader[15] = (byte) ((height >> 8) & 0xFF);

                // Time rate (fps) (32-bit little endian)
                fileHeader[16] = (byte) (fps & 0xFF);
                fileHeader[17] = (byte) ((fps >> 8) & 0xFF);
                fileHeader[18] = (byte) ((fps >> 16) & 0xFF);
                fileHeader[19] = (byte) ((fps >> 24) & 0xFF);

                // Time scale (1) (32-bit little endian)
                fileHeader[20] = 1; fileHeader[21] = 0; fileHeader[22] = 0; fileHeader[23] = 0;

                // Total frames (write 0 for now, will patch at end of encoding)
                fileHeader[24] = 0; fileHeader[25] = 0; fileHeader[26] = 0; fileHeader[27] = 0;

                outStream.write(fileHeader);
            }

            // 2. Configure and start MediaCodec encoder
            MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar); // NV12
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, gopSeconds);

            encoder = MediaCodec.createEncoderByType(mimeType);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();

            byte[] rawFrameBytes = new byte[frameSize];
            byte[] nv12Bytes = new byte[frameSize];

            int frameIndex = 0;
            boolean inputEos = false;
            boolean outputEos = false;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (isEncoding && (!inputEos || !outputEos)) {
                // Feed input buffer
                if (!inputEos) {
                    int inputBufferId = encoder.dequeueInputBuffer(10000);
                    if (inputBufferId >= 0) {
                        ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferId);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            int read = inStream.read(rawFrameBytes, 0, frameSize);
                            
                            if (read < frameSize) {
                                encoder.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                inputEos = true;
                            } else {
                                convertToNV12(rawFrameBytes, nv12Bytes, width, height, inputYuvFormat);
                                inputBuffer.put(nv12Bytes);
                                
                                long ptsUs = (long) frameIndex * 1000000 / fps;
                                encoder.queueInputBuffer(inputBufferId, 0, frameSize, ptsUs, 0);
                                
                                frameIndex++;
                                final int finalFrameIdx = frameIndex;
                                final int progress = (int) ((finalFrameIdx * 100) / totalFrames);
                                runOnUiThread(() -> {
                                    binding.tvEncodeProgressLabel.setText("Đang mã hóa " + codecType + ": Khung hình " + finalFrameIdx + " / " + totalFrames);
                                    binding.tvEncodeProgressPercent.setText(progress + "%");
                                    binding.pbEncode.setProgress(progress);
                                });
                            }
                        }
                    }
                }

                // Fetch output buffer
                int outputBufferId = encoder.dequeueOutputBuffer(info, 10000);
                if (outputBufferId >= 0) {
                    ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferId);
                    if (outputBuffer != null) {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEos = true;
                        }

                        if (info.size > 0) {
                            if (codecType.equals("AV1")) {
                                byte[] bytes = new byte[info.size];
                                outputBuffer.position(info.offset);
                                outputBuffer.get(bytes);

                                if (outputBitstreamFormat.equals("OBU Stream")) {
                                    outStream.write(bytes);
                                } else {
                                    // AV1 Annex B (LEB128 Size Prefixed)
                                    writeLeb128(info.size, outStream);
                                    outStream.write(bytes);
                                }
                            } else {
                                // VP9 Bitstream Packing
                                if (outputBitstreamFormat.equals("Raw Packets")) {
                                    byte[] bytes = new byte[info.size];
                                    outputBuffer.position(info.offset);
                                    outputBuffer.get(bytes);
                                    outStream.write(bytes);
                                } else {
                                    // IVF Container (12-byte Frame Header + Frame payload)
                                    byte[] frameHeader = new byte[12];
                                    int payloadSize = info.size;

                                    // Size (32-bit little endian)
                                    frameHeader[0] = (byte) (payloadSize & 0xFF);
                                    frameHeader[1] = (byte) ((payloadSize >> 8) & 0xFF);
                                    frameHeader[2] = (byte) ((payloadSize >> 16) & 0xFF);
                                    frameHeader[3] = (byte) ((payloadSize >> 24) & 0xFF);

                                    // Timestamp (64-bit little-endian presentationTimeUs)
                                    long pts = info.presentationTimeUs;
                                    frameHeader[4] = (byte) (pts & 0xFF);
                                    frameHeader[5] = (byte) ((pts >> 8) & 0xFF);
                                    frameHeader[6] = (byte) ((pts >> 16) & 0xFF);
                                    frameHeader[7] = (byte) ((pts >> 24) & 0xFF);
                                    frameHeader[8] = (byte) ((pts >> 32) & 0xFF);
                                    frameHeader[9] = (byte) ((pts >> 40) & 0xFF);
                                    frameHeader[10] = (byte) ((pts >> 48) & 0xFF);
                                    frameHeader[11] = (byte) ((pts >> 56) & 0xFF);

                                    outStream.write(frameHeader);

                                    byte[] bytes = new byte[payloadSize];
                                    outputBuffer.position(info.offset);
                                    outputBuffer.get(bytes);
                                    outStream.write(bytes);
                                }
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(outputBufferId, false);
                }
            }

            // Close files before patching
            inStream.close();
            inStream = null;
            outStream.flush();
            outStream.close();
            outStream = null;

            // 3. Patch IVF total frames for VP9 IVF Container
            if (codecType.equals("VP9") && outputBitstreamFormat.equals("IVF Container")) {
                try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(outputBitstreamPath, "rw")) {
                    raf.seek(24);
                    byte[] totalFramesBytes = new byte[4];
                    totalFramesBytes[0] = (byte) (frameIndex & 0xFF);
                    totalFramesBytes[1] = (byte) ((frameIndex >> 8) & 0xFF);
                    totalFramesBytes[2] = (byte) ((frameIndex >> 16) & 0xFF);
                    totalFramesBytes[3] = (byte) ((frameIndex >> 24) & 0xFF);
                    raf.write(totalFramesBytes);
                } catch (Exception ignored) {}
            }

            final int finalEncodedFrames = frameIndex;
            runOnUiThread(() -> {
                Toast.makeText(this, "Mã hóa " + codecType + " hoàn tất thành công! Tổng số khung: " + finalEncodedFrames, Toast.LENGTH_LONG).show();
                isEncoding = false;
                binding.btnToggleEncode.setText("🎬 Bắt Đầu Mã Hóa");
                binding.btnSelectEncodeDestination.setEnabled(true);
                binding.tvYuvSourceName.setEnabled(true);
                binding.spCodecType.setEnabled(true);
                binding.cvEncodeProgress.setVisibility(View.GONE);
            });

        } catch (Exception e) {
            final String errMsg = e.getMessage();
            runOnUiThread(() -> {
                Toast.makeText(this, "Lỗi mã hóa " + codecType + ": " + errMsg, Toast.LENGTH_LONG).show();
                isEncoding = false;
                binding.btnToggleEncode.setText("🎬 Bắt Đầu Mã Hóa");
                binding.btnSelectEncodeDestination.setEnabled(true);
                binding.tvYuvSourceName.setEnabled(true);
                binding.spCodecType.setEnabled(true);
                binding.cvEncodeProgress.setVisibility(View.GONE);
            });
        } finally {
            if (encoder != null) {
                try {
                    encoder.stop();
                    encoder.release();
                } catch (Exception ignored) {}
            }
            if (inStream != null) {
                try {
                    inStream.close();
                } catch (Exception ignored) {}
            }
            if (outStream != null) {
                try {
                    outStream.flush();
                    outStream.close();
                } catch (Exception ignored) {}
            }
        }
    }

    private void convertToNV12(byte[] src, byte[] dest, int width, int height, String inputFormat) {
        int ySize = width * height;
        int uvSize = ySize / 2;

        System.arraycopy(src, 0, dest, 0, ySize);

        if (inputFormat.equals("NV12")) {
            System.arraycopy(src, ySize, dest, ySize, uvSize);
        } else if (inputFormat.equals("I420")) {
            int uStart = ySize;
            int vStart = ySize + ySize / 4;
            int destUVStart = ySize;

            int uvPixels = ySize / 4;
            for (int i = 0; i < uvPixels; i++) {
                dest[destUVStart + i * 2] = src[uStart + i];
                dest[destUVStart + i * 2 + 1] = src[vStart + i];
            }
        } else if (inputFormat.equals("NV21")) {
            int srcUVStart = ySize;
            int destUVStart = ySize;
            int uvPixels = ySize / 4;

            for (int i = 0; i < uvPixels; i++) {
                dest[destUVStart + i * 2] = src[srcUVStart + i * 2 + 1];
                dest[destUVStart + i * 2 + 1] = src[srcUVStart + i * 2];
            }
        }
    }

    private void writeLeb128(int value, OutputStream out) throws IOException {
        while (true) {
            int byteVal = value & 0x7F;
            value >>>= 7;
            if (value != 0) {
                byteVal |= 0x80;
                out.write(byteVal);
            } else {
                out.write(byteVal);
                break;
            }
        }
    }
}
