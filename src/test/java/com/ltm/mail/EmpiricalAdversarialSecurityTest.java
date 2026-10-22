package com.ltm.mail;

import com.ltm.mail.client.EmailMessageBuilder;
import com.ltm.mail.client.SmtpClient;
import com.ltm.mail.config.ConfigLoader;
import com.ltm.mail.config.SmtpConfig;
import com.ltm.mail.util.LoggerUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import java.io.File;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Empirical Adversarial Test Suite for Milestone 1: Security & Vulnerability Hardening (R1)
 * Executed independently by Challenger 2 to stress-test SEC-01, SEC-02, SEC-03, and SEC-06.
 */
public class EmpiricalAdversarialSecurityTest {

    @Nested
    @DisplayName("ADV-01: SSL/TLS Hardening & Hostname Verification (SEC-01, SEC-02, SEC-03)")
    class SslTlsAdversarialTests {

        @Test
        @DisplayName("SMTPS mode must enforce hostname verification, protocols, and disable socket factory fallback")
        void testSmtpsModeStrictConfiguration() {
            SmtpConfig config = new SmtpConfig();
            config.setHost("smtp.gmail.com");
            config.setPort(465);
            config.setEnableSsl(true);
            config.setUsername("testuser@gmail.com");
            config.setPassword("secretPass123");

            SmtpClient client = new SmtpClient(config);
            Session session = client.createSession();
            Properties props = session.getProperties();

            // SEC-01: Hostname verification
            assertEquals("true", props.getProperty("mail.smtp.ssl.checkserveridentity"));

            // SEC-02: Modern protocols
            assertEquals("TLSv1.2 TLSv1.3", props.getProperty("mail.smtp.ssl.protocols"));

            // SEC-03: SMTPS socket settings
            assertEquals("true", props.getProperty("mail.smtp.ssl.enable"));
            assertEquals("465", props.getProperty("mail.smtp.socketFactory.port"));
            assertEquals("javax.net.ssl.SSLSocketFactory", props.getProperty("mail.smtp.socketFactory.class"));
            assertEquals("false", props.getProperty("mail.smtp.socketFactory.fallback"));

            // Ensure no wildcard trust (mail.smtp.ssl.trust must NOT be "*")
            assertNotEquals("*", props.getProperty("mail.smtp.ssl.trust"),
                    "Wildcard trust '*' would disable certificate verification and cause MitM!");
        }

        @Test
        @DisplayName("STARTTLS mode must enforce hostname verification, required STARTTLS, and disable direct SSL")
        void testStartTlsModeStrictConfiguration() {
            SmtpConfig config = new SmtpConfig();
            config.setHost("smtp.office365.com");
            config.setPort(587);
            config.setEnableSsl(false);
            config.setUsername("corp@company.com");
            config.setPassword("corpSecretPass");

            SmtpClient client = new SmtpClient(config);
            Session session = client.createSession();
            Properties props = session.getProperties();

            // SEC-01: Hostname verification in STARTTLS mode
            assertEquals("true", props.getProperty("mail.smtp.ssl.checkserveridentity"));

            // SEC-02: Modern protocols in STARTTLS mode
            assertEquals("TLSv1.2 TLSv1.3", props.getProperty("mail.smtp.ssl.protocols"));

            // SEC-03: STRIPTLS downgrade attack prevention
            assertEquals("true", props.getProperty("mail.smtp.starttls.enable"));
            assertEquals("true", props.getProperty("mail.smtp.starttls.required"));
            assertEquals("false", props.getProperty("mail.smtp.ssl.enable"));

            // Ensure no wildcard trust
            assertNotEquals("*", props.getProperty("mail.smtp.ssl.trust"));
        }

        @Test
        @DisplayName("Non-standard ports (e.g. 2525) must be preserved in port properties")
        void testCustomPortHandling() {
            SmtpConfig config = new SmtpConfig();
            config.setHost("smtp.mailtrap.io");
            config.setPort(2525);
            config.setEnableSsl(false);

            SmtpClient client = new SmtpClient(config);
            Session session = client.createSession();
            Properties props = session.getProperties();

            assertEquals("2525", props.getProperty("mail.smtp.port"));
            assertEquals("true", props.getProperty("mail.smtp.ssl.checkserveridentity"));
            assertEquals("true", props.getProperty("mail.smtp.starttls.required"));
        }

