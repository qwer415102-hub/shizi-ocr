package cn.shizi.ocr;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** HTTPS translation only. OCR never calls this class. */
public final class Translator {
    public interface Progress { void update(int done, int total); }
    public String translate(String text, Progress progress) throws Exception {
        StringBuilder output = new StringBuilder();
        var chunks = TextTools.chunks(text);
        int done = 0;
        for (String part : chunks) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("已取消翻译。");
            if (!TextTools.hasEnglish(part)) output.append(part);
            else {
                String query = URLEncoder.encode(part, "UTF-8");
                URL url = new URL("https://api.mymemory.translated.net/get?q=" + query + "&langpair=en%7Czh-CN");
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(25000);
                try {
                    if (connection.getResponseCode() != 200) throw new IOException("翻译服务暂时不可用，请稍后重试。");
                    try (InputStream input = connection.getInputStream(); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                        byte[] bytes = new byte[4096]; int read;
                        while ((read = input.read(bytes)) != -1) {
                            if (buffer.size() + read > 1_000_000) throw new IOException("翻译服务返回异常。");
                            buffer.write(bytes, 0, read);
                        }
                        JSONObject payload = new JSONObject(buffer.toString("UTF-8"));
                        if (payload.optInt("responseStatus") != 200 || payload.optBoolean("quotaFinished"))
                            throw new IOException("翻译服务额度已用完或暂时不可用，请稍后重试。");
                        String value = payload.getJSONObject("responseData").getString("translatedText");
                        if (value.trim().isEmpty()) throw new IOException("翻译服务返回空结果，请稍后重试。");
                        output.append(android.text.Html.fromHtml(value).toString());
                    }
                } finally { connection.disconnect(); }
            }
            if (++done < chunks.size()) output.append('\n');
            progress.update(done, chunks.size());
        }
        return output.toString();
    }
}
