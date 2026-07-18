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

import com.luongtd14.videoanalysis.databinding.ActivityEncoderBinding;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class EncoderActivity extends AppCompatActivity {

    private ActivityEncoderBinding binding;
    private String yuvSourcePath = "";
    private String outputBitstreamPath = "";

    private int width = 1280;
    private int height = 720;
    private String inputYuvFormat = "I420";
    private String codecType = "AVC"; // "AVC" or "HEVC"
    private String outputBitstreamFormat = "Annex B";

    private volatile boolean isEncoding = false;
    private Thread encodeThread = null;

    // Pick source YUV file
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

    // Select destination output bitstream
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
        binding = ActivityEncoderBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setupSpinners();

        // Check if intent passed a source YUV path
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
            intent.putExtra(Intent.EXTRA_TITLE, "output." + (codecType.equals("HEVC") ? "265" : "264"));
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

        // Codec Type
        List<String> codecs = new ArrayList<>();
        codecs.add("AVC (H.264)");
        codecs.add("HEVC (H.265)");
        ArrayAdapter<String> codecAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, codecs);
        codecAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spCodecType.setAdapter(codecAdapter);

        binding.spCodecType.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                codecType = (position == 0) ? "AVC" : "HEVC";
                updateOutputFormatSpinner();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        updateOutputFormatSpinner();
    }

    private void updateOutputFormatSpinner() {
        List<String> outputFormats = new ArrayList<>();
        if (codecType.equals("AVC")) {
            outputFormats.add("Annex B (Start Codes)");
            outputFormats.add("AVCC (Length-Prefixed)");
        } else {
            outputFormats.add("Annex B (Start Codes)");
            outputFormats.add("HVCC (Length-Prefixed)");
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
        outputBitstreamFormat = binding.spOutputBitstreamFormat.getSelectedItemPosition() == 0 ? "Annex B" : "AVCC/HVCC";

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

            // Select MIME Type based on selected codec
            String mimeType = codecType.equals("HEVC") ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;

            // Configure MediaCodec encoder
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
                    int inputBufferId = encoder.dequeueInputBuffer(10000); // 10ms timeout
                    if (inputBufferId >= 0) {
                        ByteBuffer inputBuffer = encoder.getInputBuffer(inputBufferId);
                        if (inputBuffer != null) {
                            inputBuffer.clear();
                            int read = inStream.read(rawFrameBytes, 0, frameSize);
                            
                            if (read < frameSize) {
                                // End of stream
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
                                    binding.tvEncodeProgressLabel.setText("Đang mã hóa: Khung hình " + finalFrameIdx + " / " + totalFrames);
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
                            if (outputBitstreamFormat.equals("Annex B")) {
                                byte[] bytes = new byte[info.size];
                                outputBuffer.position(info.offset);
                                outputBuffer.get(bytes);
                                outStream.write(bytes);
                            } else {
                                // Convert Annex B output buffer to AVCC / HVCC length-prefixed format
                                writeAsLengthPrefixed(outputBuffer, info, outStream);
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(outputBufferId, false);
                }
            }

            // Successfully completed
            runOnUiThread(() -> {
                Toast.makeText(this, "Mã hóa hoàn tất thành công!", Toast.LENGTH_LONG).show();
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
                Toast.makeText(this, "Lỗi mã hóa: " + errMsg, Toast.LENGTH_LONG).show();
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
                dest[destUVStart + i * 2] = src[uStart + i];       // U
                dest[destUVStart + i * 2 + 1] = src[vStart + i];   // V
            }
        } else if (inputFormat.equals("NV21")) {
            int srcUVStart = ySize;
            int destUVStart = ySize;
            int uvPixels = ySize / 4;

            for (int i = 0; i < uvPixels; i++) {
                dest[destUVStart + i * 2] = src[srcUVStart + i * 2 + 1]; // U
                dest[destUVStart + i * 2 + 1] = src[srcUVStart + i * 2];   // V
            }
        }
    }

    private void writeAsLengthPrefixed(ByteBuffer buffer, MediaCodec.BufferInfo info, OutputStream out) throws IOException {
        int offset = info.offset;
        int limit = info.offset + info.size;

        int pos = offset;
        int nextStartCodePos = findStartCode(buffer, pos, limit);

        while (nextStartCodePos != -1) {
            int startCodeLen = (buffer.get(nextStartCodePos + 2) == 1) ? 3 : 4;
            int nalStart = nextStartCodePos + startCodeLen;

            int nextNextStartCodePos = findStartCode(buffer, nalStart, limit);
            int nalEnd = (nextNextStartCodePos != -1) ? nextNextStartCodePos : limit;

            int nalSize = nalEnd - nalStart;
            if (nalSize > 0) {
                // Write 4-byte size length prefix in Big-Endian format (Used by AVCC and HVCC)
                byte[] lenPrefix = new byte[4];
                lenPrefix[0] = (byte) ((nalSize >> 24) & 0xFF);
                lenPrefix[1] = (byte) ((nalSize >> 16) & 0xFF);
                lenPrefix[2] = (byte) ((nalSize >> 8) & 0xFF);
                lenPrefix[3] = (byte) (nalSize & 0xFF);
                out.write(lenPrefix);

                // Write NAL unit payload
                byte[] nalPayload = new byte[nalSize];
                buffer.position(nalStart);
                buffer.get(nalPayload);
                out.write(nalPayload);
            }

            pos = nalEnd;
            nextStartCodePos = nextNextStartCodePos;
        }
    }

    private int findStartCode(ByteBuffer buffer, int start, int limit) {
        for (int i = start; i < limit - 2; i++) {
            if (buffer.get(i) == 0 && buffer.get(i + 1) == 0) {
                if (buffer.get(i + 2) == 1) {
                    return i;
                }
                if (i < limit - 3 && buffer.get(i + 2) == 0 && buffer.get(i + 3) == 1) {
                    return i;
                }
            }
        }
        return -1;
    }
}
