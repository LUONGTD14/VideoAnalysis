package com.luongtd14.videoanalysis;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;

import com.luongtd14.videoanalysis.databinding.ActivityMainBinding;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;

    private ActivityMainBinding binding;
    private Uri selectedFileUri = null;
    private String selectedFilePath = "";
    private long selectedFileSize = 0;
    private String selectedFileName = "";
    private String detectedFormat = "N/A";
    
    // JSON response from native parser
    private String parsedJsonData = "";

    private final ActivityResultLauncher<Intent> filePickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    selectedFileUri = result.getData().getData();
                    if (selectedFileUri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(selectedFileUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception ignored) {}
                        
                        selectedFilePath = PathUtils.getPathFromUri(this, selectedFileUri);
                        if (selectedFilePath == null || selectedFilePath.isEmpty()) {
                            Toast.makeText(this, "Không thể chuyển đổi URI thành đường dẫn tệp tuyệt đối!", Toast.LENGTH_LONG).show();
                            return;
                        }
                        
                        displayFileInfo();
                        parseSelectedFile();
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        requestPermissions();

        binding.btnSelectFile.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            String[] mimeTypes = {"video/mp4", "video/quicktime", "video/x-matroska", "video/webm"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            filePickerLauncher.launch(intent);
        });

        binding.btnTreeView.setOnClickListener(v -> startEditorActivity(0));
        binding.btnMetadataEditor.setOnClickListener(v -> startEditorActivity(1));
        binding.btnHexEditor.setOnClickListener(v -> startEditorActivity(2));
    }

    private void displayFileInfo() {
        Cursor cursor = getContentResolver().query(selectedFileUri, null, null, null, null);
        if (cursor != null && cursor.moveToFirst()) {
            int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
            selectedFileName = cursor.getString(nameIndex);
            selectedFileSize = cursor.getLong(sizeIndex);
            cursor.close();
        }

        binding.fileNameLbl.setText("Tên file: " + selectedFileName);
        
        String sizeStr;
        if (selectedFileSize > 1024 * 1024) {
            sizeStr = String.format("%.2f MB", selectedFileSize / (1024.0 * 1024.0));
        } else if (selectedFileSize > 1024) {
            sizeStr = String.format("%.2f KB", selectedFileSize / 1024.0);
        } else {
            sizeStr = selectedFileSize + " Bytes";
        }
        binding.fileSizeLbl.setText("Kích thước: " + sizeStr);
    }

    private void parseSelectedFile() {
        if (selectedFilePath.isEmpty()) return;
        
        binding.titleLbl.setText("Đang phân tích...");
        
        try {
            byte[] sig = new byte[4];
            try (FileInputStream fis = new FileInputStream(selectedFilePath)) {
                fis.read(sig);
            }
            
            if (sig[0] == 0x1A && sig[1] == 0x45 && sig[2] == 0xDF && sig[3] == 0xA3) {
                detectedFormat = "Matroska / WebM (EBML)";
            } else {
                detectedFormat = "MP4 / MOV (ISOBMFF)";
            }
            
            binding.fileTypeLbl.setText("Định dạng: " + detectedFormat);

            // Run native parser using absolute file path string
            parsedJsonData = MediaBridge.parseMediaFile(selectedFilePath);
            
            binding.titleLbl.setText("Aura Media Box Viewer");
            
            binding.btnTreeView.setEnabled(true);
            binding.btnMetadataEditor.setEnabled(true);
            binding.btnHexEditor.setEnabled(true);
            
            Toast.makeText(this, "Đã phân tích cấu trúc nhị phân thành công!", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            binding.titleLbl.setText("Lỗi phân tích");
            binding.fileTypeLbl.setText("Định dạng: Lỗi");
            Toast.makeText(this, "Không thể đọc hoặc phân tích file: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startEditorActivity(int mode) {
        if (selectedFilePath.isEmpty() || parsedJsonData.isEmpty()) return;
        
        Intent intent;
        if (detectedFormat.contains("Matroska") || detectedFormat.contains("WebM")) {
            intent = new Intent(this, WebmEditorActivity.class);
        } else {
            intent = new Intent(this, Mp4EditorActivity.class);
        }
        
        intent.putExtra("file_path", selectedFilePath);
        intent.putExtra("file_name", selectedFileName);
        intent.putExtra("json_data", parsedJsonData);
        intent.putExtra("mode", mode);
        startActivity(intent);
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            }
        } else {
            String[] perms = {Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};
            List<String> needed = new ArrayList<>();
            for (String p : perms) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) needed.add(p);
            }
            if (!needed.isEmpty()) ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Quyền truy cập là bắt buộc", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
}