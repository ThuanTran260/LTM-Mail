package com.ltm.mail.client;

import com.ltm.mail.config.RetryConfig;
import com.ltm.mail.config.SmtpConfig;
import com.ltm.mail.util.ExceptionClassifier;
import com.ltm.mail.util.LoggerUtil;

import javax.mail.Address;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.MimeMessage;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

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
        return createSession(this.config);
    }

    /**
     * Khởi tạo Session JavaMail với các thuộc tính bảo mật SSL/TLS & Timeout
     */
    public Session createSession(SmtpConfig config) {
        Properties props = new Properties();

        // 1. Cấu hình máy chủ SMTP & Cổng
        props.put("mail.smtp.host", config.getHost());
        props.put("mail.smtp.port", String.valueOf(config.getPort()));
        props.put("mail.smtp.auth", "true");

        // 2. Cấu hình bảo mật SSL/TLS & Hostname Verification (SEC-01, SEC-02, SEC-03)
        // SEC-01: Bắt buộc xác thực danh tính máy chủ (phòng chống tấn công MitM - RFC 2595 / RFC 6125)
        props.put("mail.smtp.ssl.checkserveridentity", "true");

        // SEC-02: Hỗ trợ cả TLSv1.2 và TLSv1.3
        props.put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3");

        if (config.isEnableSsl()) {
            // SMTPS (Port 465)
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.socketFactory.port", String.valueOf(config.getPort()));
            props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
            props.put("mail.smtp.socketFactory.fallback", "false");
        } else {
            // STARTTLS (Port 587)
            props.put("mail.smtp.ssl.enable", "false");
            props.put("mail.smtp.starttls.enable", "true");
            // SEC-03: Bắt buộc dùng STARTTLS để chống tấn công hạ cấp STRIPTLS
            props.put("mail.smtp.starttls.required", "true");
        }

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

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    public interface SmtpProgressListener {
        void onProgress(int percent, String message);
    }

    @FunctionalInterface
    public interface RetryCountdownListener {
        void onRetry(int attempt, int maxRetry, long delayMillis, String reason);
    }

    private Sleeper sleeper = Thread::sleep;
    private SmtpProgressListener progressListener;
    private RetryCountdownListener retryCountdownListener;

    public void setSleeper(Sleeper sleeper) {
        this.sleeper = sleeper != null ? sleeper : Thread::sleep;
    }

    public void setProgressListener(SmtpProgressListener listener) {
        this.progressListener = listener;
    }

    public void setRetryCountdownListener(RetryCountdownListener listener) {
        this.retryCountdownListener = listener;
    }

    /**
     * Kiểm tra kết nối và xác thực tới máy chủ SMTP
     */
    public static void testConnection(SmtpConfig config) throws Exception {
        if (config == null) {
            throw new IllegalArgumentException("Cấu hình SMTP không được để null!");
        }
        if (config.getHost() == null || config.getHost().trim().isEmpty()) {
            throw new IllegalArgumentException("Thiếu cấu hình SMTP Host!");
        }
        if (config.getPort() <= 0) {
            throw new IllegalArgumentException("Cổng SMTP Port không hợp lệ: " + config.getPort());
        }
        if (config.getUsername() == null || config.getUsername().trim().isEmpty()) {
            throw new IllegalArgumentException("Thiếu tài khoản SMTP Username!");
        }
        if (config.getPassword() == null || config.getPassword().trim().isEmpty()) {
            throw new IllegalArgumentException("Thiếu mật khẩu SMTP Password!");
        }
        SmtpClient client = new SmtpClient(config);
        Session session = client.createSession();
        Transport transport = session.getTransport("smtp");
        try {
            LoggerUtil.info("Đang kiểm tra kết nối & xác thực với máy chủ SMTP " + config.getHost() + ":" + config.getPort() + "...");
            transport.connect(config.getHost(), config.getPort(), config.getUsername(), config.getPassword());
            LoggerUtil.info("✔ Kiểm tra kết nối & xác thực SMTP thành công!");
        } finally {
            if (transport != null) {
                try {
                    transport.close();
                    LoggerUtil.info("Đã đóng kết nối kiểm tra an toàn.");
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * REL-06: Gửi Email trực tiếp qua Transport và giải phóng socket an toàn trong khối finally
     */
    public void sendEmail(Session session, MimeMessage message) throws MessagingException {
        if (message == null) {
            throw new IllegalArgumentException("Thông điệp email không được để null!");
        }

        Address[] recipients = message.getAllRecipients();
        if (recipients == null || recipients.length == 0) {
            throw new MessagingException("Không tìm thấy địa chỉ người nhận hợp lệ (To/CC/BCC).");
        }

        if (progressListener != null) {
            progressListener.onProgress(25, "Đang kết nối tới máy chủ SMTP " + config.getHost() + ":" + config.getPort() + "...");
        }

        Transport transport = session.getTransport("smtp");
        try {
            LoggerUtil.info("Đang kết nối tới máy chủ SMTP: " + config.getHost() + "...");
            transport.connect();
            if (progressListener != null) {
                progressListener.onProgress(50, "Kết nối & xác thực thành công. Đang gửi dữ liệu email (SMTP DATA)...");
            }
            LoggerUtil.info("Kết nối và xác thực thành công. Đang gửi dữ liệu email (SMTP DATA)...");

            transport.sendMessage(message, recipients);
            if (progressListener != null) {
                progressListener.onProgress(85, "Server đã nhận dữ liệu (250 OK). Đang hoàn tất phiên gửi...");
            }
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
     * REL-05: Tính toán thời gian chờ theo Exponential Backoff có Jitter và chống tràn số:
     * - Cố định toán tử dịch bit shift operand tối đa 30 để phòng chống bit-shift overflow.
     * - Randomized Jitter (lên đến 20% hoặc 1000ms) để phòng chống hiện tượng Thundering Herd.
     * - Nghiêm ngặt áp dụng trần maxDelaySec (delay không bao giờ vượt quá maxDelaySec).
     */
    public static long calculateBackoffWithJitterMillis(int attempt, int baseDelaySec, int maxDelaySec) {
        int safeBase = Math.max(1, baseDelaySec);
        int safeMax = Math.max(safeBase, maxDelaySec);
        int safeAttempt = Math.max(1, attempt);

        int shift = Math.min(safeAttempt - 1, 30);
        long rawBackoffMillis = (long) safeBase * (1L << shift) * 1000L;
        long maxDelayMillis = (long) safeMax * 1000L;

        long cappedBackoffMillis = Math.min(rawBackoffMillis, maxDelayMillis);
        long maxJitter = Math.min(1000L, Math.max(1L, cappedBackoffMillis / 5));
        long jitter = ThreadLocalRandom.current().nextLong(0, maxJitter + 1);

        long totalDelayMillis = Math.min(cappedBackoffMillis + jitter, maxDelayMillis);
        return Math.max(1000L, totalDelayMillis);
    }

    /**
     * Overload cho phép chỉ định jitter xác định (deterministic) phục vụ unit tests
     */
    public static long calculateBackoffWithJitterMillis(int attempt, int baseDelaySec, int maxDelaySec, long jitterMillis) {
        int safeBase = Math.max(1, baseDelaySec);
        int safeMax = Math.max(safeBase, maxDelaySec);
        int safeAttempt = Math.max(1, attempt);

        int shift = Math.min(safeAttempt - 1, 30);
        long rawBackoffMillis = (long) safeBase * (1L << shift) * 1000L;
        long maxDelayMillis = (long) safeMax * 1000L;

        long cappedBackoffMillis = Math.min(rawBackoffMillis, maxDelayMillis);
        long safeJitter = Math.max(0, jitterMillis);

        long totalDelayMillis = Math.min(cappedBackoffMillis + safeJitter, maxDelayMillis);
        return Math.max(1000L, totalDelayMillis);
    }

    /**
     * Gửi email kèm cơ chế Retry với thuật toán Exponential Backoff (Slide 30 - Mục 6 & REL-05)
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
                Address[] allRecipients = message.getAllRecipients();
                String recDesc = (allRecipients != null && allRecipients.length > 0)
                        ? java.util.Arrays.toString(allRecipients)
                        : (config.getTo() != null ? config.getTo() : "người nhận");
                if (progressListener != null) {
                    progressListener.onProgress(100, "Gửi email thành công tới " + recDesc);
                }
                LoggerUtil.info("✔ GỬI EMAIL THÀNH CÔNG TỚI: " + recDesc);
                return;
            } catch (Exception ex) {
                attempt++;
                boolean retryable = ExceptionClassifier.isRetryable(ex);

                if (retryable && attempt <= maxRetry) {
                    long delayMillis = calculateBackoffWithJitterMillis(attempt, baseDelaySec, maxDelaySec);
                    LoggerUtil.warn(String.format("⚠ Lỗi tạm thời khi gửi email. Thử lại sau %d ms (Lần %d/%d). Nguyên nhân: %s",
                            delayMillis, attempt, maxRetry, ex.getMessage()));

                    if (retryCountdownListener != null) {
                        retryCountdownListener.onRetry(attempt, maxRetry, delayMillis, ex.getMessage());
                    }

                    try {
                        this.sleeper.sleep(delayMillis);
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

    /**
     * Interface contract: Gửi email kèm cấu hình chỉ định và đối tượng Message
     */
    public void sendWithRetry(SmtpConfig customConfig, Message message) throws Exception {
        Session session = createSession(customConfig);
        if (message instanceof MimeMessage) {
            sendWithRetry(session, (MimeMessage) message);
        } else {
            throw new IllegalArgumentException("Chỉ hỗ trợ MimeMessage!");
        }
    }

    /**
     * Interface contract: Gửi email với Message sử dụng cấu hình mặc định của client
     */
    public void sendWithRetry(Message message) throws Exception {
        Session session = createSession(this.config);
        if (message instanceof MimeMessage) {
            sendWithRetry(session, (MimeMessage) message);
        } else {
            throw new IllegalArgumentException("Chỉ hỗ trợ MimeMessage!");
        }
    }
}
