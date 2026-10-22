package com.ltm.mail;

import com.ltm.mail.client.EmailMessageBuilder;
import com.ltm.mail.client.SmtpClient;
import com.ltm.mail.config.ConfigLoader;
import com.ltm.mail.config.SmtpConfig;
import com.ltm.mail.util.LoggerUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.File;
import java.io.RandomAccessFile;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bộ kiểm thử tự động toàn diện cho Milestone 1: Security & Vulnerability Hardening (R1)
 * Bao gồm các tính năng SEC-01 đến SEC-06 theo đặc tả PROJECT.md.
 */
public class SecurityAuditTest {

    @Nested
    @DisplayName("SEC-01 & SEC-02 & SEC-03: Kiểm thử Cấu hình Bảo mật SSL/TLS & Chống MitM/STRIPTLS")
    class SslTlsHardeningTests {

        @Test
        @DisplayName("SEC-01: Bắt buộc cấu hình mail.smtp.ssl.checkserveridentity = true trong SMTPS (Port 465)")
        void testSslCheckServerIdentityEnabledInSmtps() {
            SmtpConfig config = new SmtpConfig();
            config.setHost("smtp.gmail.com");
            config.setPort(465);
            config.setEnableSsl(true);
            config.setUsername("testuser@gmail.com");
            config.setPassword("secretPass");

            SmtpClient client = new SmtpClient(config);
            Session session = client.createSession();
            Properties props = session.getProperties();

            assertEquals("true", props.getProperty("mail.smtp.ssl.checkserveridentity"),
                    "mail.smtp.ssl.checkserveridentity phải được bật (true) để xác thực hostname chống MitM");
        }

        @Test
        @DisplayName("SEC-01: Bắt buộc cấu hình mail.smtp.ssl.checkserveridentity = true trong STARTTLS (Port 587)")
        void testSslCheckServerIdentityEnabledInStartTls() {
            SmtpConfig config = new SmtpConfig();
            config.setHost("smtp.gmail.com");
            config.setPort(587);
            config.setEnableSsl(false);
            config.setUsername("testuser@gmail.com");
            config.setPassword("secretPass");

            SmtpClient client = new SmtpClient(config);
            Session session = client.createSession();
            Properties props = session.getProperties();

            assertEquals("true", props.getProperty("mail.smtp.ssl.checkserveridentity"),
                    "mail.smtp.ssl.checkserveridentity phải được bật (true) ngay cả trong chế độ STARTTLS");
        }

        @Test
        @DisplayName("SEC-02: Cấu hình mail.smtp.ssl.protocols hỗ trợ đồng thời cả TLSv1.2 và TLSv1.3")
        void testSslProtocolsSupportTls12AndTls13() {
            SmtpConfig config = new SmtpConfig();
            config.setHost("smtp.gmail.com");
            config.setPort(465);
            config.setEnableSsl(true);

            SmtpClient client = new SmtpClient(config);
            Session session = client.createSession();
            Properties props = session.getProperties();

            String protocols = props.getProperty("mail.smtp.ssl.protocols");
            assertNotNull(protocols, "Thuộc tính mail.smtp.ssl.protocols không được để trống");
            assertTrue(protocols.contains("TLSv1.2"), "Cần hỗ trợ TLSv1.2");
            assertTrue(protocols.contains("TLSv1.3"), "Cần hỗ trợ TLSv1.3");
        }

        @Test
        @DisplayName("SEC-03: STARTTLS bắt buộc phải bật mail.smtp.starttls.required = true chống tấn công hạ cấp STRIPTLS")
        void testStartTlsRequiredToPreventStripTls() {
            SmtpConfig config = new SmtpConfig();
            config.setHost("smtp.gmail.com");
            config.setPort(587);
            config.setEnableSsl(false);

            SmtpClient client = new SmtpClient(config);
            Session session = client.createSession();
            Properties props = session.getProperties();

            assertEquals("true", props.getProperty("mail.smtp.starttls.enable"),
                    "mail.smtp.starttls.enable phải là true");
            assertEquals("true", props.getProperty("mail.smtp.starttls.required"),
                    "mail.smtp.starttls.required phải là true để ngăn chặn kẻ tấn công hạ cấp kết nối về plaintext");
        }

