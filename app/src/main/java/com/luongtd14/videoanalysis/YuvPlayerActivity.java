package com.luongtd14.videoanalysis;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Toast;

import com.luongtd14.videoanalysis.databinding.ActivityYuvPlayerBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class YuvPlayerActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private ActivityYuvPlayerBinding binding;
    private String yuvFilePath = "";
    private int yuvWidth = 1280;
    private int yuvHeight = 720;
    private String yuvFormat = "I420";

    private Bitmap frameBitmap = null;
    private int currentFrameIndex = 0;
    private int totalFrames = 0;
    private boolean isPlaying = false;
    private SurfaceHolder surfaceHolder = null;

    private final Handler playbackHandler = new Handler(Looper.getMainLooper());
    private final Runnable playbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPlaying && currentFrameIndex < totalFrames - 1) {
                currentFrameIndex++;
                renderCurrentFrame();
                binding.sbYuvTimeline.setProgress(currentFrameIndex);
                binding.tvFrameIndex.setText("Khung hình: " + currentFrameIndex + " / " + totalFrames);
                playbackHandler.postDelayed(this, 33); // 30 FPS (approx 33ms)
            } else {
                pausePlayback();
            }
        }
    };

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
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
                            yuvFilePath = path;
                            binding.tvPlayerTitle.setText("YUV: " + new File(path).getName());
                        } else {
                            Toast.makeText(this, "Không thể lấy đường dẫn tệp tin tuyệt đối!", Toast.LENGTH_LONG).show();
                        }
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityYuvPlayerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.svYuvRender.getHolder().addCallback(this);

        setupFormatSpinner();

        binding.btnSelectYuv.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            filePickerLauncher.launch(intent);
        });

        binding.btnLoadYuv.setOnClickListener(v -> loadAndStartPlayer());

        binding.btnYuvPlayPause.setOnClickListener(v -> {
            if (totalFrames <= 0) return;
            if (isPlaying) {
                pausePlayback();
            } else {
                startPlayback();
            }
        });

        binding.btnYuvPrev.setOnClickListener(v -> {
            if (currentFrameIndex > 0) {
                currentFrameIndex--;
                updateFramePosition();
            }
        });

        binding.btnYuvNext.setOnClickListener(v -> {
            if (currentFrameIndex < totalFrames - 1) {
                currentFrameIndex++;
                updateFramePosition();
            }
        });

        binding.sbYuvTimeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && totalFrames > 0) {
                    currentFrameIndex = progress;
                    renderCurrentFrame();
                    binding.tvFrameIndex.setText("Khung hình: " + currentFrameIndex + " / " + totalFrames);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                if (isPlaying) pausePlayback();
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // Load intent data if available
        String passedPath = getIntent().getStringExtra("file_path");
        if (passedPath != null && passedPath.endsWith(".yuv")) {
            yuvFilePath = passedPath;
            binding.tvPlayerTitle.setText("YUV: " + new File(passedPath).getName());
        }
    }

    private void setupFormatSpinner() {
        List<String> formats = new ArrayList<>();
        formats.add("I420");
        formats.add("NV12");
        formats.add("NV21");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, formats);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spYuvFormat.setAdapter(adapter);
    }

    private void loadAndStartPlayer() {
        if (yuvFilePath.isEmpty()) {
            Toast.makeText(this, "Vui lòng chọn tệp YUV trước!", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            yuvWidth = Integer.parseInt(binding.etYuvWidth.getText().toString().trim());
            yuvHeight = Integer.parseInt(binding.etYuvHeight.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Độ phân giải không hợp lệ!", Toast.LENGTH_SHORT).show();
            return;
        }

        int pos = binding.spYuvFormat.getSelectedItemPosition();
        if (pos == 0) yuvFormat = "I420";
        else if (pos == 1) yuvFormat = "NV12";
        else if (pos == 2) yuvFormat = "NV21";

        File file = new File(yuvFilePath);
        long fileSize = file.length();
        long frameSize = yuvWidth * yuvHeight * 3 / 2;
        
        if (frameSize <= 0) {
            Toast.makeText(this, "Tính toán kích thước khung hình không hợp lệ!", Toast.LENGTH_SHORT).show();
            return;
        }

        totalFrames = (int) (fileSize / frameSize);
        if (totalFrames <= 0) {
            Toast.makeText(this, "Kích thước tệp tin quá nhỏ cho độ phân giải đã chọn!", Toast.LENGTH_LONG).show();
            return;
        }

        // Allocate bitmap for frame rendering
        if (frameBitmap != null) {
            frameBitmap.recycle();
        }
        frameBitmap = Bitmap.createBitmap(yuvWidth, yuvHeight, Bitmap.Config.ARGB_8888);

        currentFrameIndex = 0;
        binding.sbYuvTimeline.setMax(totalFrames - 1);
        binding.sbYuvTimeline.setProgress(0);
        binding.tvFrameIndex.setText("Khung hình: 0 / " + totalFrames);

        renderCurrentFrame();
        pausePlayback();
        
        Toast.makeText(this, "Đã nạp tệp! Tổng số khung hình: " + totalFrames, Toast.LENGTH_SHORT).show();
    }

    private void renderCurrentFrame() {
        if (frameBitmap == null || surfaceHolder == null || yuvFilePath.isEmpty()) return;

        // Perform fast NDK frame conversion directly into frameBitmap
        boolean success = MediaBridge.convertYUVFrame(yuvFilePath, currentFrameIndex, yuvWidth, yuvHeight, yuvFormat, frameBitmap);
        if (success) {
            Canvas canvas = surfaceHolder.lockCanvas();
            if (canvas != null) {
                canvas.drawColor(Color.BLACK); // Clear with black background

                // Maintain aspect ratio scaling
                float scaleX = (float) canvas.getWidth() / frameBitmap.getWidth();
                float scaleY = (float) canvas.getHeight() / frameBitmap.getHeight();
                float scale = Math.min(scaleX, scaleY);

                float dx = (canvas.getWidth() - frameBitmap.getWidth() * scale) / 2f;
                float dy = (canvas.getHeight() - frameBitmap.getHeight() * scale) / 2f;

                canvas.save();
                canvas.translate(dx, dy);
                canvas.scale(scale, scale);
                canvas.drawBitmap(frameBitmap, 0, 0, null);
                canvas.restore();

                surfaceHolder.unlockCanvasAndPost(canvas);
            }
        }
    }

    private void updateFramePosition() {
        renderCurrentFrame();
        binding.sbYuvTimeline.setProgress(currentFrameIndex);
        binding.tvFrameIndex.setText("Khung hình: " + currentFrameIndex + " / " + totalFrames);
    }

    private void startPlayback() {
        if (currentFrameIndex >= totalFrames - 1) {
            currentFrameIndex = 0;
        }
        isPlaying = true;
        binding.btnYuvPlayPause.setText("⏸ Tạm dừng");
        playbackHandler.post(playbackRunnable);
    }

    private void pausePlayback() {
        isPlaying = false;
        binding.btnYuvPlayPause.setText("▶ Phát");
        playbackHandler.removeCallbacks(playbackRunnable);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceHolder = holder;
        if (totalFrames > 0) {
            renderCurrentFrame();
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        surfaceHolder = holder;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceHolder = null;
        pausePlayback();
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausePlayback();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (frameBitmap != null) {
            frameBitmap.recycle();
            frameBitmap = null;
        }
    }
}
