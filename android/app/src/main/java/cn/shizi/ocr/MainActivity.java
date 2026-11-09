package cn.shizi.ocr;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PICK = 101, PHOTO = 102, EXPORT = 103;
    private static final int PURPLE = 0xff7362db, INK = 0xff252d40, MUTED = 0xff858d9f;
    private static final long MAX_BYTES = 40L * 1024 * 1024;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<OcrProcessor.Page> pages = new ArrayList<>();
    private OcrProcessor ocr;
    private TextView networkLabel, fileLabel, status, counter;
    private Button importButton, photoButton, recognizeButton, translateButton, previewButton;
    private EditText pageRange, source, translated;
    private ProgressBar progress;
    private Spinner pageSelect;
    private ImageView preview;
    private File selectedFile, cameraFile;
    private String selectedName = "文档", pendingExport;
    private boolean isPdf, busy, changingText;
    private int currentPage = 0, previewVersion = 0;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        if (state != null && state.getString("camera") != null) cameraFile = new File(state.getString("camera"));
        if (state != null) pendingExport = state.getString("export");
        updateNetwork(); updateControls();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xfff7f8fb);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(22), dp(24), dp(22), dp(28));
        scroll.addView(root); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            root.setPadding(dp(22), dp(20) + insets.getSystemWindowInsetTop(), dp(22), dp(24) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView brand = label("拾字  /  TEXT STUDIO", 21, INK); brand.setTypeface(null, Typeface.BOLD); root.addView(brand);
        networkLabel = label("本地离线识别", 11, MUTED); add(root, networkLabel, 7);
        TextView title = label("让图片里的文字，\n流动起来。", 29, INK); title.setTypeface(null, Typeface.BOLD); add(root, title, 26);
        add(root, label("图片与 PDF · 中英文识别 · 英译中", 12, MUTED), 8);

        LinearLayout input = card(root, 24);
        add(input, label("01  导入文件", 16, INK), 0);
        add(input, label("识别模型已内置，首次使用也无需联网下载。", 11, MUTED), 7);
        LinearLayout row = row(input, 14);
        importButton = button("导入图片 / PDF", false); photoButton = button("拍照", false);
        addWeighted(row, importButton); addWeighted(row, photoButton);
        importButton.setOnClickListener(v -> pickFile()); photoButton.setOnClickListener(v -> takePhoto());
        fileLabel = label("尚未选择文件 · 单个文件最大 40 MB", 12, MUTED); add(input, fileLabel, 12);
        pageRange = edit("PDF 页码选填，例如 1-5,8", false); pageRange.setSingleLine(true); pageRange.setTextSize(12); add(input, pageRange, 12);
        recognizeButton = button("开始离线识别", true); add(input, recognizeButton, 15); recognizeButton.setOnClickListener(v -> startOcr());
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setIndeterminate(true); progress.setVisibility(View.GONE); add(input, progress, 9);
        status = label("文件仅在手机内处理，识别无需电脑或服务器。", 11, MUTED); add(input, status, 9);
        previewButton = button("展开文件预览", false); previewButton.setVisibility(View.GONE); add(input, previewButton, 5);
        preview = new ImageView(this); preview.setAdjustViewBounds(true); preview.setMaxHeight(dp(300)); preview.setVisibility(View.GONE); add(input, preview, 8);
        previewButton.setOnClickListener(v -> {
            boolean open = preview.getVisibility() != View.VISIBLE;
            preview.setVisibility(open ? View.VISIBLE : View.GONE);
            previewButton.setText(open ? "收起文件预览" : "展开文件预览");
            if (open) loadPreview();
        });

        LinearLayout result = card(root, 18);
        LinearLayout heading = row(result, 0);
        TextView resultTitle = label("02  识别原文", 16, INK); heading.addView(resultTitle, new LinearLayout.LayoutParams(0, -2, 1));
        counter = label("0 字符", 11, MUTED); heading.addView(counter);
        pageSelect = new Spinner(this); add(result, pageSelect, 10); setPageOptions();
        pageSelect.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!busy && position != currentPage && position < pages.size()) { savePage(); showPage(position); }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        source = edit("识别后的文字会出现在这里。\n\n可以编辑校对，再翻译为中文。", true); source.setMinHeight(dp(190)); source.setId(R.id.source_text); add(result, source, 7);
        source.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!changingText) { translated.setText(""); if (currentPage < pages.size()) pages.get(currentPage).translation = ""; }
                updateControls();
            }
            public void afterTextChanged(Editable s) {}
        });
        LinearLayout actions = row(result, 10);
        Button copy = button("复制原文", false), export = button("导出 TXT", false);
        addWeighted(actions, copy); addWeighted(actions, export);
        copy.setOnClickListener(v -> copy(source.getText().toString())); export.setOnClickListener(v -> export(false));

        LinearLayout translationCard = card(root, 18);
        add(translationCard, label("03  中文译文", 16, INK), 0);
        add(translationCard, label("翻译需要联网，当前页文字会发送至 MyMemory 服务。", 11, MUTED), 8);
        translateButton = button("翻译当前页为中文", true); translateButton.setId(R.id.translate_button); add(translationCard, translateButton, 13);
        translateButton.setOnClickListener(v -> startTranslation());
        translated = edit("联网后点击上方按钮，即可翻译当前页英文。", true); translated.setMinHeight(dp(170)); translated.setId(R.id.translated_text); add(translationCard, translated, 12);
        LinearLayout translatedActions = row(translationCard, 9);
        Button copyTranslation = button("复制译文", false), exportTranslation = button("导出译文", false);
        addWeighted(translatedActions, copyTranslation); addWeighted(translatedActions, exportTranslation);
        copyTranslation.setOnClickListener(v -> copy(translated.getText().toString())); exportTranslation.setOnClickListener(v -> export(true));
        add(root, label("印刷体效果更好；手写、模糊图片和复杂版式请对照原图校对。\n结果在本次会话保留，重要文字请及时导出。", 10, MUTED), 20);
    }

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
        try { startActivityForResult(intent, PICK); } catch (Exception e) { message("无法打开文件选择器", "请确认手机已启用系统文件管理器。"); }
    }

    private void takePhoto() {
        try {
            File dir = new File(getCacheDir(), "camera"); if (!dir.exists() && !dir.mkdirs()) throw new IOException();
            cameraFile = File.createTempFile("photo-", ".jpg", dir);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", cameraFile);
            Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri);
            intent.setClipData(ClipData.newRawUri("拍照", uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(intent, PHOTO);
        } catch (Exception e) { if (cameraFile != null) cameraFile.delete(); message("无法打开相机", "可先使用手机相机拍照，再通过「导入图片」选择照片。"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == EXPORT) {
            if (result == RESULT_OK && data != null && data.getData() != null && pendingExport != null) {
                String content = pendingExport; Uri uri = data.getData();
                worker.execute(() -> {
                    try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                        if (output == null) throw new IOException();
                        output.write(("\ufeff" + content).getBytes(StandardCharsets.UTF_8)); ui(() -> toast("已导出 TXT。"));
                    } catch (Exception e) { ui(() -> message("导出失败", "请重新选择保存位置后重试。")); }
                });
            }
            pendingExport = null; return;
        }
        if (request == PHOTO) {
            if (result == RESULT_OK && cameraFile != null && cameraFile.length() > 0) importUri(Uri.fromFile(cameraFile), "拍照.jpg");
            else if (cameraFile != null) { cameraFile.delete(); cameraFile = null; }
        } else if (request == PICK && result == RESULT_OK && data != null && data.getData() != null) importUri(data.getData(), null);
    }

    private void importUri(Uri uri, String name) {
        if (busy) return;
        setBusy(true, "正在导入文件…");
        worker.execute(() -> {
            File copy = null;
            try {
                String displayName = name;
                if (displayName == null) {
                    try (android.database.Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) displayName = cursor.getString(0);
                    }
                }
                if (displayName == null) displayName = "文档";
                copy = File.createTempFile("import-", ".bin", getCacheDir());
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(copy)) {
                    if (in == null) throw new IOException("无法打开所选文件。");
                    byte[] buffer = new byte[16384]; int read; long size = 0;
                    while ((read = in.read(buffer)) != -1) {
                        size += read;
                        if (size > MAX_BYTES) throw new IOException("文件超过 40 MB，请缩小文件后重试。");
                        out.write(buffer, 0, read);
                    }
                    if (size == 0) throw new IOException("文件是空的。");
                }
                byte[] magic = new byte[5];
                try (InputStream in = new FileInputStream(copy)) { in.read(magic); }
                boolean pdf = new String(magic, StandardCharsets.US_ASCII).equals("%PDF-");
                Bitmap thumbnail = OcrProcessor.preview(copy, pdf, 0);
                File imported = copy; String filename = displayName;
                ui(() -> {
                    if (selectedFile != null) selectedFile.delete();
                    selectedFile = imported; selectedName = filename; isPdf = pdf;
                    pages.clear(); currentPage = 0; previewVersion++;
                    changingText = true; source.setText(""); translated.setText(""); changingText = false;
                    setPageOptions(); preview.setImageBitmap(thumbnail); preview.setVisibility(View.GONE);
                    previewButton.setVisibility(View.VISIBLE); previewButton.setText("展开文件预览");
                    fileLabel.setText(filename + " · " + String.format(java.util.Locale.CHINA, "%.2f MB", imported.length() / 1024.0 / 1024.0));
                    pageRange.setText(""); setBusy(false, "已导入。点击「开始离线识别」提取文字。");
                });
            } catch (OutOfMemoryError error) {
                if (copy != null) copy.delete(); ui(() -> { setBusy(false, "导入未完成"); message("图片过大", "请缩小图片后重试。"); });
            } catch (Exception e) {
                if (copy != null) copy.delete();
                String reason = e instanceof SecurityException ? "文件无权限或 PDF 有密码保护，请选择可访问且已解密的文件。" : e.getMessage();
                ui(() -> { setBusy(false, "导入未完成"); message("无法导入文件", reason == null ? "文件损坏或格式不支持，请更换文件。" : reason); });
            } finally { if (cameraFile != null) { cameraFile.delete(); cameraFile = null; } }
        });
    }

    private void startOcr() {
        if (selectedFile == null || busy) return;
        hideKeyboard(); savePage(); String range = pageRange.getText().toString();
        setBusy(true, "正在加载本地识别模型…");
        worker.execute(() -> {
            try {
                if (ocr == null) ocr = new OcrProcessor();
                List<OcrProcessor.Page> found = ocr.recognize(selectedFile, isPdf, range,
                    (done, total, original) -> ui(() -> status.setText("正在识别第 " + (done + 1) + "/" + total + " 页（文档第 " + original + " 页）…")));
                ui(() -> {
                    pages.clear(); pages.addAll(found); currentPage = 0;
                    setPageOptions(); if (!pages.isEmpty()) showPage(0);
                    setBusy(false, "离线识别完成，共 " + pages.size() + " 页。可编辑校对原文。");
                    boolean empty = true; for (OcrProcessor.Page p : pages) if (!p.text.trim().isEmpty()) empty = false;
                    if (empty) message("未发现可识别文字", "请换用更清晰的图片，确保文字方向正确、光线充足。");
                });
            } catch (OutOfMemoryError error) { ui(() -> { setBusy(false, "识别未完成，已有结果已保留。"); message("内存不足", "请缩小图片，或减少 PDF 识别页数后重试。"); }); }
            catch (Exception e) { ui(() -> { setBusy(false, "识别未完成，已有结果已保留。"); message("识别失败", e.getMessage() == null ? "请更换文件或减少 PDF 页数后重试。" : e.getMessage()); }); }
        });
    }

    private void startTranslation() {
        if (busy) return;
        String text = source.getText().toString();
        if (text.trim().isEmpty()) { toast("请先识别或输入文字。"); return; }
        if (!isOnline()) { showNetworkRequired(); return; }
        if (text.length() > 60000) { message("文字过长", "请将当前页文字缩短至 60000 字符以内后翻译。"); return; }
        hideKeyboard();
        translateOnline(text);
    }

    private void translateOnline(String text) {
        if (!isOnline()) { showNetworkRequired(); return; }
        setBusy(true, "正在翻译，识别原文已保留…");
        worker.execute(() -> {
            try {
                String result = new Translator().translate(text, (done, total) -> ui(() -> status.setText("正在翻译 " + done + "/" + total + "…")));
                ui(() -> { translated.setText(result); savePage(); setBusy(false, "翻译完成。"); toast("翻译完成，可复制或导出。"); });
            } catch (Exception e) {
                ui(() -> {
                    setBusy(false, "翻译未完成，识别原文和已有译文已保留。");
                    if (!isOnline()) showNetworkRequired();
                    else message("翻译暂不可用", "请确认网络可访问互联网，稍后重试。\n\n" + (e.getMessage() == null ? "翻译服务连接失败。" : e.getMessage()));
                });
            }
        });
    }

    boolean isOnline() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network active = manager.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : manager.getNetworkCapabilities(active);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    void showNetworkRequired() {
        new AlertDialog.Builder(this).setTitle("翻译需要开启网络")
            .setMessage("当前无可用网络，请开启 Wi-Fi 或移动数据后重试。\n\n离线文字识别可继续使用，识别原文已保留。")
            .setNegativeButton("稍后翻译", null)
            .setPositiveButton("打开网络设置", (dialog, which) -> {
                try {
                    startActivity(new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_INTERNET_CONNECTIVITY : Settings.ACTION_WIRELESS_SETTINGS));
                } catch (Exception e) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            }).show();
    }

    private void savePage() {
        if (currentPage < pages.size()) { pages.get(currentPage).text = source.getText().toString(); pages.get(currentPage).translation = translated.getText().toString(); }
    }
    private void showPage(int position) {
        currentPage = position; OcrProcessor.Page page = pages.get(position);
        changingText = true; source.setText(page.text); translated.setText(page.translation); changingText = false;
        updateControls(); if (preview.getVisibility() == View.VISIBLE) loadPreview();
    }
    private void setPageOptions() {
        List<String> labels = new ArrayList<>();
        for (OcrProcessor.Page page : pages) labels.add("第 " + page.number + " 页");
        if (labels.isEmpty()) labels.add("第 1 页");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); pageSelect.setAdapter(adapter);
    }
    private void loadPreview() {
        if (selectedFile == null) return;
        File file = selectedFile; boolean pdf = isPdf; int version = ++previewVersion;
        int index = pages.isEmpty() ? 0 : pages.get(currentPage).number - 1;
        worker.execute(() -> {
            try {
                Bitmap bitmap = OcrProcessor.preview(file, pdf, index);
                ui(() -> { if (version == previewVersion) preview.setImageBitmap(bitmap); else bitmap.recycle(); });
            } catch (Exception | OutOfMemoryError e) { ui(() -> toast("无法生成预览，识别文字仍可使用。")); }
        });
    }

    private void copy(String text) {
        if (text.trim().isEmpty()) { toast("暂无可复制的文字。"); return; }
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("拾字", text)); toast("已复制。");
    }
    private void export(boolean translation) {
        if (busy) { toast("请等待当前任务完成。"); return; }
        savePage(); StringBuilder content = new StringBuilder(); int exported = 0;
        if (pages.isEmpty()) content.append(translation ? translated.getText() : source.getText());
        else for (OcrProcessor.Page page : pages) {
            String text = translation ? page.translation : page.text;
            if (text.trim().isEmpty()) continue;
            if (content.length() > 0) content.append("\n\n");
            if (pages.size() > 1) content.append("—— 第 ").append(page.number).append(" 页 ——\n\n");
            content.append(text); exported++;
        }
        if (content.toString().trim().isEmpty()) { toast("暂无可导出的文字。"); return; }
        if (translation && exported < pages.size()) toast("将导出已翻译页面，其余页面可逐页翻译后再导出。");
        pendingExport = content.toString();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, selectedName.replaceAll("\\.[^.]+$", "") + (translation ? "-中文译文.txt" : "-识别原文.txt"));
        try { startActivityForResult(intent, EXPORT); } catch (Exception e) { pendingExport = null; message("无法保存", "系统文件管理器不可用，可以先复制文字。"); }
    }

    private void setBusy(boolean value, String message) {
        busy = value; progress.setVisibility(value ? View.VISIBLE : View.GONE); status.setText(message); updateControls();
    }
    private void updateControls() {
        if (source == null || translated == null) return;
        importButton.setEnabled(!busy); photoButton.setEnabled(!busy);
        recognizeButton.setEnabled(!busy && selectedFile != null); translateButton.setEnabled(!busy && source.length() > 0);
        pageRange.setEnabled(!busy && isPdf); pageSelect.setEnabled(!busy && pages.size() > 1);
        source.setEnabled(!busy); translated.setEnabled(!busy); previewButton.setEnabled(!busy);
        counter.setText(source.length() + " 字符"); updateNetwork();
    }
    private void updateNetwork() { if (networkLabel != null) networkLabel.setText(isOnline() ? "● 本地离线识别 · 网络可用，可翻译" : "● 本地离线识别 · 当前离线，识别不受影响"); }
    @Override protected void onResume() { super.onResume(); updateNetwork(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); if (cameraFile != null) out.putString("camera", cameraFile.getAbsolutePath());
        // Export content can be large; it remains in this Activity during the system picker.
    }
    @Override protected void onDestroy() {
        // Let an in-flight recognizer finish before closing model resources and files.
        worker.execute(() -> { if (ocr != null) ocr.close(); if (selectedFile != null) selectedFile.delete(); if (cameraFile != null) cameraFile.delete(); });
        worker.shutdown(); super.onDestroy();
    }
    private void ui(Runnable runnable) { runOnUiThread(() -> { if (!isDestroyed() && !isFinishing()) runnable.run(); }); }
    private void hideKeyboard() { ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(source.getWindowToken(), 0); source.clearFocus(); translated.clearFocus(); }
    private void message(String title, String text) { new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("知道了", null).show(); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color, int stroke) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(13)); if (stroke != 0) shape.setStroke(dp(1), stroke); return shape; }
    private TextView label(String text, int size, int color) { TextView view = new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color); view.setLineSpacing(dp(3), 1); return view; }
    private Button button(String text, boolean primary) {
        Button button = new Button(this); button.setText(text); button.setTextSize(13); button.setAllCaps(false);
        button.setTextColor(primary ? Color.WHITE : PURPLE); button.setBackground(background(primary ? PURPLE : 0xfff3f0ff, 0));
        button.setPadding(dp(12), dp(10), dp(12), dp(10)); button.setMinHeight(dp(47)); button.setMinimumHeight(dp(47)); return button;
    }
    private EditText edit(String hint, boolean multiline) {
        EditText field = new EditText(this); field.setHint(hint); field.setTextSize(14); field.setTextColor(INK); field.setHintTextColor(0xffb0b5c2);
        field.setBackground(background(0xfffafaff, 0xffe8e5f2)); field.setPadding(dp(13), dp(12), dp(13), dp(12));
        field.setGravity(Gravity.TOP | Gravity.START); field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | (multiline ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0)); return field;
    }
    private LinearLayout card(LinearLayout parent, int top) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(18), dp(20), dp(18), dp(20)); card.setBackground(background(Color.WHITE, 0xffe6e8ef)); add(parent, card, top); return card;
    }
    private LinearLayout row(LinearLayout parent, int top) { LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); add(parent, row, top); return row; }
    private void add(LinearLayout parent, View child, int top) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(top); parent.addView(child, params); }
    private void addWeighted(LinearLayout row, View child) { LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1); if (row.getChildCount() > 0) params.leftMargin = dp(10); row.addView(child, params); }
}
