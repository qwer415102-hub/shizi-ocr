package cn.shizi.ocr;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import androidx.exifinterface.media.ExifInterface;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** All image/PDF processing is on-device; the model is bundled in the APK. */
public final class OcrProcessor implements AutoCloseable {
    public static final class Page {
        public final int number;
        public String text, translation = "";
        public Page(int number, String text) { this.number = number; this.text = text; }
    }
    public interface Progress { void update(int done, int total, int originalPage); }
    private final TextRecognizer recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());

    public List<Page> recognize(File source, boolean pdf, String range, Progress progress) throws Exception {
        List<Page> result = new ArrayList<>();
        if (pdf) {
            try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(descriptor)) {
                List<Integer> pages = TextTools.pages(range, renderer.getPageCount());
                for (int index : pages) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("识别已取消。");
                    progress.update(result.size(), pages.size(), index + 1);
                    Bitmap bitmap = render(renderer, index, 2600);
                    try { result.add(new Page(index + 1, recognize(bitmap))); }
                    finally { bitmap.recycle(); }
                }
            }
        } else {
            progress.update(0, 1, 1);
            Bitmap bitmap = decode(source, 2600);
            try { result.add(new Page(1, recognize(bitmap))); }
            finally { bitmap.recycle(); }
        }
        return result;
    }

    private String recognize(Bitmap bitmap) throws Exception {
        return Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).getText();
    }

    public static Bitmap preview(File source, boolean pdf, int page) throws IOException {
        if (!pdf) return decode(source, 1000);
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            return render(renderer, page, 1000);
        }
    }

    private static Bitmap render(PdfRenderer renderer, int index, int maxSide) {
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            float scale = Math.min(3.5f, (float) maxSide / Math.max(page.getWidth(), page.getHeight()));
            int width = Math.max(1, Math.round(page.getWidth() * scale));
            int height = Math.max(1, Math.round(page.getHeight() * scale));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        }
    }

    private static Bitmap decode(File source, int maxSide) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(source.getAbsolutePath(), options);
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("无法读取图片，请选择 JPG、PNG、WEBP 等系统支持的图片。");
        options.inSampleSize = 1;
        while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > maxSide * 1.5) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap decoded = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
        if (decoded == null) throw new IOException("图片无法解码，请尝试另一张图片。");
        int rotation = 0; boolean mirror = false;
        try {
            ExifInterface exif = new ExifInterface(source);
            rotation = exif.getRotationDegrees(); mirror = exif.isFlipped();
        } catch (IOException ignored) { /* Images without EXIF have normal orientation. */ }
        Matrix matrix = new Matrix();
        if (mirror) matrix.postScale(-1, 1);
        if (rotation != 0) matrix.postRotate(rotation);
        Bitmap oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
        if (oriented != decoded) decoded.recycle();
        // Composite transparency against white before OCR.
        Bitmap rgb = Bitmap.createBitmap(oriented.getWidth(), oriented.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(rgb); canvas.drawColor(Color.WHITE); canvas.drawBitmap(oriented, 0, 0, null);
        oriented.recycle();
        return rgb;
    }

    @Override public void close() { recognizer.close(); }
}
