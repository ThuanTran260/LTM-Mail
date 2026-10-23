package com.ltm.mail;

import com.ltm.mail.client.EmailMessageBuilder;
import com.ltm.mail.client.SmtpClient;
import com.ltm.mail.config.AppConfig;
import com.ltm.mail.config.ConfigLoader;
import com.ltm.mail.config.RetryConfig;
import com.ltm.mail.config.SmtpConfig;
import com.ltm.mail.ui.AttachmentItem;
import com.ltm.mail.ui.ThemeManager;
import com.ltm.mail.util.LoggerUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.mail.Message;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Kiểm thử tính năng Giao diện đồ họa (GUI), Lưu cấu hình, CC/BCC, và Bộ đếm")
public class GuiFeaturesTest {

    @Test
    @DisplayName("Lưu cấu hình JSON (saveConfig) và đọc lại khớp 100% dữ liệu")
    void testSaveAndLoadConfig(@TempDir Path tempDir) throws Exception {
        File configFile = tempDir.resolve("test_appsettings.json").toFile();

        SmtpConfig smtp = new SmtpConfig();
        smtp.setHost("smtp.testprovider.com");
        smtp.setPort(587);
        smtp.setEnableSsl(false);
        smtp.setAuthType("LOGIN");
        smtp.setUsername("testuser@testprovider.com");
        smtp.setPassword("secretPass123");
        smtp.setFrom("sender@testprovider.com");
        smtp.setTo("receiver@testprovider.com");
        smtp.setCc("cc_recipient@testprovider.com");
        smtp.setBcc("bcc_recipient@testprovider.com");
        smtp.setSubject("Tiêu đề kiểm thử lưu cấu hình");
        smtp.setBody("<p>Nội dung kiểm thử</p>");
        smtp.setRetry(new RetryConfig(4, 3, 45));

        AppConfig appConfig = new AppConfig(smtp);

        // Lưu file
        ConfigLoader.saveConfig(appConfig, configFile.getAbsolutePath());
        assertTrue(configFile.exists(), "File cấu hình phải được tạo thành công");

        // Đọc lại file
        AppConfig loaded = ConfigLoader.loadConfig(configFile.getAbsolutePath());
        assertNotNull(loaded);
        SmtpConfig loadedSmtp = loaded.getSmtp();
        assertNotNull(loadedSmtp);

        assertEquals("smtp.testprovider.com", loadedSmtp.getHost());
        assertEquals(587, loadedSmtp.getPort());
        assertFalse(loadedSmtp.isEnableSsl());
        assertEquals("LOGIN", loadedSmtp.getAuthType());
        assertEquals("testuser@testprovider.com", loadedSmtp.getUsername());
        assertEquals("secretPass123", loadedSmtp.getPassword());
        assertEquals("sender@testprovider.com", loadedSmtp.getFrom());
        assertEquals("receiver@testprovider.com", loadedSmtp.getTo());
        assertEquals("cc_recipient@testprovider.com", loadedSmtp.getCc());
        assertEquals("bcc_recipient@testprovider.com", loadedSmtp.getBcc());
        assertEquals("Tiêu đề kiểm thử lưu cấu hình", loadedSmtp.getSubject());
        assertEquals("<p>Nội dung kiểm thử</p>", loadedSmtp.getBody());
        assertEquals(4, loadedSmtp.getRetry().getMaxRetry());
        assertEquals(3, loadedSmtp.getRetry().getBaseDelaySeconds());
        assertEquals(45, loadedSmtp.getRetry().getMaxDelaySeconds());
    }

    @Test
    @DisplayName("EmailMessageBuilder tạo MimeMessage hỗ trợ đầy đủ To, CC, BCC")
    void testEmailMessageBuilderWithCcAndBcc() throws Exception {
        Session session = Session.getInstance(new Properties());

        MimeMessage msg = EmailMessageBuilder.createMimeMessage(
                session,
                "sender@example.com",
                "to1@example.com, to2@example.com",
                "cc1@example.com",
                "bcc1@example.com, bcc2@example.com",
                "Tiêu đề thử nghiệm CC BCC",
                "<p>Xin chào</p>",
                Collections.emptyList()
        );

        assertNotNull(msg);
        assertEquals("Tiêu đề thử nghiệm CC BCC", msg.getSubject());

        // Kiểm tra TO
        InternetAddress[] toAddresses = (InternetAddress[]) msg.getRecipients(Message.RecipientType.TO);
        assertNotNull(toAddresses);
        assertEquals(2, toAddresses.length);
        assertEquals("to1@example.com", toAddresses[0].getAddress());
        assertEquals("to2@example.com", toAddresses[1].getAddress());

        // Kiểm tra CC
        InternetAddress[] ccAddresses = (InternetAddress[]) msg.getRecipients(Message.RecipientType.CC);
        assertNotNull(ccAddresses);
        assertEquals(1, ccAddresses.length);
        assertEquals("cc1@example.com", ccAddresses[0].getAddress());

        // Kiểm tra BCC
        InternetAddress[] bccAddresses = (InternetAddress[]) msg.getRecipients(Message.RecipientType.BCC);
        assertNotNull(bccAddresses);
        assertEquals(2, bccAddresses.length);
        assertEquals("bcc1@example.com", bccAddresses[0].getAddress());
        assertEquals("bcc2@example.com", bccAddresses[1].getAddress());
    }