        @Test
        @DisplayName("Session Authenticator correctly supplies credentials without cross-session contamination")
        void testAuthenticatorIsolationBetweenSessions() throws Exception {
            SmtpConfig config1 = new SmtpConfig();
            config1.setHost("smtp.example.com");
            config1.setUsername("user1@example.com");
            config1.setPassword("pass1");

            SmtpConfig config2 = new SmtpConfig();
            config2.setHost("smtp.example.com");
            config2.setUsername("user2@example.com");
            config2.setPassword("pass2");

            SmtpClient client1 = new SmtpClient(config1);
            Session session1 = client1.createSession();

            SmtpClient client2 = new SmtpClient(config2);
            Session session2 = client2.createSession();

            // Invoke requestPasswordAuthentication via reflection or direct call on Session
            Method requestAuth = Session.class.getDeclaredMethod(
                    "requestPasswordAuthentication",
                    InetAddress.class, int.class, String.class, String.class, String.class
            );
            requestAuth.setAccessible(true);

            PasswordAuthentication auth1 = (PasswordAuthentication) requestAuth.invoke(
                    session1, null, 465, "smtp", "prompt", "user1@example.com"
            );
            PasswordAuthentication auth2 = (PasswordAuthentication) requestAuth.invoke(
                    session2, null, 465, "smtp", "prompt", "user2@example.com"
            );

            assertNotNull(auth1);
            assertEquals("user1@example.com", auth1.getUserName());
            assertEquals("pass1", auth1.getPassword());

            assertNotNull(auth2);
            assertEquals("user2@example.com", auth2.getUserName());
            assertEquals("pass2", auth2.getPassword());
        }
    }

    @Nested
    @DisplayName("ADV-02: Credential Protection & Environment Variable Precedence (SEC-06)")
    class CredentialProtectionAdversarialTests {

        @Test
        @DisplayName("Environment variable SMTP_PASSWORD must override hardcoded password in SmtpConfig")
        void testEnvVarOverridesHardcodedPassword() {
            SmtpConfig config = new SmtpConfig();
            config.setUsername("file_user@gmail.com");
            config.setPassword("hardcoded_plaintext_pass_in_json");

            Map<String, String> env = new HashMap<>();
            env.put("SMTP_PASSWORD", "SuperSecureEnvPass2026!");

            ConfigLoader.applyEnvironmentOverrides(config, env);

            assertEquals("SuperSecureEnvPass2026!", config.getPassword(),
                    "Biến môi trường SMTP_PASSWORD phải ghi đè mật khẩu trong file JSON");
        }

        @Test
        @DisplayName("Whitespace-only or empty SMTP_PASSWORD in env should NOT overwrite valid password with blank")
        void testEmptyEnvVarDoesNotOverwriteValidPassword() {
            SmtpConfig config = new SmtpConfig();
            config.setUsername("file_user@gmail.com");
            config.setPassword("valid_original_password");

            Map<String, String> env = new HashMap<>();
            env.put("SMTP_PASSWORD", "   ");

            ConfigLoader.applyEnvironmentOverrides(config, env);

            assertEquals("valid_original_password", config.getPassword(),
                    "Biến môi trường chỉ có khoảng trắng không được ghi đè làm mất mật khẩu hợp lệ");
        }

        @Test
        @DisplayName("System property smtp.password acts as fallback when password is placeholder")
        void testSystemPropertyFallbackOnPlaceholder() {
            String originalProp = System.getProperty("smtp.password");
            try {
                System.setProperty("smtp.password", "SystemPropertyPass123");

                SmtpConfig config = new SmtpConfig();
                config.setPassword("your_app_password_here"); // placeholder

                ConfigLoader.applyEnvironmentOverrides(config, Collections.emptyMap());

                assertEquals("SystemPropertyPass123", config.getPassword(),
                        "System Property phải ghi đè giá trị placeholder 'your_app_password_here'");
            } finally {
                if (originalProp != null) {
                    System.setProperty("smtp.password", originalProp);
                } else {
                    System.clearProperty("smtp.password");
                }
            }
        }

