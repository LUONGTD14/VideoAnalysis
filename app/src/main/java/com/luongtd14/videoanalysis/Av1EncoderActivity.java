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
            intent.putExtra(Intent.EXTRA_TITLE, "output." + (codecType.equals("VP9") ? "vp9" : "obu"));
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

        // Codecs (AV1 & VP9 placeholder)
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
            outputFormats.add("Raw Frame Packets (IVF / WebM format)");
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
        if (codecType.equals("VP9")) {
            Toast.makeText(this, "VP9 sẽ được hỗ trợ tiếp theo! Vui lòng chọn AV1 để bắt đầu.", Toast.LENGTH_SHORT).show();
            return;
        }

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
        outputBitstreamFormat = binding.spOutputBitstreamFormat.getSelectedItemPosition() == 0 ? "OBU Stream" : "AV1 Annex B";

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

            String mimeType = MediaFormat.MIMETYPE_VIDEO_AV1; // "video/av01"

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
                // 1. Feed input buffer
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
                                    binding.tvEncodeProgressLabel.setText("Đang mã hóa AV1: Khung hình " + finalFrameIdx + " / " + totalFrames);
                                    binding.tvEncodeProgressPercent.setText(progress + "%");
                                    binding.pbEncode.setProgress(progress);
                                });
                            }
                        }
                    }
                }

                // 2. Fetch output buffer
                int outputBufferId = encoder.dequeueOutputBuffer(info, 10000);
                if (outputBufferId >= 0) {
                    ByteBuffer outputBuffer = encoder.getOutputBuffer(outputBufferId);
                    if (outputBuffer != null) {
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputEos = true;
                        }

                        if (info.size > 0) {
                            byte[] bytes = new byte[info.size];
                            outputBuffer.position(info.offset);
                            outputBuffer.get(bytes);

                            if (outputBitstreamFormat.equals("OBU Stream")) {
                                outStream.write(bytes);
                            } else {
                                // Write as AV1 Annex B (Pre-fixed with LEB128 size!)
                                writeLeb128(info.size, outStream);
                                outStream.write(bytes);
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(outputBufferId, false);
                }
            }

            runOnUiThread(() -> {
                Toast.makeText(this, "Mã hóa AV1 hoàn tất thành công!", Toast.LENGTH_LONG).show();
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
                Toast.makeText(this, "Lỗi mã hóa AV1: " + errMsg, Toast.LENGTH_LONG).show();
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

    // Encodes an integer as a LEB128 variable length integer (used by AV1 bitstream packaging)
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
