package cc.carm.outsource.plugin.coreprotectaddon.utils;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class TimeFormatUtils {

    static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // covert duration to 00:00:00 format (hh:mm:ss)
    public static String duration(long durationMillis) {
        long totalSeconds = durationMillis / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        // if hours is 0, return mm:ss format
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }

    }

    public static String duration(Duration duration) {
        return duration(duration.toMillis());
    }

    // covert localdatetime to yyyy-MM-dd HH:mm:ss format
    public static String datetime(java.time.LocalDateTime dateTime) {
        return FORMATTER.format(dateTime);
    }

    public static @NotNull String datetime(long seconds) {
        // 基于系统时区，将秒时间戳转换为日期时间字符串
        return FORMATTER.format(java.time.Instant.ofEpochSecond(seconds)
                .atZone(ZoneId.systemDefault())
                .toLocalDateTime());
    }

    public static @Nullable Duration parse(@NotNull String input) {
        if (input.isEmpty()) return null;
        var matcher = java.util.regex.Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(mo|[ywdhms])")
                .matcher(input.toLowerCase(java.util.Locale.ROOT));
        java.math.BigDecimal seconds = java.math.BigDecimal.ZERO;
        int end = 0;
        try {
            while (matcher.find()) {
                if (matcher.start() != end) return null;
                long multiplier = switch (matcher.group(2)) {
                    case "y" -> 31536000; case "mo" -> 2592000; case "w" -> 604800;
                    case "d" -> 86400; case "h" -> 3600; case "m" -> 60; default -> 1;
                };
                seconds = seconds.add(new java.math.BigDecimal(matcher.group(1)).multiply(java.math.BigDecimal.valueOf(multiplier)));
                end = matcher.end();
            }
            if (end != input.length()) return null;
            return Duration.ofMillis(seconds.multiply(java.math.BigDecimal.valueOf(1000)).longValueExact());
        } catch (ArithmeticException ex) {
            return null;
        }
    }

    public static @Nullable Duration[] parseInterval(@NotNull String input) {
        String[] parts = input.split("-", -1);
        if (parts.length > 2) {
            return null; // Invalid format, more than one '-'
        }

        Duration duration1;
        Duration duration2;

        if (parts.length == 1) {
            duration1 = parse(parts[0]);
            duration2 = Duration.ZERO; // Represents "now"
        } else { // parts.length == 2
            duration1 = parse(parts[0]);
            duration2 = parse(parts[1]);
        }

        if (duration1 == null || duration2 == null) {
            return null; // one of the parts is invalid
        }

        return new Duration[]{duration1, duration2};
    }

}
