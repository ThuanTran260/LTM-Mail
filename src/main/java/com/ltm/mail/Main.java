package com.ltm.mail;

import com.ltm.mail.client.EmailMessageBuilder;
import com.ltm.mail.client.SmtpClient;
import com.ltm.mail.config.AppConfig;
import com.ltm.mail.config.ConfigLoader;
import com.ltm.mail.config.SmtpConfig;
import com.ltm.mail.util.LoggerUtil;

import javax.mail.Session;
import javax.mail.internet.MimeMessage;

/**
 * Điểm khởi chạy ứng dụng SMTP Client Java:
 * Quản lý vòng đời: Đọc cấu hình -> Tạo Session -> Tạo MimeMessage -> Gửi & Retry -> Ghi log
 */
public class Main {

    public static void main(String[] args) {
        try {
            System.setOut(new java.io.PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8));
            System.setErr(new java.io.PrintStream(System.err, true, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }

        printBanner();

        String configFile = (args.length > 0) ? args[0] : "appsettings.json";

        try {
            LoggerUtil.info("=== BẮT ĐẦU TIẾN TRÌNH GỬI EMAIL (SMTP CLIENT) ===");
            LoggerUtil.info("1. Đang nạp cấu hình từ: " + configFile);

            AppConfig appConfig = ConfigLoader.loadConfig(configFile);
            SmtpConfig smtpConfig = appConfig.getSmtp();

            // Hiển thị thông tin tổng quan cấu hình
            LoggerUtil.info("   - Máy chủ SMTP: " + smtpConfig.getHost() + ":" + smtpConfig.getPort());
            LoggerUtil.info("   - Bảo mật SSL/TLS: " + smtpConfig.isEnableSsl() + " (Auth: " + smtpConfig.getAuthType() + ")");
            LoggerUtil.info("   - Người gửi (From): " + smtpConfig.getFrom());
            LoggerUtil.info("   - Người nhận (To): " + smtpConfig.getTo());
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
