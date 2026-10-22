package com.ltm.mail.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.ltm.mail.util.LoggerUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Trình tải và phân tích cấu hình từ file JSON (appsettings.json)
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
            validateConfig(config.getSmtp());
            return config;
        }
    }

    private static void validateConfig(SmtpConfig smtp) {
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
    }
}
