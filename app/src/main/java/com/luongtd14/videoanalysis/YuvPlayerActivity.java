package com.luongtd14.videoanalysis;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
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
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Toast;

import com.luongtd14.videoanalysis.databinding.ActivityYuvPlayerBinding;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class YuvPlayerActivity extends AppCompatActivity {

    private ActivityYuvPlayerBinding binding;
    
    // Video 1 configurations
    private String yuvFilePath = "";
    private int yuvWidth = 1280;
    private int yuvHeight = 720;
    private String yuvFormat = "I420";
    private int totalFrames1 = 0;
    private Bitmap frameBitmap1 = null;
    private SurfaceHolder surfaceHolder1 = null;

    // Video 2 configurations
    private String yuvFilePath2 = "";
    private int yuvWidth2 = 1280;
    private int yuvHeight2 = 720;
    private String yuvFormat2 = "I420";
    private int totalFrames2 = 0;
    private Bitmap frameBitmap2 = null;
    private SurfaceHolder surfaceHolder2 = null;

    // Playback control state
    private int activeSelectorIndex = 1;
    private int currentFrameIndex = 0;
    private int totalFrames = 0;
    private boolean isPlaying = false;

    private final Handler playbackHandler = new Handler(Looper.getMainLooper());
    private final Runnable playbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (isPlaying && currentFrameIndex < totalFrames - 1) {
                currentFrameIndex++;
                renderCurrentFrame();
                binding.sbYuvTimeline.setProgress(currentFrameIndex);
                binding.tvFrameIndex.setText("Khung hình: " + currentFrameIndex + " / " + totalFrames);
                playbackHandler.postDelayed(this, 33);
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
                            if (activeSelectorIndex == 1) {
                                yuvFilePath = path;
                                binding.tvTitleV1.setText("VIDEO 1: " + new File(path).getName());
                            } else {
                                yuvFilePath2 = path;
                                binding.tvTitleV2.setText("VIDEO 2: " + new File(path).getName());
                            }
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

        // Set SurfaceHolder callbacks for both SurfaceViews
        binding.svYuvRender.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                surfaceHolder1 = holder;
                if (totalFrames > 0) renderCurrentFrame();
            }
            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                surfaceHolder1 = holder;
            }
            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                surfaceHolder1 = null;
                pausePlayback();
            }
        });

        binding.svYuvRender2.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                surfaceHolder2 = holder;
                if (totalFrames > 0) renderCurrentFrame();
            }
            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
                surfaceHolder2 = holder;
            }
            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                surfaceHolder2 = null;
                pausePlayback();
            }
        });

        binding.rlConfigHeader.setOnClickListener(v -> {
            if (binding.llSettingsContainer.getVisibility() == View.VISIBLE) {
                binding.llSettingsContainer.setVisibility(View.GONE);
                binding.tvCollapseToggle.setText("[Mở rộng]");
            } else {
                binding.llSettingsContainer.setVisibility(View.VISIBLE);
                binding.tvCollapseToggle.setText("[Thu gọn]");
            }
        });

        setupFormatSpinner(binding.spYuvFormat);
        setupFormatSpinner(binding.spYuvFormat2);

        binding.btnSelectYuv.setOnClickListener(v -> {
            activeSelectorIndex = 1;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            filePickerLauncher.launch(intent);
        });

        binding.btnSelectYuv2.setOnClickListener(v -> {
            activeSelectorIndex = 2;
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

        String passedPath = getIntent().getStringExtra("file_path");
        if (passedPath != null && passedPath.endsWith(".yuv")) {
            yuvFilePath = passedPath;
            binding.tvTitleV1.setText("VIDEO 1: " + new File(passedPath).getName());
        }
    }

    private void setupFormatSpinner(android.widget.Spinner spinner) {
        List<String> formats = new ArrayList<>();
        formats.add("I420");
        formats.add("NV12");
        formats.add("NV21");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, formats);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }

    private void loadAndStartPlayer() {
        if (yuvFilePath.isEmpty()) {
            Toast.makeText(this, "Vui lòng chọn Video 1 trước!", Toast.LENGTH_SHORT).show();
            return;
        }

        // 1. Load Video 1 settings
        try {
            yuvWidth = Integer.parseInt(binding.etYuvWidth.getText().toString().trim());
            yuvHeight = Integer.parseInt(binding.etYuvHeight.getText().toString().trim());
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Độ phân giải Video 1 không hợp lệ!", Toast.LENGTH_SHORT).show();
            return;
        }

        int pos = binding.spYuvFormat.getSelectedItemPosition();
        if (pos == 0) yuvFormat = "I420";
        else if (pos == 1) yuvFormat = "NV12";
        else if (pos == 2) yuvFormat = "NV21";

        long fileSize1 = new File(yuvFilePath).length();
        long frameSize1 = yuvWidth * yuvHeight * 3 / 2;
        totalFrames1 = (int) (fileSize1 / frameSize1);

        if (totalFrames1 <= 0) {
            Toast.makeText(this, "Tệp Video 1 quá nhỏ cho độ phân giải đã chọn!", Toast.LENGTH_LONG).show();
            return;
        }

        if (frameBitmap1 != null) frameBitmap1.recycle();
        frameBitmap1 = Bitmap.createBitmap(yuvWidth, yuvHeight, Bitmap.Config.ARGB_8888);

        // 2. Load Video 2 settings (optional)
        if (!yuvFilePath2.isEmpty()) {
            try {
                yuvWidth2 = Integer.parseInt(binding.etYuvWidth2.getText().toString().trim());
                yuvHeight2 = Integer.parseInt(binding.etYuvHeight2.getText().toString().trim());
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Độ phân giải Video 2 không hợp lệ!", Toast.LENGTH_SHORT).show();
                return;
            }

            int pos2 = binding.spYuvFormat2.getSelectedItemPosition();
            if (pos2 == 0) yuvFormat2 = "I420";
            else if (pos2 == 1) yuvFormat2 = "NV12";
            else if (pos2 == 2) yuvFormat2 = "NV21";

            long fileSize2 = new File(yuvFilePath2).length();
            long frameSize2 = yuvWidth2 * yuvHeight2 * 3 / 2;
            totalFrames2 = (int) (fileSize2 / frameSize2);

            if (totalFrames2 > 0) {
                if (frameBitmap2 != null) frameBitmap2.recycle();
                frameBitmap2 = Bitmap.createBitmap(yuvWidth2, yuvHeight2, Bitmap.Config.ARGB_8888);
                binding.svYuvRender2.setVisibility(View.VISIBLE);
                totalFrames = Math.max(totalFrames1, totalFrames2);
            } else {
                binding.svYuvRender2.setVisibility(View.GONE);
                totalFrames = totalFrames1;
            }
        } else {
            binding.svYuvRender2.setVisibility(View.GONE);
            totalFrames2 = 0;
            if (frameBitmap2 != null) {
                frameBitmap2.recycle();
                frameBitmap2 = null;
            }
            totalFrames = totalFrames1;
        }

        currentFrameIndex = 0;
        binding.sbYuvTimeline.setMax(totalFrames - 1);
        binding.sbYuvTimeline.setProgress(0);
        binding.tvFrameIndex.setText("Khung hình: 0 / " + totalFrames);

        renderCurrentFrame();
        pausePlayback();

        // Auto-collapse settings panel on successful configuration load
        binding.llSettingsContainer.setVisibility(View.GONE);
        binding.tvCollapseToggle.setText("[Mở rộng]");

        Toast.makeText(this, "Đã nạp video! Số khung hình: " + totalFrames, Toast.LENGTH_SHORT).show();
    }

    private void renderCurrentFrame() {
        // Draw Video 1
        if (frameBitmap1 != null && surfaceHolder1 != null && !yuvFilePath.isEmpty()) {
            if (currentFrameIndex < totalFrames1) {
                boolean success = MediaBridge.convertYUVFrame(yuvFilePath, currentFrameIndex, yuvWidth, yuvHeight, yuvFormat, frameBitmap1);
                if (success) {
                    drawBitmapToSurface(frameBitmap1, surfaceHolder1);
                }
            } else {
                drawBlackBackground(surfaceHolder1, "Hết Video 1");
            }
        }

        // Draw Video 2 (if loaded)
        if (totalFrames2 > 0 && frameBitmap2 != null && surfaceHolder2 != null && !yuvFilePath2.isEmpty()) {
            if (currentFrameIndex < totalFrames2) {
                boolean success = MediaBridge.convertYUVFrame(yuvFilePath2, currentFrameIndex, yuvWidth2, yuvHeight2, yuvFormat2, frameBitmap2);
                if (success) {
                    drawBitmapToSurface(frameBitmap2, surfaceHolder2);
                }
            } else {
                drawBlackBackground(surfaceHolder2, "Hết Video 2");
            }
        }
    }

    private void drawBitmapToSurface(Bitmap bitmap, SurfaceHolder holder) {
        Canvas canvas = holder.lockCanvas();
        if (canvas != null) {
            canvas.drawColor(Color.BLACK);
            
            float scaleX = (float) canvas.getWidth() / bitmap.getWidth();
            float scaleY = (float) canvas.getHeight() / bitmap.getHeight();
            float scale = Math.min(scaleX, scaleY);

            float dx = (canvas.getWidth() - bitmap.getWidth() * scale) / 2f;
            float dy = (canvas.getHeight() - bitmap.getHeight() * scale) / 2f;

            canvas.save();
            canvas.translate(dx, dy);
            canvas.scale(scale, scale);
            canvas.drawBitmap(bitmap, 0, 0, null);
            canvas.restore();

            holder.unlockCanvasAndPost(canvas);
        }
    }

    private void drawBlackBackground(SurfaceHolder holder, String message) {
        Canvas canvas = holder.lockCanvas();
        if (canvas != null) {
            canvas.drawColor(Color.BLACK);
            
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setColor(Color.WHITE);
            paint.setTextSize(40);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            
            canvas.drawText(message, canvas.getWidth() / 2f, canvas.getHeight() / 2f, paint);
            
            holder.unlockCanvasAndPost(canvas);
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
    protected void onPause() {
        super.onPause();
        pausePlayback();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (frameBitmap1 != null) {
            frameBitmap1.recycle();
            frameBitmap1 = null;
        }
        if (frameBitmap2 != null) {
            frameBitmap2.recycle();
            frameBitmap2 = null;
        }
    }
}