    @Test
    @DisplayName("EmailMessageBuilder ngăn chặn tấn công CRLF Injection trên trường CC và BCC")
    void testCrlfInjectionOnCcAndBcc() {
        Session session = Session.getInstance(new Properties());

        // CRLF trên CC
        assertThrows(IllegalArgumentException.class, () ->
                EmailMessageBuilder.createMimeMessage(
                        session,
                        "sender@example.com",
                        "to@example.com",
                        "victim@example.com\r\nBcc: hacker@example.com",
                        null,
                        "Test",
                        "Body",
                        null
                )
        );

        // CRLF trên BCC
        assertThrows(IllegalArgumentException.class, () ->
                EmailMessageBuilder.createMimeMessage(
                        session,
                        "sender@example.com",
                        "to@example.com",
                        null,
                        "victim@example.com\nSubject: Injected",
                        "Test",
                        "Body",
                        null
                )
        );
    }

    @Test
    @DisplayName("AttachmentItem định dạng dung lượng file chính xác (Bytes, KB, MB)")
    void testAttachmentItemSizeFormatting(@TempDir Path tempDir) throws Exception {
        // 1. File nhỏ (< 1KB)
        File smallFile = tempDir.resolve("small.txt").toFile();
        try (FileOutputStream fos = new FileOutputStream(smallFile)) {
            fos.write(new byte[512]);
        }
        AttachmentItem itemSmall = new AttachmentItem(smallFile);
        assertEquals("512 B", itemSmall.getFormattedSize());
        assertTrue(itemSmall.isValid());

        // 2. File vừa (KB)
        File medFile = tempDir.resolve("medium.txt").toFile();
        try (FileOutputStream fos = new FileOutputStream(medFile)) {
            fos.write(new byte[2048]); // 2 KB
        }
        AttachmentItem itemMed = new AttachmentItem(medFile);
        assertEquals("2.0 KB", itemMed.getFormattedSize());

        // 3. File lớn (MB)
        File mbFile = tempDir.resolve("large.bin").toFile();
        try (FileOutputStream fos = new FileOutputStream(mbFile)) {
            fos.write(new byte[1024 * 1024 * 2]); // 2 MB
        }
        AttachmentItem itemMb = new AttachmentItem(mbFile);
        assertEquals("2.00 MB", itemMb.getFormattedSize());
    }

    @Test
    @DisplayName("AttachmentItem phát hiện và từ chối các file nhạy cảm và quá kích thước")
    void testAttachmentItemSecurityDetection(@TempDir Path tempDir) throws Exception {
        File appsettingsFile = tempDir.resolve("appsettings.json").toFile();
        appsettingsFile.createNewFile();
        AttachmentItem sensitiveItem = new AttachmentItem(appsettingsFile);
        assertTrue(sensitiveItem.isSensitive());
        assertFalse(sensitiveItem.isValid());

        File certFile = tempDir.resolve("server.pem").toFile();
        certFile.createNewFile();
        AttachmentItem certItem = new AttachmentItem(certFile);
        assertTrue(certItem.isSensitive());
        assertFalse(certItem.isValid());
    }

    @Test
    @DisplayName("LoggerUtil.addLogListener nhận sự kiện log thời gian thực")
    void testLoggerUtilListenerDispatch() {
        AtomicBoolean received = new AtomicBoolean(false);
        LoggerUtil.LogListener listener = (time, level, msg, ex, formatted) -> {
            if ("INFO".equals(level) && msg.contains("Test Real-Time Event 12345")) {
                received.set(true);
            }
        };

        LoggerUtil.addLogListener(listener);
        try {
            LoggerUtil.info("Test Real-Time Event 12345");
            assertTrue(received.get(), "LogListener phải nhận được sự kiện log");
        } finally {
            LoggerUtil.removeLogListener(listener);
        }
    }

    @Test
    @DisplayName("ThemeManager chuyển đổi Dark / Light mode và cung cấp màu accent Outlook")
    void testThemeManager() {
        assertNotNull(ThemeManager.getAccentColor());
        assertNotNull(ThemeManager.getSuccessColor());
        assertNotNull(ThemeManager.getWarningColor());
        assertNotNull(ThemeManager.getDangerColor());

        boolean initial = ThemeManager.isDarkMode();
        ThemeManager.setDarkMode(!initial, null);
        assertEquals(!initial, ThemeManager.isDarkMode());

        // Restore
        ThemeManager.setDarkMode(initial, null);
    }