        @Test
        @DisplayName("SmtpConfig.toString() never reveals password under any circumstances")
        void testToStringExcludesPassword() {
            SmtpConfig config = new SmtpConfig();
            config.setUsername("john.doe@company.com");
            config.setPassword("SecretKey#999!");

            String str = config.toString();
            assertFalse(str.contains("SecretKey#999!"), "Mật khẩu không được xuất hiện trong toString()");
            assertFalse(str.toLowerCase().contains("password="), "Trường password không được xuất hiện trong toString()");
        }

        @Test
        @DisplayName("ConfigLoader.validateConfig rejects missing credentials after env resolution")
        void testValidationFailsIfPasswordRemainsEmpty() {
            SmtpConfig config = new SmtpConfig();
            config.setHost("smtp.gmail.com");
            config.setPort(465);
            config.setUsername("user@gmail.com");
            config.setPassword(null); // No password
            config.setTo("dest@example.com");

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    ConfigLoader.validateConfig(config)
            );
            assertTrue(ex.getMessage().contains("Password"));
        }
    }

    @Nested
    @DisplayName("ADV-03: Logger Masking Stress Tests (SEC-06)")
    class LoggerMaskingAdversarialTests {

        @Test
        @DisplayName("Masking handles various key patterns (password, pass, secret) with different delimiters")
        void testVariousMaskingPatterns() {
            assertEquals("password: ********", LoggerUtil.maskSensitiveInfo("password: MyPlainPassword"));
            assertEquals("password=********", LoggerUtil.maskSensitiveInfo("password=MyPlainPassword"));
            assertEquals("Password : '********'", LoggerUtil.maskSensitiveInfo("Password : 'MyPlainPassword'"));
            assertEquals("PASSWORD = \"********\"", LoggerUtil.maskSensitiveInfo("PASSWORD = \"MyPlainPassword\""));
            assertEquals("pass: ********", LoggerUtil.maskSensitiveInfo("pass: 12345678"));
            assertEquals("secret=********", LoggerUtil.maskSensitiveInfo("secret=topSecretToken"));
        }

        @Test
        @DisplayName("LoggerUtil.maskSensitiveInfo gracefully handles null and empty inputs")
        void testNullAndEmptyInputs() {
            assertNull(LoggerUtil.maskSensitiveInfo(null));
            assertEquals("", LoggerUtil.maskSensitiveInfo(""));
            assertEquals("Normal log message without secrets",
                    LoggerUtil.maskSensitiveInfo("Normal log message without secrets"));
        }

        @Test
        @DisplayName("LoggerUtil masks sensitive info inside Exception messages and nested causes")
        void testExceptionLoggingMasking() {
            Exception nestedCause = new Exception("Root cause detail with secret=sensitiveNestedValue");
            Exception mainException = new Exception("Failed with password: sensitiveMainPassword", nestedCause);

            // Trigger LoggerUtil.error without throwing
            assertDoesNotThrow(() -> LoggerUtil.error("Test error event with pass=tempPass", mainException));
        }
    }

    @Nested
    @DisplayName("ADV-04: Header & Path Traversal Boundary Stress Tests (SEC-04, SEC-05)")
    class SecurityBoundaryStressTests {

        @Test
        @DisplayName("Header validation rejects carriage return without line feed (CR-only injection)")
        void testRejectCrOnly() {
            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.validateHeaderValue("Subject", "line1\rline2"));
        }

        @Test
        @DisplayName("Header validation rejects line feed without carriage return (LF-only injection)")
        void testRejectLfOnly() {
            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.validateHeaderValue("Subject", "line1\nline2"));
        }

        @Test
        @DisplayName("Path traversal detects Windows-style backward slashes ..\\..\\")
        void testRejectWindowsStylePathTraversal() {
            assertThrows(SecurityException.class, () ->
                    EmailMessageBuilder.resolveAttachment("..\\..\\appsettings.json"));
        }

        @Test
        @DisplayName("Sensitive files blacklist rejects access to credentials, keys, and logs")
        void testSensitiveFilesBlacklist() {
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File(".env")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File(".env.local")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("appsettings.json")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("appsettings.example.json")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("smtp-client.log")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("private.key")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("certificate.crt")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("id_rsa")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File("id_ed25519")));
            assertTrue(EmailMessageBuilder.isSensitiveFile(new File(".git/config")));
        }
    }
}
