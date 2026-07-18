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

import java.io.File;
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
    private boolean isRawBitstream = false;
    private String inputBitstreamFormat = "Annex B";
    private volatile boolean isDecoding = false;
    private volatile boolean cancelRequested = false;
    private Thread decodeThread = null;

    private final ActivityResultLauncher<Intent> sourceFileLauncher = registerForActivityResult(
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
                            sourceFilePath = path;
                            sourceFileName = new File(path).getName();
                            isRawBitstream = false; // Reset first
                            extractVideoMetadata();
                            checkCanStart();
                        } else {
                            Toast.makeText(this, "Không thể lấy đường dẫn tệp tin gốc!", Toast.LENGTH_LONG).show();
                        }
                    }
                }
            }
    );

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
                        checkCanStart();
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

        if (sourceFilePath == null) sourceFilePath = "";
        if (sourceFileName == null) sourceFileName = "";

        if (!sourceFilePath.isEmpty()) {
            extractVideoMetadata();
        } else {
            binding.tvVideoPath.setText("Đường dẫn: Chưa chọn");
            binding.tvVideoResolution.setText("Độ phân giải: Chưa chọn");
            binding.tvVideoCodec.setText("Codec: Chưa chọn");
            binding.tvVideoFps.setText("FPS: Chưa chọn");
            binding.tvVideoDuration.setText("Thời lượng: Chưa chọn");
        }

        setupColorFormatSpinner();
        setupInputBitstreamSpinner();

        binding.btnSelectSource.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            String[] mimeTypes = {
                "video/mp4", "video/quicktime", "video/x-matroska", "video/webm",
                "video/h264", "video/hevc", "video/x-h264", "application/octet-stream"
            };
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            sourceFileLauncher.launch(intent);
        });

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

        checkCanStart();
    }

    private void checkCanStart() {
        boolean hasSource = sourceFilePath != null && !sourceFilePath.isEmpty();
        binding.btnSelectDestination.setEnabled(hasSource);
        binding.btnToggleDecode.setEnabled(hasSource && destinationUri != null);
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

    private void setupInputBitstreamSpinner() {
        List<String> formats = new ArrayList<>();
        formats.add("Annex B (Start Codes 0x00000001)");
        formats.add("AVCC / HVCC (Length Prefixed)");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, formats);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spInputBitstreamFormat.setAdapter(adapter);
    }

    private void extractVideoMetadata() {
        if (sourceFilePath.endsWith(".264") || sourceFilePath.endsWith(".h264") ||
                sourceFilePath.endsWith(".265") || sourceFilePath.endsWith(".h265")) {
            isRawBitstream = true;
            videoWidth = 0;
            videoHeight = 0;
            videoMime = (sourceFilePath.endsWith(".265") || sourceFilePath.endsWith(".h265"))
                    ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
            videoFps = 30;
            durationUs = 0;

            binding.tvVideoPath.setText("Đường dẫn: " + sourceFilePath);
            binding.tvVideoResolution.setText("Độ phân giải: Tự động phát hiện (Raw Stream)");
            binding.tvVideoCodec.setText("Codec: " + videoMime);
            binding.tvVideoFps.setText("FPS: 30 (Mặc định)");
            binding.tvVideoDuration.setText("Thời lượng: N/A (Raw Bitstream)");
            binding.cvInputBitstreamFormat.setVisibility(View.VISIBLE);
            return;
        }

        binding.cvInputBitstreamFormat.setVisibility(View.GONE);
        isRawBitstream = false;
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
        binding.spInputBitstreamFormat.setEnabled(false);
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

        if (isRawBitstream) {
            inputBitstreamFormat = (binding.spInputBitstreamFormat.getSelectedItemPosition() == 0) ? "Annex B" : "AVCC";
        }

        final String finalFormat = selectedFormat;
        decodeThread = new Thread(() -> performDecode(destUri, finalFormat));
        decodeThread.start();
    }

    private void performDecode(Uri destUri, String formatName) {
        java.io.FileInputStream fis = null;
        MediaExtractor extractor = null;
        MediaCodec decoder = null;
        OutputStream out = null;

        try {
            MediaFormat format;
            if (isRawBitstream) {
                fis = new java.io.FileInputStream(sourceFilePath);
                format = MediaFormat.createVideoFormat(videoMime, 1280, 720); // Placeholder
            } else {
                extractor = new MediaExtractor();
                extractor.setDataSource(sourceFilePath);
                int trackIndex = findVideoTrack(extractor);
                extractor.selectTrack(trackIndex);
                format = extractor.getTrackFormat(trackIndex);
            }

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
            int totalFrames;
            if (isRawBitstream) {
                long fileSize = new File(sourceFilePath).length();
                totalFrames = (int) (fileSize / 20480); // Estimate average frame size as 20KB for AVC
                if (totalFrames <= 0) totalFrames = 100;
            } else {
                totalFrames = (int) ((durationUs / 1000000.0) * videoFps);
                if (totalFrames <= 0) totalFrames = 100;
            }

            byte[] chunk = new byte[65536]; // 64KB chunks
            int frameIndex = 0;

            while (!isOutputEOS && !cancelRequested) {
                if (!isInputEOS) {
                    int inputBufferId = decoder.dequeueInputBuffer(10000);
                    if (inputBufferId >= 0) {
                        ByteBuffer inputBuffer = decoder.getInputBuffer(inputBufferId);
                        if (inputBuffer != null) {
                            if (isRawBitstream) {
                                if (inputBitstreamFormat.equals("AVCC")) {
                                    byte[] sizeBytes = new byte[4];
                                    int readSize = fis.read(sizeBytes);
                                    if (readSize < 4) {
                                        decoder.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                        isInputEOS = true;
                                    } else {
                                        int nalLen = ((sizeBytes[0] & 0xFF) << 24) | ((sizeBytes[1] & 0xFF) << 16) | ((sizeBytes[2] & 0xFF) << 8) | (sizeBytes[3] & 0xFF);
                                        if (nalLen <= 0 || nalLen > 10 * 1024 * 1024) {
                                            throw new IOException("Độ dài NAL unit không hợp lệ: " + nalLen);
                                        }
                                        byte[] nalData = new byte[nalLen];
                                        int readPayload = 0;
                                        while (readPayload < nalLen) {
                                            int r = fis.read(nalData, readPayload, nalLen - readPayload);
                                            if (r < 0) {
                                                break;
                                            }
                                            readPayload += r;
                                        }
                                        if (readPayload < nalLen) {
                                            decoder.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                            isInputEOS = true;
                                        } else {
                                            inputBuffer.clear();
                                            if (nalLen + 4 > inputBuffer.remaining()) {
                                                throw new IOException("Độ dài NAL unit vượt quá dung lượng buffer đầu vào: " + nalLen);
                                            }
                                            inputBuffer.put(new byte[]{0, 0, 0, 1});
                                            inputBuffer.put(nalData, 0, nalLen);
                                            long presentationTimeUs = (long) frameIndex * 1000000 / videoFps;
                                            decoder.queueInputBuffer(inputBufferId, 0, nalLen + 4, presentationTimeUs, 0);
                                            frameIndex++;
                                        }
                                    }
                                } else {
                                    int read = fis.read(chunk);
                                    if (read < 0) {
                                        decoder.queueInputBuffer(inputBufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                        isInputEOS = true;
                                    } else {
                                        inputBuffer.clear();
                                        inputBuffer.put(chunk, 0, read);
                                        long presentationTimeUs = (long) frameIndex * 1000000 / videoFps;
                                        decoder.queueInputBuffer(inputBufferId, 0, read, presentationTimeUs, 0);
                                        frameIndex++;
                                    }
                                }
                            } else {
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
                    }
                }

                int outputBufferId = decoder.dequeueOutputBuffer(info, 10000);
                if (outputBufferId >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isOutputEOS = true;
                    }

                    if (info.size > 0) {
                        android.media.Image image = decoder.getOutputImage(outputBufferId);
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
                    
                    runOnUiThread(() -> {
                        binding.tvVideoResolution.setText("Độ phân giải: " + videoWidth + " x " + videoHeight);
                    });
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
            if (extractor != null) {
                try {
                    extractor.release();
                } catch (Exception ignored) {}
            }
            if (fis != null) {
                try {
                    fis.close();
                } catch (Exception ignored) {}
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {}
            }

            runOnUiThread(() -> {
                isDecoding = false;
                binding.spColorFormat.setEnabled(true);
                binding.spInputBitstreamFormat.setEnabled(true);
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
