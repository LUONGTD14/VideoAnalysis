package com.luongtd14.videoanalysis;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.luongtd14.videoanalysis.databinding.ActivityWebmEditorBinding;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class WebmEditorActivity extends AppCompatActivity {

    private ActivityWebmEditorBinding binding;
    private String originalFilePath;
    private String fileName;
    
    private final List<BoxNode> rootNodes = new ArrayList<>();
    private final List<BoxNode> visibleNodes = new ArrayList<>();
    private BoxTreeAdapter adapter;
    
    private BoxNode selectedNode = null;
    
    private static class FieldPatch {
        long offset;
        String type;
        double value;

        FieldPatch(long offset, String type, double value) {
            this.offset = offset;
            this.type = type;
            this.value = value;
        }
    }
    
    private final Map<Long, FieldPatch> recordedFieldPatches = new HashMap<>();
    private final Map<Long, byte[]> recordedPayloadPatches = new HashMap<>();

    private final ActivityResultLauncher<Intent> saveFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri destUri = result.getData().getData();
                    if (destUri != null) {
                        String destPath = PathUtils.getPathFromUri(this, destUri);
                        if (destPath != null) {
                            savePatchedFile(destPath);
                        } else {
                            Toast.makeText(this, "Không thể xác định đường dẫn tệp đích", Toast.LENGTH_LONG).show();
                        }
                    }
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityWebmEditorBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        originalFilePath = getIntent().getStringExtra("file_path");
        fileName = getIntent().getStringExtra("file_name");
        String jsonData = getIntent().getStringExtra("json_data");

        try {
            JSONArray jsonArray = new JSONArray(jsonData);
            rootNodes.addAll(parseJsonToNodes(jsonArray, 0));
            refreshVisibleNodes();
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi giải mã JSON: " + e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        binding.rvBoxTree.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BoxTreeAdapter();
        binding.rvBoxTree.setAdapter(adapter);

        binding.btnSaveAs.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_TITLE, "edited_" + fileName);
            saveFileLauncher.launch(intent);
        });

        binding.btnEditPayloadHex.setOnClickListener(v -> {
            if (selectedNode == null) return;
            binding.hexEditorContainer.setVisibility(View.VISIBLE);
            
            byte[] payload = recordedPayloadPatches.get(selectedNode.payload_offset);
            if (payload == null) {
                payload = readRawPayload(originalFilePath, selectedNode.payload_offset, (int)selectedNode.payload_size);
            }
            
            StringBuilder sb = new StringBuilder();
            if (payload != null) {
                for (byte b : payload) {
                    sb.append(String.format("%02X ", b));
                }
            }
            binding.etHexPayload.setText(sb.toString().trim());
        });

        binding.btnApplyHex.setOnClickListener(v -> {
            if (selectedNode == null) return;
            String rawHex = binding.etHexPayload.getText().toString().replaceAll("\\s+", "");
            try {
                byte[] newBytes = hexStringToByteArray(rawHex);
                if (newBytes.length != selectedNode.payload_size) {
                    throw new IllegalArgumentException("Độ dày byte hex (" + newBytes.length + 
                            " B) phải bằng đúng kích thước payload gốc (" + selectedNode.payload_size + " B).");
                }
                
                recordedPayloadPatches.put(selectedNode.payload_offset, newBytes);
                binding.hexEditorContainer.setVisibility(View.GONE);
                Toast.makeText(this, "Đã cập nhật dữ liệu Hex của element!", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "Lỗi: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    private byte[] readRawPayload(String path, long offset, int size) {
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            raf.seek(offset);
            byte[] buf = new byte[size];
            raf.readFully(buf);
            return buf;
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }

    private List<BoxNode> parseJsonToNodes(JSONArray jsonArray, int depth) throws Exception {
        List<BoxNode> list = new ArrayList<>();
        for (int i = 0; i < jsonArray.length(); i++) {
            JSONObject obj = jsonArray.getJSONObject(i);
            BoxNode node = new BoxNode();
            node.name = obj.getString("name");
            node.offset = obj.getLong("offset");
            node.size = obj.getLong("size");
            node.payload_offset = obj.getLong("payload_offset");
            node.payload_size = obj.getLong("payload_size");
            node.is_container = obj.getBoolean("is_container");
            node.depth = depth;
            
            if (obj.has("children")) {
                node.children = parseJsonToNodes(obj.getJSONArray("children"), depth + 1);
            }
            
            if (obj.has("fields")) {
                JSONObject fieldsObj = obj.getJSONObject("fields");
                Iterator<String> keys = fieldsObj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    node.fields.put(key, fieldsObj.get(key));
                }
            }
            
            if (obj.has("editable_fields")) {
                JSONObject edObj = obj.getJSONObject("editable_fields");
                Iterator<String> keys = edObj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject fSpecObj = edObj.getJSONObject(key);
                    FieldSpec spec = new FieldSpec();
                    spec.offset = fSpecObj.getLong("offset");
                    spec.format = fSpecObj.getString("format");
                    spec.value = fSpecObj.getDouble("value");
                    spec.label = fSpecObj.getString("label");
                    spec.type = fSpecObj.getString("type");
                    node.editable_fields.put(key, spec);
                }
            }
            
            list.add(node);
        }
        return list;
    }

    private void refreshVisibleNodes() {
        visibleNodes.clear();
        addNodesRecursively(rootNodes);
    }

    private void addNodesRecursively(List<BoxNode> nodes) {
        for (BoxNode node : nodes) {
            visibleNodes.add(node);
            if (node.isExpanded && !node.children.isEmpty()) {
                addNodesRecursively(node.children);
            }
        }
    }

    private void selectNode(BoxNode node) {
        selectedNode = node;
        binding.inspectBoxName.setText("ELEMENT: " + node.name);
        
        String infoStr = "Offset: 0x" + Long.toHexString(node.offset).toUpperCase() + 
                "\nKích thước: " + node.size + " Bytes" +
                "\nPayload Size: " + node.payload_size + " Bytes";
        binding.inspectBoxInfo.setText(infoStr);
        
        renderFields(node);
    }

    private void renderFields(BoxNode node) {
        binding.fieldsContainer.removeAllViews();
        binding.hexEditorContainer.setVisibility(View.GONE);
        
        if (node.fields.isEmpty()) {
            TextView tv = new TextView(this);
            tv.setText("Không chứa thông số fields được phân tích đặc thù. Sử dụng Hex Editor để sửa đổi nhị phân.");
            tv.setTypeface(null, Typeface.ITALIC);
            binding.fieldsContainer.addView(tv);
            binding.btnEditPayloadHex.setVisibility(node.payload_size > 0 ? View.VISIBLE : View.GONE);
            return;
        }

        binding.btnEditPayloadHex.setVisibility(node.payload_size > 0 ? View.VISIBLE : View.GONE);
        
        for (Map.Entry<String, Object> entry : node.fields.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 8, 0, 8);
            
            TextView labelTv = new TextView(this);
            labelTv.setText(key.replace("_", " ") + ": ");
            labelTv.setTypeface(null, Typeface.BOLD);
            row.addView(labelTv);
            
            if (node.editable_fields.containsKey(key)) {
                FieldSpec spec = node.editable_fields.get(key);
                
                EditText valueEt = new EditText(this);
                valueEt.setText(String.valueOf(spec.value));
                valueEt.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
                valueEt.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
                row.addView(valueEt);
                
                TextView hexTv = new TextView(this);
                hexTv.setPadding(8, 0, 0, 0);
                hexTv.setTextColor(Color.parseColor("#40A02B"));
                row.addView(hexTv);
                
                valueEt.addTextChangedListener(new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) {}
                    public void afterTextChanged(Editable s) {
                        try {
                            if (s.toString().trim().isEmpty()) return;
                            double val = Double.parseDouble(s.toString().trim());
                            spec.value = val;
                            
                            recordedFieldPatches.put(spec.offset, new FieldPatch(spec.offset, spec.type, val));
                            
                            String hexStr;
                            if (spec.type.equals("fixed16_16")) {
                                long rawHex = (long)(val * 65536) & 0xFFFFFFFFL;
                                hexStr = String.format("0x%08X", rawHex);
                            } else if (spec.type.equals("fixed8_8")) {
                                long rawHex = (long)(val * 256) & 0xFFFFL;
                                hexStr = String.format("0x%04X", rawHex);
                            } else {
                                long rawHex = (long)val;
                                hexStr = "0x" + Long.toHexString(rawHex).toUpperCase();
                            }
                            hexTv.setText(" (" + hexStr + ")");
                        } catch (Exception e) {
                            hexTv.setText(" (Lỗi)");
                        }
                    }
                });
                
                valueEt.setText(String.valueOf(spec.value));
            } else {
                TextView valueTv = new TextView(this);
                valueTv.setText(String.valueOf(value));
                row.addView(valueTv);
            }
            binding.fieldsContainer.addView(row);
        }
    }

    private void savePatchedFile(String destPath) {
        binding.btnSaveAs.setText("Đang lưu...");
        binding.btnSaveAs.setEnabled(false);
        
        try {
            try (InputStream in = new FileInputStream(originalFilePath);
                 OutputStream out = new FileOutputStream(destPath)) {
                byte[] buf = new byte[1024 * 64];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }

            for (FieldPatch patch : recordedFieldPatches.values()) {
                MediaBridge.patchField(destPath, patch.offset, patch.type, patch.value);
            }
            
            for (Map.Entry<Long, byte[]> entry : recordedPayloadPatches.entrySet()) {
                MediaBridge.patchPayload(destPath, entry.getKey(), entry.getValue());
            }
            
            Toast.makeText(this, "Đã lưu tệp tin nhị phân mới thành công!", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Lỗi lưu: " + e.getMessage(), Toast.LENGTH_LONG).show();
        } finally {
            binding.btnSaveAs.setText("Lưu thành tệp mới");
            binding.btnSaveAs.setEnabled(true);
        }
    }

    public static class BoxNode {
        public String name;
        public long offset;
        public long size;
        public long payload_offset;
        public long payload_size;
        public boolean is_container;
        public List<BoxNode> children = new ArrayList<>();
        public Map<String, Object> fields = new HashMap<>();
        public Map<String, FieldSpec> editable_fields = new HashMap<>();
        
        public boolean isExpanded = false;
        public int depth = 0;
    }

    public static class FieldSpec {
        public long offset;
        public String format;
        public double value;
        public String label;
        public String type;
    }

    private class BoxTreeAdapter extends RecyclerView.Adapter<BoxTreeAdapter.ViewHolder> {

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_box_node, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            BoxNode node = visibleNodes.get(position);
            holder.tvName.setText((node.is_container ? "📁 " : "📄 ") + node.name);
            holder.tvSize.setText(String.format("%,d B", node.size));
            
            holder.indentSpacer.getLayoutParams().width = node.depth * 36;
            holder.indentSpacer.requestLayout();

            if (node.is_container) {
                holder.ivExpand.setVisibility(View.VISIBLE);
                holder.ivExpand.setRotation(node.isExpanded ? 0 : -90);
                holder.ivExpand.setOnClickListener(v -> toggleNode(node, position));
            } else {
                holder.ivExpand.setVisibility(View.INVISIBLE);
            }

            holder.itemView.setOnClickListener(v -> selectNode(node));
        }

        private void toggleNode(BoxNode node, int position) {
            if (node.isExpanded) {
                node.isExpanded = false;
                int count = 0;
                int idx = position + 1;
                while (idx < visibleNodes.size() && visibleNodes.get(idx).depth > node.depth) {
                    visibleNodes.remove(idx);
                    count++;
                }
                notifyItemRangeRemoved(position + 1, count);
                notifyItemChanged(position);
            } else {
                node.isExpanded = true;
                List<BoxNode> toInsert = new ArrayList<>();
                collectExpandedChildren(node, toInsert);
                visibleNodes.addAll(position + 1, toInsert);
                notifyItemRangeInserted(position + 1, toInsert.size());
                notifyItemChanged(position);
            }
        }

        private void collectExpandedChildren(BoxNode parent, List<BoxNode> outList) {
            for (BoxNode child : parent.children) {
                outList.add(child);
                if (child.isExpanded && !child.children.isEmpty()) {
                    collectExpandedChildren(child, outList);
                }
            }
        }

        @Override
        public int getItemCount() {
            return visibleNodes.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            View indentSpacer;
            ImageView ivExpand;
            TextView tvName;
            TextView tvSize;

            public ViewHolder(@NonNull View itemView) {
                super(itemView);
                indentSpacer = itemView.findViewById(R.id.indent_spacer);
                ivExpand = itemView.findViewById(R.id.iv_expand_icon);
                tvName = itemView.findViewById(R.id.tv_box_name);
                tvSize = itemView.findViewById(R.id.tv_box_size);
            }
        }
    }
}
