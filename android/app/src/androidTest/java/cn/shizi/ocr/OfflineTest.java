package cn.shizi.ocr;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.os.ParcelFileDescriptor;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import static org.junit.Assert.*;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.*;

@RunWith(AndroidJUnit4.class)
public class OfflineTest {
    @Rule public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class);

    @Before public void disableInternet() throws Exception {
        shell("svc wifi disable"); shell("svc data disable");
        // Emulator default Ethernet can remain present; disable its route too.
        shell("cmd connectivity airplane-mode enable");
        Thread.sleep(1500);
    }
    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor fd = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
             java.io.InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            byte[] buffer = new byte[1024]; while (in.read(buffer) != -1) {}
        }
    }
    private Bitmap sample() {
        Bitmap bitmap = Bitmap.createBitmap(1400, 480, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(Color.BLACK); paint.setTextSize(60);
        canvas.drawText("Image to Text", 60, 110, paint);
        canvas.drawText("图片文字识别测试", 60, 230, paint);
        canvas.drawText("Meeting starts tomorrow.", 60, 350, paint);
        return bitmap;
    }
    @Test public void bundledModelRecognizesEnglishAndChineseWithNoNetwork() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = File.createTempFile("offline-test", ".png", context.getCacheDir());
        Bitmap bitmap = sample();
        try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
        bitmap.recycle();
        try (OcrProcessor ocr = new OcrProcessor()) {
            List<OcrProcessor.Page> pages = ocr.recognize(file, false, "", (d, t, p) -> {});
            assertTrue(pages.get(0).text, pages.get(0).text.contains("Image to Text"));
            assertTrue(pages.get(0).text, pages.get(0).text.contains("图片文字识别测试"));
        } finally { file.delete(); }
    }
    @Test public void scannedPdfRecognizedOfflineWithSelectedPage() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = File.createTempFile("offline-pdf", ".pdf", context.getCacheDir());
        Bitmap bitmap = sample();
        PdfDocument document = new PdfDocument();
        try {
            for (int i = 1; i <= 2; i++) {
                PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(1400, 480, i).create());
                page.getCanvas().drawBitmap(bitmap, 0, 0, null); document.finishPage(page);
            }
            try (FileOutputStream out = new FileOutputStream(file)) { document.writeTo(out); }
        } finally { document.close(); }
        bitmap.recycle();
        try (OcrProcessor ocr = new OcrProcessor()) {
            List<OcrProcessor.Page> pages = ocr.recognize(file, true, "2", (d, t, p) -> {});
            assertEquals(1, pages.size()); assertEquals(2, pages.get(0).number);
            assertTrue(pages.get(0).text, pages.get(0).text.contains("Image to Text"));
        } finally { file.delete(); }
    }
    @Test public void offlineTranslationShowsNetworkSettingsAndPreservesSource() {
        String original = "The meeting starts at nine tomorrow.";
        onView(withId(R.id.source_text)).perform(scrollTo(), replaceText(original), closeSoftKeyboard());
        onView(withId(R.id.translate_button)).perform(scrollTo(), click());
        onView(withText("翻译需要开启网络")).check(matches(isDisplayed()));
        onView(withText("打开网络设置")).check(matches(isDisplayed()));
        onView(withText("稍后翻译")).perform(click());
        onView(withId(R.id.source_text)).check(matches(withText(original)));
    }
}
