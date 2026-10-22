package com.ltm.mail.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Tiện ích ghi log theo chuẩn Slide 30:
 * Định dạng: [yyyy-MM-dd HH:mm:ss] [LEVEL] Message
 * Ghi đồng thời ra Console và file smtp-client.log
 */
public class LoggerUtil {

    private static final String LOG_FILE = "smtp-client.log";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static synchronized void log(String level, String message, Exception ex) {
        String time = LocalDateTime.now().format(FORMATTER);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[%s] [%s] %s", time, level, message));

        if (ex != null) {
            sb.append(System.lineSeparator())
              .append("  ↳ ").append(ex.getClass().getSimpleName()).append(": ").append(ex.getMessage());
            
            // Nếu có nguyên nhân lồng ghép (cause)
            Throwable cause = ex.getCause();
            if (cause != null && cause != ex) {
                sb.append(System.lineSeparator())
                  .append("    Caused by: ").append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
            }
        }

        String logOutput = sb.toString();

        // 1. In ra Console
        if ("ERROR".equalsIgnoreCase(level)) {
            System.err.println(logOutput);
        } else {
            System.out.println(logOutput);
        }

        // 2. Ghi nối tiếp vào file smtp-client.log
        try {
            Path path = Paths.get(LOG_FILE);
            String lineToAppend = logOutput + System.lineSeparator();
            Files.writeString(
                    path,
                    lineToAppend,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            System.err.println("Không thể ghi log vào file " + LOG_FILE + ": " + e.getMessage());
        }
    }

    public static void info(String message) {
        log("INFO", message, null);
    }

    public static void warn(String message) {
        log("WARN", message, null);
    }

    public static void error(String message) {
        log("ERROR", message, null);
    }

    public static void error(String message, Exception ex) {
        log("ERROR", message, ex);
    }
}
