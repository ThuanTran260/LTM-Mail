package com.ltm.mail;

import com.ltm.mail.client.EmailMessageBuilder;
import com.ltm.mail.client.SmtpClient;
import com.ltm.mail.config.AppConfig;
import com.ltm.mail.config.ConfigLoader;
import com.ltm.mail.config.SmtpConfig;
import com.ltm.mail.ui.MailAppFrame;
import com.ltm.mail.util.LoggerUtil;

import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.awt.GraphicsEnvironment;

/**
 * Điểm khởi chạy ứng dụng SMTP Client Java:
 * - Mặc định mở giao diện đồ họa Desktop (Modern Outlook Style) khi chạy không đối số.
 * - Vẫn giữ nguyên 100% chế độ CLI khi truyền tham số file cấu hình hoặc flag --cli.
 */
public class Main {

    public static void main(String[] args) {
        try {
            System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(System.err, true, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }

        boolean forceCli = false;
        String configFile = "appsettings.json";

        if (args.length > 0) {
            if ("--cli".equalsIgnoreCase(args[0])) {
                forceCli = true;
                if (args.length > 1) {
                    configFile = args[1];
                }
            } else if ("--gui".equalsIgnoreCase(args[0])) {
                forceCli = false;
                if (GraphicsEnvironment.isHeadless()) {
                    System.err.println("Lỗi: Không thể khởi chạy giao diện GUI trong môi trường headless (không có màn hình hiển thị).");
                    System.exit(1);
                }
            } else {
                // Tương thích ngược: Tham số truyền vào là file cấu hình
                forceCli = true;
                configFile = args[0];
            }
        }

        if (!forceCli && !GraphicsEnvironment.isHeadless()) {
            // Khởi chạy Giao diện đồ họa Desktop FlatLaf
            MailAppFrame.launchGui();
        } else {
            // Chạy chế độ dòng lệnh (CLI)
            runCli(configFile);
        }
    }

    /**
     * Chế độ thực thi dòng lệnh (CLI) truyền thống
     */
    public static void runCli(String configFile) {
        printBanner();

        try {
            LoggerUtil.info("=== BẮT ĐẦU TIẾN TRÌNH GỬI EMAIL (SMTP CLIENT) ===");
            LoggerUtil.info("1. Đang nạp cấu hình từ: " + configFile);

            AppConfig appConfig = ConfigLoader.loadConfig(configFile);
            SmtpConfig smtpConfig = appConfig.getSmtp();
            ConfigLoader.validateConfig(smtpConfig, true);

            // Hiển thị thông tin tổng quan cấu hình
            LoggerUtil.info("   - Máy chủ SMTP: " + smtpConfig.getHost() + ":" + smtpConfig.getPort());
            LoggerUtil.info("   - Bảo mật SSL/TLS: " + smtpConfig.isEnableSsl() + " (Auth: " + smtpConfig.getAuthType() + ")");
            LoggerUtil.info("   - Người gửi (From): " + smtpConfig.getFrom());
            LoggerUtil.info("   - Người nhận (To): " + smtpConfig.getTo());
            if (smtpConfig.getCc() != null && !smtpConfig.getCc().trim().isEmpty()) {
                LoggerUtil.info("   - Đồng kính gửi (CC): " + smtpConfig.getCc());
            }
            if (smtpConfig.getBcc() != null && !smtpConfig.getBcc().trim().isEmpty()) {
                LoggerUtil.info("   - Kính gửi ẩn (BCC): " + smtpConfig.getBcc());
            }
            LoggerUtil.info("   - Chủ đề (Subject): " + smtpConfig.getSubject());
            LoggerUtil.info("   - Số file đính kèm: " + (smtpConfig.getAttachmentPaths() != null ? smtpConfig.getAttachmentPaths().size() : 0));

            // 2. Khởi tạo Client & Session
            LoggerUtil.info("2. Khởi tạo phiên kết nối SMTP (Session)...");
            SmtpClient client = new SmtpClient(smtpConfig);
            Session session = client.createSession();

            // 3. Xây dựng nội dung Email MIME multipart/mixed
            LoggerUtil.info("3. Tạo thông điệp MIME multipart/mixed (HTML body + Attachments)...");
            MimeMessage message = EmailMessageBuilder.buildMessage(session, smtpConfig);

            // 4. Gửi email với cơ chế Retry
            LoggerUtil.info("4. Tiến hành gửi email và kích hoạt cơ chế Retry tự động...");
            client.sendWithRetry(session, message);

            LoggerUtil.info("=== KẾT THÚC TIẾN TRÌNH THÀNH CÔNG ===");

        } catch (Exception e) {
            LoggerUtil.error("=== TIẾN TRÌNH THẤT BẠI ===", e);
            printTroubleshootingGuide(e);
            System.exit(1);
        }
    }

    private static void printBanner() {
        System.out.println("=====================================================================");
        System.out.println("      HOÀN THIỆN SMTP CLIENT JAVA - GỬI EMAIL CÓ ĐÍNH KÈM FILE     ");
        System.out.println("   (SSL/TLS + AUTH LOGIN/PLAIN + MIME multipart/mixed + RETRY + LOG) ");
        System.out.println("=====================================================================");
    }

    private static void printTroubleshootingGuide(Exception e) {
        System.err.println("\n--- HƯỚNG DẪN XỬ LÝ SỰ CỐ ---");
        String errorMsg = (e.getMessage() != null) ? e.getMessage() : "";

        if (e instanceof javax.mail.AuthenticationFailedException || errorMsg.contains("535")) {
            System.err.println("[!] Lỗi 535: Xác thực không thành công.");
            System.err.println("  -> Với tài khoản Gmail: Hãy bật Xác minh 2 bước và tạo 'Mật khẩu ứng dụng' (App Password).");
            System.err.println("  -> Điền mã 16 ký tự vào trường 'Password' trong file appsettings.json.");
        } else if (e instanceof java.net.UnknownHostException) {
            System.err.println("[!] Lỗi mạng / DNS: Không tìm thấy host.");
            System.err.println("  -> Hãy kiểm tra kết nối Internet hoặc kiểm tra lại tên Host (ví dụ: smtp.gmail.com).");
        } else if (e instanceof javax.net.ssl.SSLHandshakeException) {
            System.err.println("[!] Lỗi SSL Handshake: Không thể thiết lập kênh bảo mật SSL/TLS.");
            System.err.println("  -> Kiểm tra Port (465 cho SSL hoặc 587 cho STARTTLS).");
        } else {
            System.err.println("[!] Chi tiết lỗi: " + errorMsg);
            System.err.println("  -> Vui lòng kiểm tra lại file appsettings.json và log tại 'smtp-client.log'.");
        }
        System.err.println("-----------------------------\n");
    }
}
