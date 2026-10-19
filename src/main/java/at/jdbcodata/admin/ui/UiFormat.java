package at.jdbcodata.admin.ui;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Formatting helpers for the templates, used as {@code ${@fmt.ago(...)}}. */
@Component("fmt")
public class UiFormat {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ENGLISH).withZone(ZoneId.systemDefault());

    /** Relative time, e.g. "12 min ago", "yesterday", "3 days ago"; dates older than a month as date. */
    public String ago(Instant t) {
        return ago(t, Instant.now());
    }

    static String ago(Instant t, Instant now) {
        if (t == null) return "–";
        long s = Math.max(0, Duration.between(t, now).getSeconds());
        if (s < 60) return "just now";
        if (s < 3600) return s / 60 + " min ago";
        if (s < 86_400) return s / 3600 + " h ago";
        long days = s / 86_400;
        if (days == 1) return "yesterday";
        if (days < 31) return days + " days ago";
        return DATE.format(t);
    }

    /** SVG polyline points scaled into a {@code width} x {@code height} box (2px inset vertically). */
    public String spark(List<? extends Number> values, int width, int height) {
        if (values == null || values.isEmpty()) return "";
        double max = values.stream().mapToDouble(Number::doubleValue).max().orElse(0);
        double min = values.stream().mapToDouble(Number::doubleValue).min().orElse(0);
        double range = max - min == 0 ? 1 : max - min;
        StringBuilder sb = new StringBuilder();
        int n = values.size();
        for (int i = 0; i < n; i++) {
            double x = n == 1 ? width : (double) i * width / (n - 1);
            double y = max == min ? height / 2.0 : height - 2 - (values.get(i).doubleValue() - min) / range * (height - 4);
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.ROOT, "%.1f,%.1f", x, y));
        }
        return sb.toString();
    }

    /** Bar heights in pixels (at least 2) for a mini bar chart of {@code height} pixels. */
    public List<Integer> bars(List<Long> values, int height) {
        long max = values == null ? 0 : values.stream().mapToLong(Long::longValue).max().orElse(0);
        List<Integer> result = new ArrayList<>();
        if (values == null) return result;
        for (Long v : values) {
            result.add(max == 0 || v == 0 ? 2 : (int) Math.max(3, Math.round((double) v / max * height)));
        }
        return result;
    }

    /** Thousands with a comma. */
    public String num(long n) {
        return String.format(Locale.ENGLISH, "%,d", n);
    }

    /** Ratio 0..1 as percent with {@code digits} decimals, e.g. "63%" or "0.4%". */
    public String pct(double ratio, int digits) {
        return String.format(Locale.ENGLISH, "%." + digits + "f%%", ratio * 100);
    }

    /** Initial for the avatar. */
    public String initial(String name) {
        return name == null || name.isBlank() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT);
    }
}
