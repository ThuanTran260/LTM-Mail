package com.ltm.mail.client;

import com.ltm.mail.config.RetryConfig;
import com.ltm.mail.config.SmtpConfig;
import com.ltm.mail.util.ExceptionClassifier;
import com.ltm.mail.util.LoggerUtil;

import javax.mail.Authenticator;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.MimeMessage;
import java.util.Properties;

/**
 * Lớp điều khiển SMTP Client theo chuẩn Slide 30:
 * - Kết nối SSL/TLS (SMTPS - 465) + STARTTLS (587)
 * - Xác thực AUTH LOGIN/PLAIN qua PasswordAuthentication
 * - Gửi SMTP DATA và giải phóng kết nối
 * - Cơ chế Retry với Exponential Backoff
 */
public class SmtpClient {

    private final SmtpConfig config;

    public SmtpClient(SmtpConfig config) {
        this.config = config;
    }

    /**
     * Khởi tạo Session JavaMail với các thuộc tính bảo mật SSL/TLS & Timeout
     */
    public Session createSession() {
        Properties props = new Properties();

        // 1. Cấu hình máy chủ SMTP & Cổng
        props.put("mail.smtp.host", config.getHost());
        props.put("mail.smtp.port", String.valueOf(config.getPort()));
        props.put("mail.smtp.auth", "true");

        // 2. Cấu hình bảo mật SSL/TLS
        if (config.isEnableSsl()) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.ssl.protocols", "TLSv1.2");
            props.put("mail.smtp.socketFactory.port", String.valueOf(config.getPort()));
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        } else {
            props.put("mail.smtp.ssl.enable", "false");
        }

        // Dự phòng STARTTLS (cho port 587 hoặc fallback)
        props.put("mail.smtp.starttls.enable", "true");

        // 3. Thời gian chờ kết nối và đọc (Timeout khuyến nghị tại Bảng 11)
        props.put("mail.smtp.connectiontimeout", "15000"); // 15 giây
        props.put("mail.smtp.timeout", "30000");           // 30 giây
        props.put("mail.smtp.writetimeout", "15000");      // 15 giây

        LoggerUtil.info("Khởi tạo SMTP Session: " + config.getHost() + ":" + config.getPort()
                + " [SSL=" + config.isEnableSsl() + ", AuthType=" + config.getAuthType() + "]");

        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.getUsername(), config.getPassword());
            }
        });
    }

    /**
     * Gửi Email trực tiếp qua Transport
     */
    public void sendEmail(Session session, MimeMessage message) throws MessagingException {
        Transport transport = session.getTransport("smtp");
        try {
            LoggerUtil.info("Đang kết nối tới máy chủ SMTP: " + config.getHost() + "...");
            transport.connect();
            LoggerUtil.info("Kết nối và xác thực thành công. Đang gửi dữ liệu email (SMTP DATA)...");
            
            transport.sendMessage(message, message.getAllRecipients());
            LoggerUtil.info("Server đã nhận dữ liệu (250 OK - Kết thúc DATA '\\r\\n.\\r\\n').");
        } finally {
            if (transport != null) {
                try {
                    transport.close();
                    LoggerUtil.info("Đã đóng kết nối Transport an toàn.");
                } catch (Exception e) {
                    LoggerUtil.warn("Lỗi khi đóng kết nối Transport: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Gửi email kèm cơ chế Retry với thuật toán Exponential Backoff (Slide 30 - Mục 6)
     */
    public void sendWithRetry(Session session, MimeMessage message) throws Exception {
        RetryConfig retryConfig = config.getRetry();
        int maxRetry = retryConfig != null ? retryConfig.getMaxRetry() : 3;
        int baseDelaySec = retryConfig != null ? retryConfig.getBaseDelaySeconds() : 2;
        int maxDelaySec = retryConfig != null ? retryConfig.getMaxDelaySeconds() : 60;

        int attempt = 0;
        while (true) {
            try {
                sendEmail(session, message);
                LoggerUtil.info("✔ GỬI EMAIL THÀNH CÔNG TỚI: " + config.getTo());
                return;
            } catch (Exception ex) {
                attempt++;
                boolean retryable = ExceptionClassifier.isRetryable(ex);

                if (retryable && attempt <= maxRetry) {
                    // Công thức Exponential Backoff: baseDelay * 2^(attempt - 1), capped at maxDelay
                    int delay = Math.min(baseDelaySec * (1 << (attempt - 1)), maxDelaySec);
                    LoggerUtil.warn(String.format("⚠ Lỗi tạm thời khi gửi email. Thử lại sau %ds (Lần %d/%d). Nguyên nhân: %s",
                            delay, attempt, maxRetry, ex.getMessage()));
                    
                    try {
                        Thread.sleep(delay * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Tiến trình retry bị ngắt quãng", ie);
                    }
                } else {
                    LoggerUtil.error(String.format("✖ Thất bại hoàn toàn sau %d lần thử. (Retryable: %b)", attempt, retryable), ex);
                    throw ex;
                }
            }
        }
    }
}
