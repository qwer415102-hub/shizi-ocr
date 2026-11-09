package cn.shizi.ocr;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Platform-independent document validation and safe translation segmentation. */
public final class TextTools {
    private TextTools() {}
    public static List<Integer> pages(String spec, int total) {
        TreeSet<Integer> chosen = new TreeSet<>();
        if (spec.trim().isEmpty()) {
            if (total > 100) throw new IllegalArgumentException("PDF 超过 100 页，请填写页码范围，每次最多识别 100 页。");
            for (int i = 0; i < total; i++) chosen.add(i);
        } else {
            for (String part : spec.replace('，', ',').split(",", -1)) {
                Matcher m = Pattern.compile("\\s*(\\d+)(?:\\s*-\\s*(\\d+))?\\s*").matcher(part);
                if (!m.matches()) throw new IllegalArgumentException("页码格式不正确，请使用 1-5,8。");
                int first, last;
                try {
                    first = Integer.parseInt(m.group(1));
                    last = m.group(2) == null ? first : Integer.parseInt(m.group(2));
                } catch (NumberFormatException e) { throw new IllegalArgumentException("页码超出范围。"); }
                if (first < 1 || last < first || last > total) throw new IllegalArgumentException("页码必须在 1 到 " + total + " 之间。");
                if ((long) last - first >= 100) throw new IllegalArgumentException("每次最多识别 100 页。");
                for (int i = first; i <= last; i++) chosen.add(i - 1);
                if (chosen.size() > 100) throw new IllegalArgumentException("每次最多识别 100 页。");
            }
        }
        return new ArrayList<>(chosen);
    }

    public static List<String> chunks(String text) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = start, bytes = 0, boundary = -1;
            while (end < text.length()) {
                int point = text.codePointAt(end);
                int width = Character.charCount(point);
                int size = new String(Character.toChars(point)).getBytes(StandardCharsets.UTF_8).length;
                if (bytes + size > 450) break;
                bytes += size;
                end += width;
                if (Character.isWhitespace(point) || point == '.' || point == '。') boundary = end;
            }
            if (end < text.length() && boundary > start + (end - start) / 2) end = boundary;
            chunks.add(text.substring(start, end));
            start = end;
        }
        return chunks;
    }

    public static boolean hasEnglish(String text) { return Pattern.compile("[A-Za-z]{2,}").matcher(text).find(); }
}
