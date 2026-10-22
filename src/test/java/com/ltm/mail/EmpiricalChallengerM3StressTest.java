package com.ltm.mail;

import com.ltm.mail.client.EmailMessageBuilder;
import com.ltm.mail.client.SmtpClient;
import com.ltm.mail.config.RetryConfig;
import com.ltm.mail.config.SmtpConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.mail.*;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Empirical Challenger Milestone 3 Stress & Adversarial Suite.
 * Exercises extreme boundaries, concurrency safety, DOS device protection,
 * duplicate attachments, zero-retry boundaries, and large payload stress.
 */
public class EmpiricalChallengerM3StressTest {

    private final Session dummySession = Session.getInstance(new Properties());

    @Test
    @DisplayName("STRESS-01: Windows reserved device names (CON, NUL, AUX, PRN) rejected or handled safely")
    void testWindowsDosDeviceNamesHandledSafely() {
        String[] dosDevices = {"CON", "NUL", "AUX", "PRN", "COM1", "LPT1"};
        for (String device : dosDevices) {
            assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.resolveAttachment(device),
                    "Windows device " + device + " should be rejected as non-existent or invalid");
        }
    }

    @Test
    @DisplayName("STRESS-02: Path with '..' strictly blocked by SEC-05 even if syntactically resolvable")
    void testRelativePathWithParentTraversalStrictlyBlocked() {
        String redundantPath = "attachments/../attachments/sample.txt";
        SecurityException secEx = assertThrows(SecurityException.class, () ->
                EmailMessageBuilder.resolveAttachment(redundantPath));
        assertTrue(secEx.getMessage().contains("Path Traversal") && secEx.getMessage().contains(".."),
                "SEC-05 strictly forbids '..' in attachment path");
    }

    @Test
    @DisplayName("STRESS-03: Extreme subject (10,000 chars) and Unicode control chars / RTL overrides")
    void testExtremeSubjectLengthAndSpecialUnicode() throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("🔥 Bắt đầu: ");
        // Append RTL override and zero-width spaces
        sb.append("\u202E RTL OVERRIDE \u200B \uFEFF");
        for (int i = 0; i < 200; i++) {
            sb.append(" [Mục ").append(i).append(": Tiếng Việt có dấu kiểm thử tải nặng - 🚀]");
        }
        String extremeSubject = sb.toString();
        assertTrue(extremeSubject.length() > 5000);

        MimeMessage msg = EmailMessageBuilder.createMimeMessage(
                dummySession,
                "sender@example.com",
                "receiver@example.com",
                extremeSubject,
                "<p>Test body</p>",
                Collections.emptyList()
        );

        assertNotNull(msg);
        assertEquals(extremeSubject, msg.getSubject());

        // Verify message serialization handles RFC 2047 line folding of 5000+ chars
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        msg.writeTo(baos);
        String rawMime = baos.toString(StandardCharsets.UTF_8);
        assertTrue(rawMime.contains("Subject:"));
        // Either B or Q encoding is used by JavaMail RFC 2047
        assertTrue(rawMime.contains("=?UTF-8?B?") || rawMime.contains("=?UTF-8?Q?"));
    }

    @Test
    @DisplayName("STRESS-04: Boundary condition maxRetry = 0 (execute exactly 1 attempt, 0 retries)")
    void testZeroMaxRetryConfiguration() throws Exception {
        SmtpConfig config = new SmtpConfig();
        config.setHost("smtp.simulated.local");
        config.setPort(465);
        config.setUsername("user@simulated.local");
        config.setPassword("secret");
        config.setFrom("user@simulated.local");
        config.setTo("dest@simulated.local");
        config.setSubject("Zero Retry");
        config.setBody("<p>Zero Retry</p>");

        RetryConfig retry = new RetryConfig();
        retry.setMaxRetry(0); // 0 retries -> exactly 1 attempt
        retry.setBaseDelaySeconds(1);
        retry.setMaxDelaySeconds(5);
        config.setRetry(retry);

        SmtpClient client = new SmtpClient(config);
        AtomicInteger sleeps = new AtomicInteger(0);
        client.setSleeper(millis -> sleeps.incrementAndGet());

        OfflineSmtpSimulationTest.ProgrammableSmtpTransport.reset();
        OfflineSmtpSimulationTest.ProgrammableSmtpTransport.enqueueConnectFailure(
                new MessagingException("Transient error", new SocketException("Connection reset"))
        );

        Properties props = new Properties();
        props.put("mail.smtp.host", config.getHost());
        props.put("mail.smtp.port", String.valueOf(config.getPort()));
        Session session = Session.getInstance(props);
        session.setProvider(new Provider(
                Provider.Type.TRANSPORT,
                "smtp",
                OfflineSmtpSimulationTest.ProgrammableSmtpTransport.class.getName(),
                "LTM-TestDouble",
                "1.0"
        ));

        MimeMessage msg = EmailMessageBuilder.createMimeMessage(
                session, config.getFrom(), config.getTo(), config.getSubject(), config.getBody(), Collections.emptyList()
        );

        assertThrows(MessagingException.class, () -> client.sendWithRetry(session, msg));

        assertEquals(1, OfflineSmtpSimulationTest.ProgrammableSmtpTransport.CONNECT_CALLS.get(),
                "With maxRetry=0, must attempt connect exactly once");
        assertEquals(0, sleeps.get(), "With maxRetry=0, sleeper must never be invoked");
    }

    @Test
    @DisplayName("STRESS-05: Duplicate attachment paths in single message handled cleanly")
    void testDuplicateAttachmentsInSingleMessage() throws Exception {
        File sampleFile = new File("attachments/sample.txt");
        List<File> duplicates = Arrays.asList(sampleFile, sampleFile, sampleFile);

        MimeMessage msg = EmailMessageBuilder.createMimeMessage(
                dummySession,
                "sender@example.com",
                "receiver@example.com",
                "Duplicate Attachments",
                "<p>Test</p>",
                duplicates
        );

        Multipart mp = (Multipart) msg.getContent();
        // 1 body + 3 duplicate attachments = 4 parts
        assertEquals(4, mp.getCount());

        for (int i = 1; i <= 3; i++) {
            BodyPart part = mp.getBodyPart(i);
            assertEquals(Part.ATTACHMENT.toLowerCase(), part.getDisposition().toLowerCase());
            assertEquals("sample.txt", part.getFileName());
            try (java.io.InputStream is = part.getInputStream()) {
                assertTrue(is.readAllBytes().length > 0);
            }
        }
    }

    @Test
    @DisplayName("STRESS-06: High concurrency test (20 concurrent threads sendWithRetry)")
    void testConcurrentSendWithRetry() throws Exception {
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        SmtpConfig config = new SmtpConfig();
        config.setHost("smtp.simulated.local");
        config.setPort(465);
        config.setUsername("user@simulated.local");
        config.setPassword("secret");
        config.setFrom("user@simulated.local");
        config.setTo("dest@simulated.local");
        config.setSubject("Concurrent Test");
        config.setBody("<p>Concurrent</p>");

        RetryConfig retry = new RetryConfig();
        retry.setMaxRetry(2);
        retry.setBaseDelaySeconds(1);
        retry.setMaxDelaySeconds(5);
        config.setRetry(retry);

        SmtpClient client = new SmtpClient(config);
        client.setSleeper(millis -> {}); // fast sleep

        OfflineSmtpSimulationTest.ProgrammableSmtpTransport.reset();

        Properties props = new Properties();
        props.put("mail.smtp.host", config.getHost());
        props.put("mail.smtp.port", String.valueOf(config.getPort()));
        Session session = Session.getInstance(props);
        session.setProvider(new Provider(
                Provider.Type.TRANSPORT,
                "smtp",
                OfflineSmtpSimulationTest.ProgrammableSmtpTransport.class.getName(),
                "LTM-TestDouble",
                "1.0"
        ));

        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try {
                    MimeMessage msg = EmailMessageBuilder.createMimeMessage(
                            session,
                            "sender" + idx + "@example.com",
                            "receiver" + idx + "@example.com",
                            "Subject " + idx,
                            "<p>Body " + idx + "</p>",
                            Collections.emptyList()
                    );
                    client.sendWithRetry(session, msg);
                    return true;
                } finally {
                    latch.countDown();
                }
            }));
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "All 20 threads should complete within 10 seconds");
        executor.shutdown();

        for (Future<Boolean> f : futures) {
            assertTrue(f.get());
        }

        assertEquals(threadCount, OfflineSmtpSimulationTest.ProgrammableSmtpTransport.SEND_MESSAGE_CALLS.get(),
                "All 20 messages should have been sent successfully");
        assertEquals(threadCount, OfflineSmtpSimulationTest.ProgrammableSmtpTransport.CLOSE_CALLS.get(),
                "Transport should be closed for all 20 threads");
    }

    @Test
    @DisplayName("STRESS-07: Large payload HTML (1MB text) serialization stress")
    void testLargePayloadHtmlStress() throws Exception {
        // Generate ~1MB HTML text
        StringBuilder largeBody = new StringBuilder(1024 * 1024);
        largeBody.append("<html><body><h1>BÁO CÁO DỮ LIỆU LỚN</h1>");
        String chunk = "<p>Dòng dữ liệu kiểm thử tải trọng lớn với ký tự tiếng Việt có dấu: Cần Thơ, Đà Nẵng, Hà Nội, TP Hồ Chí Minh. Số ngẫu nhiên: 1234567890 🚀🔥</p>";
        while (largeBody.length() < 1024 * 1024) {
            largeBody.append(chunk);
        }
        largeBody.append("</body></html>");

        MimeMessage msg = EmailMessageBuilder.createMimeMessage(
                dummySession,
                "sender@example.com",
                "receiver@example.com",
                "Báo cáo 1MB HTML",
                largeBody.toString(),
                Collections.singletonList(new File("attachments/sample.txt"))
        );

        assertNotNull(msg);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        msg.writeTo(baos);
        byte[] outputBytes = baos.toByteArray();
        assertTrue(outputBytes.length > 1024 * 1024, "Output size must exceed 1MB");
    }
}
