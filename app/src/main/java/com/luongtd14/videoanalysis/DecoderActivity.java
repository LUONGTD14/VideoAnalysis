package com.luongtd14.videoanalysis;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import com.luongtd14.videoanalysis.databinding.ActivityDecoderBinding;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class DecoderActivity extends AppCompatActivity {

    private ActivityDecoderBinding binding;
    private String sourceFilePath;
    private String sourceFileName;

    private int videoWidth = 0;
    private int videoHeight = 0;
    private long durationUs = 0;
    private String videoMime = "";
    private int videoFps = 30;

    private Uri destinationUri = null;
    private volatile boolean isDecoding = false;
    private volatile boolean cancelRequested = false;
    private Thread decodeThread = null;

    private final ActivityResultLauncher<Intent> saveFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    destinationUri = result.getData().getData();
                    if (destinationUri != null) {
                        String destPath = PathUtils.getPathFromUri(this, destinationUri);
                        if (destPath != null) {
                            binding.tvDestinationPath.setText("Nơi lưu: " + destPath);
                        } else {
                            binding.tvDestinationPath.setText("Nơi lưu: " + destinationUri.toString());
                        }
                        binding.btnToggleDecode.setEnabled(true);
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityDecoderBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        sourceFilePath = getIntent().getStringExtra("file_path");
        sourceFileName = getIntent().getStringExtra("file_name");

        if (sourceFilePath == null || sourceFilePath.isEmpty()) {
            Toast.makeText(this, "Đường dẫn tệp tin gốc trống!", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        extractVideoMetadata();
        setupColorFormatSpinner();

        binding.btnSelectDestination.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_TITLE, "decoded_" + sourceFileName + ".yuv");
            saveFileLauncher.launch(intent);
        });

        binding.btnToggleDecode.setOnClickListener(v -> {
            if (!isDecoding) {
                if (destinationUri != null) {
                    startDecodingProcess(destinationUri);
                }
            } else {
                cancelRequested = true;
                binding.btnToggleDecode.setText("Đang dừng...");
                binding.btnToggleDecode.setEnabled(false);
            }
        });
    }

    private void setupColorFormatSpinner() {
        List<String> formats = new ArrayList<>();
        formats.add("I420 (YUV 420 Planar)");
        formats.add("NV12 (YUV 420 Semi-Planar)");
        formats.add("NV21 (YUV 420 Semi-Planar V/U)");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, formats);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spColorFormat.setAdapter(adapter);
    }

    private void extractVideoMetadata() {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(sourceFilePath);
            int trackIndex = findVideoTrack(extractor);
            if (trackIndex < 0) {
                Toast.makeText(this, "Không tìm thấy track video trong tệp tin!", Toast.LENGTH_LONG).show();
                finish();
                return;
            }

            MediaFormat format = extractor.getTrackFormat(trackIndex);
            videoWidth = format.getInteger(MediaFormat.KEY_WIDTH);
            videoHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
            durationUs = format.getLong(MediaFormat.KEY_DURATION);
            videoMime = format.getString(MediaFormat.KEY_MIME);
            
            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                videoFps = format.getInteger(MediaFormat.KEY_FRAME_RATE);
            }

            binding.tvVideoPath.setText("Đường dẫn: " + sourceFilePath);
            binding.tvVideoResolution.setText("Độ phân giải: " + videoWidth + " x " + videoHeight);
            binding.tvVideoCodec.setText("Codec: " + videoMime);
            binding.tvVideoFps.setText("FPS: " + videoFps);
            binding.tvVideoDuration.setText(String.format("Thời lượng: %.2f giây", durationUs / 1000000.0));

        } catch (IOException e) {
            Toast.makeText(this, "Lỗi đọc tệp tin: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        } finally {
            extractor.release();
        }
    }

    private int findVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) {
                return i;
            }
        }
        return -1;
    }

    private void startDecodingProcess(Uri destUri) {
        if (isDecoding) return;

        isDecoding = true;
        cancelRequested = false;

        // Update UI states for decoding
        binding.spColorFormat.setEnabled(false);
        binding.btnSelectDestination.setEnabled(false);
        
        binding.btnToggleDecode.setText("🛑 Dừng Giải Mã");
        binding.btnToggleDecode.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#D20F39"))); // Red
        binding.btnToggleDecode.setEnabled(true);

        binding.cvProgress.setVisibility(View.VISIBLE);
        binding.pbDecode.setProgress(0);
        binding.tvProgressPercent.setText("0%");
        binding.tvProgressLabel.setText("Đang khởi tạo bộ giải mã...");

        String selectedFormat = "I420";
        int position = binding.spColorFormat.getSelectedItemPosition();
        if (position == 1) selectedFormat = "NV12";
        else if (position == 2) selectedFormat = "NV21";

        final String finalFormat = selectedFormat;
        decodeThread = new Thread(() -> performDecode(destUri, finalFormat));
        decodeThread.start();
    }

    private void performDecode(Uri destUri, String formatName) {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        OutputStream out = null;

        try {
            extractor.setDataSource(sourceFilePath);
            int trackIndex = findVideoTrack(extractor);
            extractor.selectTrack(trackIndex);
            MediaFormat format = extractor.getTrackFormat(trackIndex);

            decoder = MediaCodec.createDecoderByType(videoMime);
            decoder.configure(format, null, null, 0); // null surface to output directly to buffers
            decoder.start();

            out = getContentResolver().openOutputStream(destUri);
            if (out == null) throw new IOException("Không thể mở luồng ghi tệp đích.");

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean isInputEOS = false;
            boolean isOutputEOS = false;
            int frameCount = 0;
            
            // Approximate total frames based on duration and FPS
            int totalFrames = (int) ((durationUs / 1000000.0) * videoFps);
            if (totalFrames <= 0) totalFrames = 100;

            while (!isOutputEOS && !cancelRequested) {
                if (!isInputEOS) {
                    int inputBufferId = decoder.dequeueInputBuffer(10000);
                    if (inputBufferId >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferId);
                        int sampleSize = extractor.readSampleData(inputBuffer, 0);
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            isInputEOS = true;
                        } else {
                            long presentationTimeUs = extractor.getSampleTime();
                            decoder.queueInputBuffer(inputBufferId, 0, sampleSize, presentationTimeUs, 0);
                            extractor.advance();
                        }
                    }
                }

                int outputBufferId = decoder.dequeueOutputBuffer(info, 10000);
                if (outputBufferId >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true;
                    }

                    if (info.size > 0) {
                        Image image = decoder.getOutputImage(outputBufferId);
                        if (image != null) {
                            writeYuvFrame(image, out, formatName);
                            image.close();
                            frameCount++;

                            final int currentFrame = frameCount;
                            final int total = totalFrames;
                            runOnUiThread(() -> {
                                int percent = Math.min(100, (int) ((currentFrame / (double) total) * 100));
                                binding.pbDecode.setProgress(percent);
                                binding.tvProgressPercent.setText(percent + "%");
                                binding.tvProgressLabel.setText("Đang ghi: Khung hình thứ " + currentFrame);
                            });
                        }
                    }
                    decoder.releaseOutputBuffer(outputBufferId, false);
                } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = decoder.getOutputFormat();
                    videoWidth = newFormat.getInteger(MediaFormat.KEY_WIDTH);
                    videoHeight = newFormat.getInteger(MediaFormat.KEY_HEIGHT);
                }
            }

            final int finalFrameCount = frameCount;
            if (cancelRequested) {
                runOnUiThread(() -> Toast.makeText(DecoderActivity.this, "Đã hủy giải mã bởi người dùng.", Toast.LENGTH_LONG).show());
            } else {
                runOnUiThread(() -> Toast.makeText(DecoderActivity.this, "Giải mã hoàn tất! Khung hình đã xuất: " + finalFrameCount, Toast.LENGTH_LONG).show());
            }

        } catch (Exception e) {
            final String errorMsg = e.getMessage();
            runOnUiThread(() -> Toast.makeText(DecoderActivity.this, "Lỗi giải mã: " + errorMsg, Toast.LENGTH_LONG).show());
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Exception ignored) {}
            }
            extractor.release();
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {}
            }

            runOnUiThread(() -> {
                isDecoding = false;
                binding.spColorFormat.setEnabled(true);
                binding.btnSelectDestination.setEnabled(true);
                
                binding.btnToggleDecode.setEnabled(true);
                binding.btnToggleDecode.setText("🎬 Bắt Đầu Giải Mã");
                binding.btnToggleDecode.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#1E66F5"))); // Restore blue
                
                binding.cvProgress.setVisibility(View.GONE);
            });
        }
    }

    private void writeYuvFrame(Image image, OutputStream out, String format) throws IOException {
        Rect crop = image.getCropRect();
        int width = crop.width();
        int height = crop.height();
        Image.Plane[] planes = image.getPlanes();

        byte[] rowData = new byte[planes[0].getRowStride()];

        if (format.equals("I420")) {
            writePlane(planes[0], width, height, out, rowData);
            writePlane(planes[1], width / 2, height / 2, out, rowData);
            writePlane(planes[2], width / 2, height / 2, out, rowData);
        } else if (format.equals("NV12")) {
            writePlane(planes[0], width, height, out, rowData);
            writeInterleavedPlanes(planes[1], planes[2], width / 2, height / 2, out);
        } else if (format.equals("NV21")) {
            writePlane(planes[0], width, height, out, rowData);
            writeInterleavedPlanes(planes[2], planes[1], width / 2, height / 2, out);
        }
    }

    private void writePlane(Image.Plane plane, int width, int height, OutputStream out, byte[] rowData) throws IOException {
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int startPos = buffer.position();

        if (pixelStride == 1) {
            for (int y = 0; y < height; y++) {
                buffer.position(startPos + y * rowStride);
                buffer.get(rowData, 0, width);
                out.write(rowData, 0, width);
            }
        } else {
            byte[] line = new byte[width];
            for (int y = 0; y < height; y++) {
                int linePos = startPos + y * rowStride;
                for (int x = 0; x < width; x++) {
                    line[x] = buffer.get(linePos + x * pixelStride);
                }
                out.write(line);
            }
        }
    }

    private void writeInterleavedPlanes(Image.Plane plane1, Image.Plane plane2, int width, int height, OutputStream out) throws IOException {
        ByteBuffer buffer1 = plane1.getBuffer();
        ByteBuffer buffer2 = plane2.getBuffer();
        int rowStride1 = plane1.getRowStride();
        int rowStride2 = plane2.getRowStride();
        int pixelStride1 = plane1.getPixelStride();
        int pixelStride2 = plane2.getPixelStride();

        int startPos1 = buffer1.position();
        int startPos2 = buffer2.position();

        byte[] interleavedRow = new byte[width * 2];
        for (int y = 0; y < height; y++) {
            int linePos1 = startPos1 + y * rowStride1;
            int linePos2 = startPos2 + y * rowStride2;
            for (int x = 0; x < width; x++) {
                interleavedRow[x * 2] = buffer1.get(linePos1 + x * pixelStride1);
                interleavedRow[x * 2 + 1] = buffer2.get(linePos2 + x * pixelStride2);
            }
            out.write(interleavedRow);
        }
    }
}
