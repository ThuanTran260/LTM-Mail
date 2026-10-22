package com.ltm.mail.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ltm.mail.util.LoggerUtil;

import com.ltm.mail.client.EmailMessageBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Trình tải và phân tích cấu hình từ file JSON (appsettings.json)
 * Hỗ trợ nạp / ghi đè thông tin xác thực từ Environment Variables (SEC-06)
 */
public class ConfigLoader {

    private static final String DEFAULT_CONFIG_PATH = "appsettings.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static AppConfig loadConfig() throws Exception {
        return loadConfig(DEFAULT_CONFIG_PATH);
    }

    public static AppConfig loadConfig(String filePath) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            if (DEFAULT_CONFIG_PATH.equals(filePath)) {
                LoggerUtil.warn("Không tìm thấy file cấu hình '" + filePath + "'. Kiểm tra appsettings.example.json...");
                File exampleFile = new File("appsettings.example.json");
                if (exampleFile.exists()) {
                    file = exampleFile;
                    LoggerUtil.info("Sử dụng tạm file mẫu: appsettings.example.json");
                } else {
                    throw new IllegalStateException("Không tìm thấy file cấu hình: " + filePath);
                }
            } else {
                throw new IllegalStateException("Không tìm thấy file cấu hình được chỉ định: " + filePath);
            }
        }

        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            AppConfig config = GSON.fromJson(reader, AppConfig.class);
            if (config == null || config.getSmtp() == null) {
                throw new IllegalArgumentException("Nội dung file cấu hình không hợp lệ: thiếu mục 'Smtp'.");
            }

            // SEC-06: Đọc / ghi đè mật khẩu từ biến môi trường SMTP_PASSWORD
            applyEnvironmentOverrides(config.getSmtp());

            validateConfig(config.getSmtp());
            return config;
        }
    }

    /**
     * SEC-06: Hỗ trợ nạp / ghi đè thông tin xác thực từ Environment Variables hoặc System Properties
     * để tránh phải lưu trữ mật khẩu ở dạng plain-text trên đĩa cứng.
     */
    public static void applyEnvironmentOverrides(SmtpConfig smtp) {
        applyEnvironmentOverrides(smtp, System.getenv());
    }

    public static void applyEnvironmentOverrides(SmtpConfig smtp, Map<String, String> env) {
        if (smtp == null) {
            return;
        }

        // 1. Kiểm tra Environment Variables
        if (env != null) {
            String envPassword = env.get("SMTP_PASSWORD");
            if (envPassword != null && !envPassword.trim().isEmpty()) {
                smtp.setPassword(envPassword.trim());
                LoggerUtil.info("Đã nạp / ghi đè mật khẩu SMTP từ biến môi trường SMTP_PASSWORD.");
            }

            String envUsername = env.get("SMTP_USERNAME");
            if (envUsername != null && !envUsername.trim().isEmpty()) {
                smtp.setUsername(envUsername.trim());
                LoggerUtil.info("Đã nạp / ghi đè tài khoản SMTP từ biến môi trường SMTP_USERNAME.");
            }
        }

        // 2. Fallback: Kiểm tra System Properties
        String sysPassword = System.getProperty("smtp.password");
        if ((smtp.getPassword() == null || smtp.getPassword().trim().isEmpty() || "your_app_password_here".equals(smtp.getPassword()))
                && sysPassword != null && !sysPassword.trim().isEmpty()) {
            smtp.setPassword(sysPassword.trim());
            LoggerUtil.info("Đã nạp mật khẩu SMTP từ System Property 'smtp.password'.");
        }

        String sysUsername = System.getProperty("smtp.username");
        if ((smtp.getUsername() == null || smtp.getUsername().trim().isEmpty())
                && sysUsername != null && !sysUsername.trim().isEmpty()) {
            smtp.setUsername(sysUsername.trim());
        }
    }

    public static void validateConfig(SmtpConfig smtp) {
        if (smtp.getHost() == null || smtp.getHost().trim().isEmpty()) {
            throw new IllegalArgumentException("Thiếu cấu hình SMTP Host (Smtp.Host)!");
        }
        if (smtp.getPort() <= 0) {
            throw new IllegalArgumentException("Cổng SMTP Port không hợp lệ: " + smtp.getPort());
        }
        if (smtp.getUsername() == null || smtp.getUsername().trim().isEmpty()) {
            throw new IllegalArgumentException("Thiếu thông tin tài khoản SMTP Username (Smtp.Username)!");
        }
        if (smtp.getPassword() == null || smtp.getPassword().trim().isEmpty()) {
            throw new IllegalArgumentException("Thiếu mật khẩu SMTP Password (Smtp.Password)!");
        }
        if (smtp.getFrom() == null || smtp.getFrom().trim().isEmpty()) {
            smtp.setFrom(smtp.getUsername());
        }
        if (smtp.getTo() == null || smtp.getTo().trim().isEmpty()) {
            throw new IllegalArgumentException("Thiếu địa chỉ email người nhận (Smtp.To)!");
        }

        // SEC-04: Ngăn chặn CRLF Injection ngay từ tầng thẩm định cấu hình
        EmailMessageBuilder.validateHeaderValue("Host", smtp.getHost());
        EmailMessageBuilder.validateHeaderValue("Username", smtp.getUsername());
        EmailMessageBuilder.validateHeaderValue("From", smtp.getFrom());
        EmailMessageBuilder.validateHeaderValue("To", smtp.getTo());
        if (smtp.getSubject() != null) {
            EmailMessageBuilder.validateHeaderValue("Subject", smtp.getSubject());
        }
    }
}