    @Test
    @DisplayName("SmtpClient.testConnection xác thực tham số đầu vào chặt chẽ")
    void testConnectionValidation() {
        assertThrows(IllegalArgumentException.class, () -> SmtpClient.testConnection(null));

        SmtpConfig emptyHost = new SmtpConfig();
        emptyHost.setHost("");
        assertThrows(IllegalArgumentException.class, () -> SmtpClient.testConnection(emptyHost));

        SmtpConfig invalidPort = new SmtpConfig();
        invalidPort.setPort(-1);
        assertThrows(IllegalArgumentException.class, () -> SmtpClient.testConnection(invalidPort));

        SmtpConfig missingUser = new SmtpConfig();
        missingUser.setUsername("");
        assertThrows(IllegalArgumentException.class, () -> SmtpClient.testConnection(missingUser));

        SmtpConfig missingPass = new SmtpConfig();
        missingPass.setUsername("user@example.com");
        missingPass.setPassword("");
        assertThrows(IllegalArgumentException.class, () -> SmtpClient.testConnection(missingPass));
    }

    @Test
    @DisplayName("EmailMessageBuilder hỗ trợ phân tách nhiều người nhận bằng cả dấu chấm phẩy (;) và phẩy (,)")
    void testSemicolonSeparatedRecipients() throws Exception {
        Session session = Session.getInstance(new Properties());
        MimeMessage msg = EmailMessageBuilder.createMimeMessage(
                session,
                "sender@example.com",
                "to1@example.com; to2@example.com; to3@example.com",
                "cc1@example.com; cc2@example.com",
                "bcc1@example.com; bcc2@example.com",
                "Chủ đề thử nghiệm dấu chấm phẩy",
                "<p>Test</p>",
                Collections.emptyList()
        );

        assertNotNull(msg);
        assertEquals(3, msg.getRecipients(Message.RecipientType.TO).length);
        assertEquals(2, msg.getRecipients(Message.RecipientType.CC).length);
        assertEquals(2, msg.getRecipients(Message.RecipientType.BCC).length);
    }

    @Test
    @DisplayName("EmailMessageBuilder từ chối From hoặc To rỗng hoặc null")
    void testRejectEmptyFromOrTo() {
        Session session = Session.getInstance(new Properties());

        assertThrows(IllegalArgumentException.class, () ->
                EmailMessageBuilder.createMimeMessage(session, "", "to@example.com", "Subject", "Body", Collections.emptyList()));

        assertThrows(IllegalArgumentException.class, () ->
                EmailMessageBuilder.createMimeMessage(session, null, "to@example.com", "Subject", "Body", Collections.emptyList()));

        assertThrows(IllegalArgumentException.class, () ->
                EmailMessageBuilder.createMimeMessage(session, "from@example.com", "", "Subject", "Body", Collections.emptyList()));

        assertThrows(IllegalArgumentException.class, () ->
                EmailMessageBuilder.createMimeMessage(session, "from@example.com", null, "Subject", "Body", Collections.emptyList()));
    }

    @Test
    @DisplayName("AttachmentItem định dạng dung lượng độc lập với Locale hệ thống")
    void testAttachmentItemLocaleIndependence(@TempDir Path tempDir) throws Exception {
        File file = tempDir.resolve("test_locale.txt").toFile();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(new byte[1536]); // 1.5 KB
        }

        java.util.Locale defaultLocale = java.util.Locale.getDefault();
        try {
            // Đặt giả lập Locale Đức (dùng dấu phẩy làm phân cách thập phân: 1,5)
            java.util.Locale.setDefault(java.util.Locale.GERMANY);
            AttachmentItem item = new AttachmentItem(file);
            assertEquals("1.5 KB", item.getFormattedSize(), "Phải định dạng dấu chấm thập phân nhất quán trên mọi Locale");
        } finally {
            java.util.Locale.setDefault(defaultLocale);
        }
    }

    @Test
    @DisplayName("ConfigLoader nạp thành công tài khoản không có To và kiểm tra requireRecipient")
    void testLoadConfigWithoutTo(@TempDir Path tempDir) throws Exception {
        File configFile = tempDir.resolve("no_to_appsettings.json").toFile();
        SmtpConfig smtp = new SmtpConfig();
        smtp.setHost("smtp.gmail.com");
        smtp.setPort(465);
        smtp.setUsername("testuser@gmail.com");
        smtp.setPassword("secretPass123");
        smtp.setTo(null); // Không có người nhận mặc định
        AppConfig config = new AppConfig(smtp);

        ConfigLoader.saveConfig(config, configFile.getAbsolutePath());

        // Nạp cấu hình thành công (To là tùy chọn cho việc lưu tài khoản)
        AppConfig loaded = ConfigLoader.loadConfig(configFile.getAbsolutePath());
        assertNotNull(loaded);
        assertEquals("testuser@gmail.com", loaded.getSmtp().getUsername());

        // Validate với requireRecipient=true phải ném lỗi
        assertThrows(IllegalArgumentException.class, () ->
                ConfigLoader.validateConfig(loaded.getSmtp(), true));

        // Validate với requireRecipient=false phải thành công
        assertDoesNotThrow(() ->
                ConfigLoader.validateConfig(loaded.getSmtp(), false));
    }
}