        @Test
        @DisplayName("SEC-03: SMTPS thiết lập fallback = false để tránh rơi về kết nối không mã hóa")
        void testSocketFactoryFallbackDisabled() {
            SmtpConfig config = new SmtpConfig();
            config.setPort(465);
            config.setEnableSsl(true);

            SmtpClient client = new SmtpClient(config);
            Session session = client.createSession();
            Properties props = session.getProperties();

            assertEquals("false", props.getProperty("mail.smtp.socketFactory.fallback"),
                    "mail.smtp.socketFactory.fallback phải là false");
        }
    }

    @Nested
    @DisplayName("SEC-04: Kiểm thử Phòng Chống Lỗ Hổng CRLF / Header Injection (CWE-93)")
    class CrlfInjectionPreventionTests {

        private final Session dummySession = Session.getInstance(new Properties());

        @Test
        @DisplayName("SEC-04: Từ chối Subject chứa \\r\\n (Bcc Injection)")
        void testRejectSubjectWithCrLf() {
            String maliciousSubject = "Important Update\r\nBcc: hacker@evil.com";
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
                EmailMessageBuilder.createMimeMessage(
                        dummySession,
                        "sender@example.com",
                        "victim@example.com",
                        maliciousSubject,
                        "<p>Test</p>",
                        Collections.emptyList()
                );
            });
            assertTrue(ex.getMessage().contains("CRLF Injection"), "Thông báo ngoại lệ phải cảnh báo về CRLF Injection");
        }

        @Test
        @DisplayName("SEC-04: Từ chối Subject chứa \\n độc lập (MIME Body Splitting)")
        void testRejectSubjectWithLf() {
            String maliciousSubject = "Report\n\nMalicious body content inserted here";
            assertThrows(IllegalArgumentException.class, () -> {
                EmailMessageBuilder.createMimeMessage(
                        dummySession,
                        "sender@example.com",
                        "victim@example.com",
                        maliciousSubject,
                        "<p>Test</p>",
                        Collections.emptyList()
                );
            });
        }

        @Test
        @DisplayName("SEC-04: Từ chối From chứa ký tự xuống dòng")
        void testRejectFromWithCrLf() {
            String maliciousFrom = "admin@example.com\r\nCc: spy@evil.com";
            assertThrows(IllegalArgumentException.class, () -> {
                EmailMessageBuilder.createMimeMessage(
                        dummySession,
                        maliciousFrom,
                        "victim@example.com",
                        "Clean Subject",
                        "<p>Test</p>",
                        Collections.emptyList()
                );
            });
        }

        @Test
        @DisplayName("SEC-04: Từ chối To chứa ký tự xuống dòng")
        void testRejectToWithCrLf() {
            String maliciousTo = "target@example.com\nTo: second_target@evil.com";
            assertThrows(IllegalArgumentException.class, () -> {
                EmailMessageBuilder.createMimeMessage(
                        dummySession,
                        "sender@example.com",
                        maliciousTo,
                        "Clean Subject",
                        "<p>Test</p>",
                        Collections.emptyList()
                );
            });
        }

        @Test
        @DisplayName("SEC-04: validateHeaderValue chấp nhận chuỗi sạch và từ chối chuỗi có CRLF")
        void testValidateHeaderValueMethod() {
            assertDoesNotThrow(() -> EmailMessageBuilder.validateHeaderValue("Subject", "Tiêu đề an toàn"));
            assertDoesNotThrow(() -> EmailMessageBuilder.validateHeaderValue("Subject", null));

            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.validateHeaderValue("Subject", "line1\rline2"));
            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.validateHeaderValue("Subject", "line1\nline2"));
            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.validateHeaderValue("From", "admin\r\nuser"));
        }

        @Test
        @DisplayName("SEC-04: sanitizeHeader loại bỏ triệt để các ký tự \\r và \\n")
        void testSanitizeHeader() {
            assertNull(EmailMessageBuilder.sanitizeHeader(null));
            assertEquals("Safe File Name.pdf", EmailMessageBuilder.sanitizeHeader("Safe \r\nFile Name.pdf\r\n"));
            assertEquals("Sample Text", EmailMessageBuilder.sanitizeHeader("  Sample Text\r\n  "));
            assertEquals("SampleText", EmailMessageBuilder.sanitizeHeader("Sample\r\nText"));
        }

        @Test
        @DisplayName("SEC-04: ConfigLoader.validateConfig từ chối cấu hình chứa CRLF")
        void testConfigLoaderRejectsCrlfInFields() {
            SmtpConfig config = new SmtpConfig();
            config.setHost("smtp.gmail.com");
            config.setPort(465);
            config.setUsername("user@gmail.com\r\nInjected-Header: 123");
            config.setPassword("pass");
            config.setTo("receiver@example.com");

            assertThrows(IllegalArgumentException.class, () -> {
                ConfigLoader.validateConfig(config);
            });
        }
    }

    @Nested
    @DisplayName("SEC-05: Kiểm thử Phòng Chống Lỗ Hổng Path Traversal & Giới Hạn File Đính Kèm (CWE-22)")
    class PathTraversalPreventionTests {

        @Test
        @DisplayName("SEC-05: Từ chối đường dẫn chứa chuỗi điều hướng '..' (Relative Traversal)")
        void testRejectPathTraversalWithDotDot() {
            SecurityException ex = assertThrows(SecurityException.class, () -> {
                EmailMessageBuilder.resolveAttachment("../../appsettings.json");
            });
            assertTrue(ex.getMessage().contains("Path Traversal"),
                    "Phải thông báo phát hiện Path Traversal");
        }

        @Test
        @DisplayName("SEC-05: Từ chối truy cập file nhạy cảm appsettings.json")
        void testRejectSensitiveFileAppSettings() {
            File appSettings = new File("appsettings.json");
            // Cho dù file tồn tại hay không, resolveAttachment phải chặn truy cập tệp cấu hình chứa secret
            SecurityException ex = assertThrows(SecurityException.class, () -> {
                EmailMessageBuilder.resolveAttachment("appsettings.json");
            });
            assertTrue(ex.getMessage().contains("appsettings.json") || ex.getMessage().contains("nhạy cảm"));
        }

        @Test
        @DisplayName("SEC-05: Từ chối truy cập file mẫu appsettings.example.json")
        void testRejectSensitiveFileAppSettingsExample() {
            assertThrows(SecurityException.class, () -> {
                EmailMessageBuilder.resolveAttachment("appsettings.example.json");
            });
        }

        @Test
        @DisplayName("SEC-05: Từ chối đính kèm file chứng chỉ / private key (.key, .pem)")
        void testRejectCertificateAndKeyFiles() {
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("id_rsa")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("server.key")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("cert.pem")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("keystore.pfx")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File(".env")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("smtp-client.log")));
        }

        @Test
        @DisplayName("SEC-05: Báo lỗi khi đường dẫn file đính kèm không tồn tại hoặc rỗng")
        void testRejectNonExistentOrEmptyPath() {
            assertThrows(IllegalArgumentException.class, () -> EmailMessageBuilder.resolveAttachment(null));
            assertThrows(IllegalArgumentException.class, () -> EmailMessageBuilder.resolveAttachment("   "));
            assertThrows(IllegalArgumentException.class, () -> EmailMessageBuilder.resolveAttachment("attachments/non_existent_9999.txt"));
        }

        @Test
        @DisplayName("SEC-05: Chấp nhận file hợp lệ nằm trong thư mục attachments và cấu hình Content-Disposition")
        void testAcceptValidAttachment() throws Exception {
            File sample = EmailMessageBuilder.resolveAttachment("attachments/sample.txt");
            assertNotNull(sample);
            assertTrue(sample.exists());
            assertEquals("sample.txt", sample.getName());

            Session session = Session.getInstance(new Properties());
            MimeMessage message = EmailMessageBuilder.createMimeMessage(
                    session,
                    "sender@example.com",
                    "receiver@example.com",
                    "Subject",
                    "<p>Body</p>",
                    Collections.singletonList(sample)
            );

            MimeMultipart multipart = (MimeMultipart) message.getContent();
            assertEquals(2, multipart.getCount());
            // Phần đính kèm Part 2
            Part attachPart = multipart.getBodyPart(1);
            assertEquals(Part.ATTACHMENT, attachPart.getDisposition(),
                    "Phần đính kèm phải có disposition là ATTACHMENT theo chuẩn RFC");
        }

        @Test
        @DisplayName("SEC-05: Từ chối file đính kèm vượt quá dung lượng tối đa cho phép 25MB (DoS Protection)")
        void testRejectOversizedAttachment() throws Exception {
            File tempOversized = File.createTempFile("oversized_test_", ".dat", new File("attachments"));
            tempOversized.deleteOnExit();

            try (RandomAccessFile raf = new RandomAccessFile(tempOversized, "rw")) {
                // Đặt kích thước ảo 26MB (26 * 1024 * 1024 bytes)
                raf.setLength(26 * 1024 * 1024L);
            }

            try {
                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
                    EmailMessageBuilder.resolveAttachment(tempOversized.getPath());
                });
                assertTrue(ex.getMessage().contains("25MB"), "Phải báo lỗi vượt quá 25MB");
            } finally {
                tempOversized.delete();
            }
        }
    }

    @Nested
    @DisplayName("SEC-06: Kiểm thử Bảo vệ Thông tin Xác thực (Credentials Protection & Env Overrides)")
    class CredentialProtectionTests {

        @Test
        @DisplayName("SEC-06: SmtpConfig.toString() không để lộ mật khẩu trong chuỗi")
        void testPasswordNeverExposedInToString() {
            SmtpConfig config = new SmtpConfig();
            config.setUsername("my_user@gmail.com");
            config.setPassword("SuperSecretPassword123!");

            String output = config.toString();
            assertFalse(output.contains("SuperSecretPassword123!"),
                    "Mật khẩu tuyệt đối không được xuất hiện trong SmtpConfig.toString()");
            assertFalse(output.toLowerCase().contains("password="),
                    "Trường password không nên hiển thị trong chuỗi log");
        }

        @Test
        @DisplayName("SEC-06: Ghi đè mật khẩu từ biến môi trường SMTP_PASSWORD")
        void testEnvironmentVariablePasswordOverride() {
            SmtpConfig config = new SmtpConfig();
            config.setUsername("user@example.com");
            config.setPassword("old_or_placeholder_password");

            Map<String, String> mockEnv = new HashMap<>();
            mockEnv.put("SMTP_PASSWORD", "EnvSecretPass_456!");
            mockEnv.put("SMTP_USERNAME", "env_user@example.com");

            ConfigLoader.applyEnvironmentOverrides(config, mockEnv);

            assertEquals("EnvSecretPass_456!", config.getPassword(),
                    "Mật khẩu phải được cập nhật từ biến môi trường SMTP_PASSWORD");
            assertEquals("env_user@example.com", config.getUsername(),
                    "Tài khoản phải được cập nhật từ biến môi trường SMTP_USERNAME");
        }

        @Test
        @DisplayName("SEC-06: LoggerUtil.maskSensitiveInfo che giấu mật khẩu trong log và exception")
        void testLoggerMasksSensitiveInfo() {
            String rawMessage = "Lỗi kết nối với password: SuperSecretPass123! và auth token secret=xyz789";
            String masked = LoggerUtil.maskSensitiveInfo(rawMessage);

            assertFalse(masked.contains("SuperSecretPass123!"), "Mật khẩu thô phải được che chắn");
            assertFalse(masked.contains("xyz789"), "Secret thô phải được che chắn");
            assertTrue(masked.contains("********"), "Chuỗi sau khi che phải chứa ký tự đại diện");
        }

        @Test
        @DisplayName("SEC-06: validateConfig ném ngoại lệ khi thiếu mật khẩu")
        void testValidateConfigMissingPassword() {
            SmtpConfig config = new SmtpConfig();
            config.setHost("smtp.gmail.com");
            config.setPort(465);
            config.setUsername("user@gmail.com");
            config.setPassword(""); // Trống
            config.setTo("receiver@example.com");

            assertThrows(IllegalArgumentException.class, () -> {
                ConfigLoader.validateConfig(config);
            });
        }
    }
}
