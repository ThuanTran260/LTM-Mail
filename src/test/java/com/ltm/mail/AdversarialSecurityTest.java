package com.ltm.mail;

import com.ltm.mail.client.EmailMessageBuilder;
import com.ltm.mail.client.SmtpClient;
import com.ltm.mail.config.ConfigLoader;
import com.ltm.mail.config.SmtpConfig;
import com.ltm.mail.util.LoggerUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial Security Test Suite for Milestone 1 (SEC-04 & SEC-05).
 * Tests deep bypass attempts, Windows-specific filesystem quirks, tricky CRLF variants,
 * boundary sizes, and error paths.
 */
public class AdversarialSecurityTest {

    private final Session dummySession = Session.getInstance(new Properties());

    @Nested
    @DisplayName("Adversarial CRLF Injection Stress Tests (SEC-04)")
    class AdversarialCrlfTests {

        @Test
        @DisplayName("Test standalone CR (\\r without \\n) in Subject, From, To")
        void testStandaloneCr() {
            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.validateHeaderValue("Subject", "Alert\rBcc: spy@evil.com"));
            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.validateHeaderValue("From", "attacker@evil.com\r"));
            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.validateHeaderValue("To", "\rvictim@evil.com"));

            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.createMimeMessage(dummySession, "from@test.com", "to@test.com", "Subject\rEvil", "Body", null));
        }

        @Test
        @DisplayName("Test standalone LF (\\n without \\r) in Subject, From, To")
        void testStandaloneLf() {
            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.validateHeaderValue("Subject", "Alert\nBcc: spy@evil.com"));
            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.validateHeaderValue("From", "attacker@evil.com\n"));
            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.validateHeaderValue("To", "\nvictim@evil.com"));

            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.createMimeMessage(dummySession, "from@test.com", "to@test.com", "Subject\nEvil", "Body", null));
        }

        @Test
        @DisplayName("Test RFC 5322 Header Folding with whitespace (CRLF followed by Space or Tab)")
        void testHeaderFoldingAttempts() {
            // Folding White Space (FWS) attempts
            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.validateHeaderValue("Subject", "Line1\r\n Line2"));
            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.validateHeaderValue("Subject", "Line1\r\n\tLine2"));
            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.validateHeaderValue("To", "user@test.com\r\n Bcc: hidden@evil.com"));
        }

        @Test
        @DisplayName("Test multiple consecutive CRLF sequences (MIME body injection / premature DATA termination)")
        void testMultipleCrlfSequences() {
            String doubleCrlf = "Subject\r\n\r\nInjected Body content";
            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.validateHeaderValue("Subject", doubleCrlf));
            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.createMimeMessage(dummySession, "from@test.com", "to@test.com", doubleCrlf, "Body", null));

            String dotTermination = "Subject\r\n.\r\nQUIT\r\n";
            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.validateHeaderValue("Subject", dotTermination));
        }

        @Test
        @DisplayName("Test sanitizeHeader thoroughly removes all CR and LF without stripping valid content")
        void testSanitizeHeaderRobustness() {
            assertEquals("SafeFileName.txt", EmailMessageBuilder.sanitizeHeader("\r\nSafe\r\nFile\r\nName.txt\r\n"));
            assertEquals("NoNewlinesHere", EmailMessageBuilder.sanitizeHeader("No\nNew\rlines\r\nHere"));
            assertEquals("", EmailMessageBuilder.sanitizeHeader("\r\n\r\n  \r\n"));
        }

        @Test
        @DisplayName("Test Unicode line break characters (\\u2028, \\u2029, \\u0085) - must not break into raw CRLF")
        void testUnicodeLineBreaksSafeInMime() throws Exception {
            String subjectWithUnicode = "Notice\u2028Bcc: evil@example.com";
            MimeMessage message = EmailMessageBuilder.createMimeMessage(
                    dummySession,
                    "from@test.com",
                    "to@test.com",
                    subjectWithUnicode,
                    "Body",
                    null
            );
            // RFC 2047 encodes non-ASCII as =?UTF-8?...?= on a single line
            String rawHeader = message.getHeader("Subject", null);
            assertNotNull(rawHeader);
            assertFalse(rawHeader.contains("\r"), "Raw header must not contain CR");
            assertFalse(rawHeader.contains("\n"), "Raw header must not contain LF");
        }
    }

    @Nested
    @DisplayName("Adversarial Path Traversal & Attachment Security Tests (SEC-05)")
    class AdversarialPathTraversalTests {

        @Test
        @DisplayName("Test Windows absolute path outside workspace (e.g., C:\\Windows\\win.ini or C:\\Windows)")
        void testAbsoluteWindowsPathsOutsideWorkspace() {
            // An absolute path on another folder or root
            String winPath = "C:\\Windows\\win.ini";
            File winFile = new File(winPath);
            if (winFile.exists()) {
                SecurityException ex = assertThrows(SecurityException.class, () ->
                        EmailMessageBuilder.resolveAttachment(winPath));
                assertTrue(ex.getMessage().contains("phạm vi") || ex.getMessage().contains("Path Traversal"));
            } else {
                // If on different drive or doesn't exist, try root E:\ or parent folder
                File parentDir = new File("..").getAbsoluteFile();
                SecurityException ex = assertThrows(SecurityException.class, () ->
                        EmailMessageBuilder.resolveAttachment(parentDir.getAbsolutePath() + "\\test.txt"));
                // Either file not exists or security exception
                assertNotNull(ex);
            }
        }

        @Test
        @DisplayName("Test root-relative paths like \\Windows\\win.ini or /etc/passwd")
        void testRootRelativePaths() {
            // Root-relative path on Windows (e.g. \something)
            assertThrows(Exception.class, () ->
                    EmailMessageBuilder.resolveAttachment("/etc/passwd"));
            assertThrows(Exception.class, () ->
                    EmailMessageBuilder.resolveAttachment("\\Windows\\win.ini"));
        }

        @Test
        @DisplayName("Test directory path instead of regular file (should reject)")
        void testDirectoryRejected() {
            // Passing a directory like "attachments" or "."
            IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.resolveAttachment("attachments"));
            assertTrue(ex1.getMessage().contains("không phải là file thông thường"));

            IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.resolveAttachment("."));
            assertTrue(ex2.getMessage().contains("không phải là file thông thường"));
        }

        @Test
        @DisplayName("Test sensitive files inside project: .git, .env, keys, appsettings")
        void testSensitiveFileBlacklist() {
            // Sensitive files that might exist or are probed
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("appsettings.json")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("appsettings.example.json")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File(".env")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File(".env.local")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("smtp-client.log")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File(".git/config")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File(".git/HEAD")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("my_server.key")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("cert.pem")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("id_rsa")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("id_rsa.pub")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("id_ed25519")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("keystore.p12")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("keystore.pfx")));

            // Case insensitivity
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("APPSETTINGS.JSON")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("SERVER.KEY")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("ID_RSA")));
        }

        @Test
        @DisplayName("Test defense in createMimeMessage: attaching a sensitive file directly must throw SecurityException")
        void testCreateMimeMessageRejectsSensitiveFileDirectly() {
            File sensitive = new File("appsettings.json");
            if (sensitive.exists()) {
                assertThrows(SecurityException.class, () ->
                        EmailMessageBuilder.createMimeMessage(
                                dummySession,
                                "sender@example.com",
                                "target@example.com",
                                "Subject",
                                "Body",
                                Collections.singletonList(sensitive)
                        ));
            }
        }

        @Test
        @DisplayName("Test Windows Alternate Data Stream (ADS) trick e.g. filename::$DATA")
        void testWindowsAlternateDataStream() {
            // Probing appsettings.json::$DATA
            String adsPath = "appsettings.json::$DATA";
            // On Windows NTFS, does it exist or get blocked?
            try {
                File f = EmailMessageBuilder.resolveAttachment(adsPath);
                fail("VULNERABILITY: appsettings.json::$DATA was NOT rejected! File returned: " + f);
            } catch (Exception ex) {
                System.out.println("ADS exception caught: " + ex.getClass().getName() + " -> " + ex.getMessage());
                // IOException or SecurityException or IllegalArgumentException
                assertTrue(ex instanceof SecurityException || ex instanceof IllegalArgumentException || ex instanceof java.io.IOException,
                        "Must reject ADS: " + ex.getClass().getName());
            }
        }

        @Test
        @DisplayName("Test 25MB boundary limits: exactly 25MB vs 25MB + 1 byte")
        void testBoundaryAttachmentSizes() throws Exception {
            File exact25Mb = File.createTempFile("exact_25mb_", ".dat", new File("attachments"));
            exact25Mb.deleteOnExit();

            File oversized = File.createTempFile("oversized_25mb_plus_", ".dat", new File("attachments"));
            oversized.deleteOnExit();

            try {
                // Exactly 25 MB = 25 * 1024 * 1024 bytes = 26,214,400 bytes
                try (RandomAccessFile raf = new RandomAccessFile(exact25Mb, "rw")) {
                    raf.setLength(EmailMessageBuilder.MAX_ATTACHMENT_SIZE);
                }

                // Exactly 25 MB + 1 byte = 26,214,401 bytes
                try (RandomAccessFile raf = new RandomAccessFile(oversized, "rw")) {
                    raf.setLength(EmailMessageBuilder.MAX_ATTACHMENT_SIZE + 1);
                }

                // 25MB exactly must be accepted
                File resolved = EmailMessageBuilder.resolveAttachment(exact25Mb.getPath());
                assertNotNull(resolved);
                assertEquals(EmailMessageBuilder.MAX_ATTACHMENT_SIZE, resolved.length());

                // 25MB + 1 byte must be rejected with IllegalArgumentException
                IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                        EmailMessageBuilder.resolveAttachment(oversized.getPath()));
                assertTrue(ex.getMessage().contains("25MB"), "Must mention 25MB limit");
            } finally {
                exact25Mb.delete();
                oversized.delete();
            }
        }

        @Test
        @DisplayName("Test 0-byte empty file attachment (should be accepted with warning, not throw)")
        void testZeroByteAttachment() throws Exception {
            File zeroByte = File.createTempFile("zero_byte_", ".txt", new File("attachments"));
            zeroByte.deleteOnExit();

            try {
                File resolved = EmailMessageBuilder.resolveAttachment(zeroByte.getPath());
                assertNotNull(resolved);
                assertEquals(0, resolved.length());
            } finally {
                zeroByte.delete();
            }
        }

        @Test
        @DisplayName("Test nested valid relative path in subfolder e.g. attachments/sample.txt and ./attachments/sample.txt")
        void testNestedValidPaths() throws Exception {
            File f1 = EmailMessageBuilder.resolveAttachment("attachments/sample.txt");
            assertNotNull(f1);

            File f2 = EmailMessageBuilder.resolveAttachment("./attachments/sample.txt");
            assertNotNull(f2);
            assertEquals(f1.getCanonicalPath(), f2.getCanonicalPath());

            File f3 = EmailMessageBuilder.resolveAttachment("attachments\\sample.txt");
            assertNotNull(f3);
            assertEquals(f1.getCanonicalPath(), f3.getCanonicalPath());

            File f4 = EmailMessageBuilder.resolveAttachment("attachments///sample.txt");
            assertNotNull(f4);
            assertEquals(f1.getCanonicalPath(), f4.getCanonicalPath());

            File f5 = EmailMessageBuilder.resolveAttachment("attachments/./sample.txt");
            assertNotNull(f5);
            assertEquals(f1.getCanonicalPath(), f5.getCanonicalPath());
        }

        @Test
        @DisplayName("Test Windows trailing dot trick e.g. appsettings.json. must be rejected")
        void testWindowsTrailingDotTrick() {
            SecurityException ex = assertThrows(SecurityException.class, () ->
                    EmailMessageBuilder.resolveAttachment("appsettings.json."));
            assertTrue(ex.getMessage().contains("appsettings.json") || ex.getMessage().contains("nhạy cảm"));
        }

        @Test
        @DisplayName("Test Windows 8.3 short name trick e.g. APPSET~1.JSO")
        void testWindows8Dot3ShortName() {
            File shortNameFile = new File("APPSET~1.JSO");
            if (shortNameFile.exists()) {
                SecurityException ex = assertThrows(SecurityException.class, () ->
                        EmailMessageBuilder.resolveAttachment("APPSET~1.JSO"));
                assertTrue(ex.getMessage().contains("nhạy cảm") || ex.getMessage().contains("appsettings"));
            }
        }

        @Test
        @DisplayName("Test buildMessage with null and whitespace attachment paths")
        void testBuildMessageWithNullAndWhitespaceAttachments() throws Exception {
            SmtpConfig config = new SmtpConfig();
            config.setFrom("sender@test.com");
            config.setTo("receiver@test.com");
            config.setSubject("Test Subject");
            config.setBody("Hello");
            config.setAttachmentPaths(java.util.Arrays.asList(
                    null,
                    "",
                    "   ",
                    "attachments/sample.txt"
            ));

            MimeMessage msg = EmailMessageBuilder.buildMessage(dummySession, config);
            assertNotNull(msg);
            assertEquals("Test Subject", msg.getSubject());
        }

        @Test
        @DisplayName("Test attachment with UTF-8 / non-ASCII Vietnamese characters in filename")
        void testUtf8AttachmentFilename() throws Exception {
            File utf8File = new File("attachments", "báo_cáo_tài_chính.txt");
            try {
                Files.writeString(utf8File.toPath(), "Nội dung báo cáo tài chính.");
                File resolved = EmailMessageBuilder.resolveAttachment(utf8File.getPath());
                assertNotNull(resolved);

                MimeMessage message = EmailMessageBuilder.createMimeMessage(
                        dummySession,
                        "from@example.com",
                        "to@example.com",
                        "Chủ đề tiếng Việt",
                        "<p>Nội dung</p>",
                        Collections.singletonList(resolved)
                );
                assertNotNull(message);
                javax.mail.Multipart mp = (javax.mail.Multipart) message.getContent();
                assertEquals(2, mp.getCount());
                javax.mail.BodyPart attachPart = mp.getBodyPart(1);
                assertNotNull(attachPart.getFileName());
            } finally {
                if (utf8File.exists()) {
                    utf8File.delete();
                }
            }
        }
    }

    @Nested
    @DisplayName("Adversarial SSL/TLS and Configuration Hardening Tests (SEC-01..03, SEC-06)")
    class AdversarialSslAndConfigTests {

        @Test
        @DisplayName("Verify checkserveridentity is present regardless of port variations")
        void testCheckServerIdentityAlwaysTrue() {
            int[] ports = {25, 465, 587, 2525};
            for (int port : ports) {
                SmtpConfig config = new SmtpConfig();
                config.setHost("mail.custom-domain.com");
                config.setPort(port);
                config.setEnableSsl(port == 465);

                SmtpClient client = new SmtpClient(config);
                Session session = client.createSession();
                assertEquals("true", session.getProperty("mail.smtp.ssl.checkserveridentity"),
                        "Hostname verification must be enabled for port " + port);
            }
        }

        @Test
        @DisplayName("Verify TLS protocols contain both TLSv1.2 and TLSv1.3 in exact format")
        void testTlsProtocolsExactMatch() {
            SmtpConfig config = new SmtpConfig();
            config.setEnableSsl(true);
            SmtpClient client = new SmtpClient(config);
            Session session = client.createSession();

            String protocols = session.getProperty("mail.smtp.ssl.protocols");
            assertEquals("TLSv1.2 TLSv1.3", protocols, "Must configure TLSv1.2 TLSv1.3");
        }

        @Test
        @DisplayName("Verify password masking with complex passwords containing symbols")
        void testPasswordMaskingSymbols() {
            String testLog = "User authenticated with password=P@$$w0rd_123!#&^; next operation";
            String masked = LoggerUtil.maskSensitiveInfo(testLog);
            assertFalse(masked.contains("P@$$w0rd_123!#&^"), "Complex password should be masked");
            assertTrue(masked.contains("********"), "Mask placeholder should be present");
        }
    }
}
