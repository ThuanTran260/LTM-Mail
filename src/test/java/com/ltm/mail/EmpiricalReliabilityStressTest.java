package com.ltm.mail;

import com.ltm.mail.client.EmailMessageBuilder;
import com.ltm.mail.client.SmtpClient;
import com.ltm.mail.config.RetryConfig;
import com.ltm.mail.config.SmtpConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Part;
import javax.mail.Provider;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.URLName;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Empirical Reliability & Robustness Stress Test Suite (Milestone 2 - Challenger 2)
 * Adversarially challenges:
 * - REL-05: Exponential backoff calculation, boundary conditions, bit-shift overflow, jitter distribution, maxDelay ceiling.
 * - REL-05: Real thread interruptions during backoff sleep.
 * - REL-06: Transport lifecycle & cleanup under all simulated failures (connect failure, send failure, dual exceptions).
 * - REL-06 / REL-07: Attachment stream lifecycle, Windows file locking release, RFC 5322 compliance.
 */
public class EmpiricalReliabilityStressTest {

    // =========================================================================
    // Dynamic Mock Transport for Simulated Hardware / Protocol Failures
    // =========================================================================
    public static class AdversarialTransport extends Transport {
        public static final AtomicInteger instanceCount = new AtomicInteger(0);
        public static final AtomicInteger connectCount = new AtomicInteger(0);
        public static final AtomicInteger sendCount = new AtomicInteger(0);
        public static final AtomicInteger closeCount = new AtomicInteger(0);

        public static volatile RuntimeException connectRuntimeException = null;
        public static volatile MessagingException connectMessagingException = null;
        public static volatile MessagingException sendMessageMessagingException = null;
        public static volatile RuntimeException sendMessageRuntimeException = null;
        public static volatile Error sendMessageError = null;
        public static volatile MessagingException closeMessagingException = null;

        public static void reset() {
            instanceCount.set(0);
            connectCount.set(0);
            sendCount.set(0);
            closeCount.set(0);
            connectRuntimeException = null;
            connectMessagingException = null;
            sendMessageMessagingException = null;
            sendMessageRuntimeException = null;
            sendMessageError = null;
            closeMessagingException = null;
        }

        public AdversarialTransport(Session session, URLName urlname) {
            super(session, urlname);
            instanceCount.incrementAndGet();
        }

        @Override
        public synchronized void connect() throws MessagingException {
            connectCount.incrementAndGet();
            if (connectMessagingException != null) {
                throw connectMessagingException;
            }
            if (connectRuntimeException != null) {
                throw connectRuntimeException;
            }
            setConnected(true);
        }

        @Override
        public synchronized void sendMessage(Message msg, Address[] addresses) throws MessagingException {
            sendCount.incrementAndGet();
            if (sendMessageError != null) {
                throw sendMessageError;
            }
            if (sendMessageMessagingException != null) {
                throw sendMessageMessagingException;
            }
            if (sendMessageRuntimeException != null) {
                throw sendMessageRuntimeException;
            }
        }

        @Override
        public synchronized void close() throws MessagingException {
            closeCount.incrementAndGet();
            setConnected(false);
            if (closeMessagingException != null) {
                throw closeMessagingException;
            }
        }
    }

    private Session createAdversarialSession() {
        try {
            Properties props = new Properties();
            props.put("mail.smtp.host", "localhost");
            props.put("mail.smtp.port", "25");
            Session session = Session.getInstance(props);

            Provider testProvider = new Provider(
                    Provider.Type.TRANSPORT,
                    "smtp",
                    AdversarialTransport.class.getName(),
                    "Adversarial-LTM",
                    "2.0"
            );
            session.setProvider(testProvider);
            return session;
        } catch (Exception e) {
            throw new RuntimeException("Failed to register test provider", e);
        }
    }

    private SmtpConfig createConfig(int maxRetry, int baseDelay, int maxDelay) {
        SmtpConfig config = new SmtpConfig();
        config.setHost("localhost");
        config.setPort(25);
        config.setEnableSsl(false);
        config.setUsername("test@example.com");
        config.setPassword("secret");
        config.setFrom("test@example.com");
        config.setTo("dest@example.com");
        config.setSubject("Reliability Stress Test");
        config.setRetry(new RetryConfig(maxRetry, baseDelay, maxDelay));
        return config;
    }

    @BeforeEach
    void setup() {
        AdversarialTransport.reset();
    }

    // =========================================================================
    // 1. REL-05: Backoff Boundaries & Bit-Shift Overflow Guards
    // =========================================================================
    @Nested
    @DisplayName("REL-05: Kiểm tra biên và chống tràn số của thuật toán Exponential Backoff")
    class BackoffBoundariesAndOverflowTests {

        @Test
        @DisplayName("Biên số âm và số 0: attempt <= 0 phải được quy đổi an toàn về attempt 1 (>= 1000ms)")
        void testAttemptZeroAndNegativeInputs() {
            int[] edgeAttempts = {0, -1, -2, -10, -100, -999999, Integer.MIN_VALUE};

            for (int att : edgeAttempts) {
                long delay = SmtpClient.calculateBackoffWithJitterMillis(att, 2, 60, 0L);
                assertEquals(2000L, delay, "Attempt " + att + " phải được coi như attempt 1 (delay = 2000ms)");
                assertTrue(delay >= 1000L, "Delay không bao giờ được nhỏ hơn 1000ms");
            }
        }

        @Test
        @DisplayName("Biên cực đại: attempt lên đến Integer.MAX_VALUE không bị tràn số và bị chặn ở maxDelaySec")
        void testAttemptHugeValuesUpToIntegerMaxValue() {
            int[] extremeAttempts = {
                    30, 31, 32, 33, 63, 64, 100, 10_000, 1_000_000,
                    Integer.MAX_VALUE - 1, Integer.MAX_VALUE
            };

            for (int att : extremeAttempts) {
                long delay = SmtpClient.calculateBackoffWithJitterMillis(att, 2, 60);
                assertTrue(delay > 0, "Delay cho attempt " + att + " không được là số âm do tràn số!");
                assertEquals(60000L, delay, "Delay cho attempt " + att + " phải bị chặn chính xác ở maxDelaySec (60000ms)");
            }
        }

        @Test
        @DisplayName("Khảo sát chi tiết vùng chuyển dịch bit shift từ attempt 28 đến 33 (chặn tràn tại 30)")
        void testBitShiftTransitionRange() {
            int baseDelaySec = 1;
            int maxDelaySec = 2_000_000_000; // Đặt maxDelay đủ lớn (2 tỷ giây) để kiểm tra giá trị thô không bị chạm trần

            // Attempt 1: 1 * 2^0 = 1s = 1000ms
            assertEquals(1000L, SmtpClient.calculateBackoffWithJitterMillis(1, baseDelaySec, maxDelaySec, 0L));
            // Attempt 2: 1 * 2^1 = 2s = 2000ms
            assertEquals(2000L, SmtpClient.calculateBackoffWithJitterMillis(2, baseDelaySec, maxDelaySec, 0L));
            // Attempt 30: 1 * 2^29 = 536,870,912s
            assertEquals(536870912000L, SmtpClient.calculateBackoffWithJitterMillis(30, baseDelaySec, maxDelaySec, 0L));
            // Attempt 31: 1 * 2^30 = 1,073,741,824s
            assertEquals(1073741824000L, SmtpClient.calculateBackoffWithJitterMillis(31, baseDelaySec, maxDelaySec, 0L));
            // Attempt 32: shift bị clamp tại 30 -> vẫn là 1,073,741,824s, không bị tràn sang âm
            assertEquals(1073741824000L, SmtpClient.calculateBackoffWithJitterMillis(32, baseDelaySec, maxDelaySec, 0L));
            // Attempt 33: vẫn giữ nguyên tại shift = 30
            assertEquals(1073741824000L, SmtpClient.calculateBackoffWithJitterMillis(33, baseDelaySec, maxDelaySec, 0L));
        }

        @Test
        @DisplayName("Cấu hình bất thường: baseDelaySec hoặc maxDelaySec <= 0 hoặc max < base")
        void testAnomalousDelayConfigurations() {
            // base <= 0 -> được nâng lên 1
            long delayZeroBase = SmtpClient.calculateBackoffWithJitterMillis(1, 0, 10, 0L);
            assertEquals(1000L, delayZeroBase);

            // max <= 0 -> safeMax nâng lên safeBase (ít nhất 1)
            long delayZeroMax = SmtpClient.calculateBackoffWithJitterMillis(1, 2, 0, 0L);
            assertEquals(2000L, delayZeroMax);

            // max < base (ví dụ base=10, max=3) -> safeMax được nâng lên 10 để tránh nghịch lý
            long delayInverted = SmtpClient.calculateBackoffWithJitterMillis(1, 10, 3, 0L);
            assertEquals(10000L, delayInverted);

            // Cấu hình cực đại Integer.MAX_VALUE: vẫn luôn dương và >= 1000ms
            long delayHugeConfig = SmtpClient.calculateBackoffWithJitterMillis(1, Integer.MAX_VALUE, Integer.MAX_VALUE);
            assertTrue(delayHugeConfig >= 1000L, "Delay phải luôn >= 1000ms");
        }

        @Test
        @DisplayName("Tính đơn điệu (Monotonicity) của chuỗi backoff khi chưa chạm trần")
        void testBackoffMonotonicProgression() {
            int base = 2;
            int max = 1000;
            long previousDelay = 0;

            for (int att = 1; att <= 9; att++) {
                long delay = SmtpClient.calculateBackoffWithJitterMillis(att, base, max, 0L);
                assertTrue(delay > previousDelay, "Delay ở attempt " + att + " phải lớn hơn attempt trước đó");
                assertEquals((long) base * (1L << (att - 1)) * 1000L, delay);
                previousDelay = delay;
            }
        }
    }

    // =========================================================================
    // 2. REL-05: Jitter Distribution & Anti-Thundering Herd Verification
    // =========================================================================
    @Nested
    @DisplayName("REL-05: Kiểm định thống kê phân phối Jitter ngẫu nhiên và hiệu lực của trần maxDelay")
    class JitterDistributionTests {

        @Test
        @DisplayName("Kiểm định thống kê phân phối Jitter qua 10.000 mẫu: tính ngẫu nhiên, khoảng biên và phương sai")
        void testJitterStatisticalDistribution() {
            int baseDelaySec = 2;
            int maxDelaySec = 60;
            int sampleSize = 10_000;

            long minObserved = Long.MAX_VALUE;
            long maxObserved = Long.MIN_VALUE;
            double sum = 0;

            // Attempt 1: cappedBackoff = 2000ms, maxJitter = min(1000, 2000/5=400) = 400ms.
            // Khoảng hợp lệ lý thuyết: [2000ms, 2400ms].
            for (int i = 0; i < sampleSize; i++) {
                long delay = SmtpClient.calculateBackoffWithJitterMillis(1, baseDelaySec, maxDelaySec);
                assertTrue(delay >= 2000L, "Delay không được thấp hơn 2000ms");
                assertTrue(delay <= 2400L, "Delay không được vượt quá 2400ms");

                minObserved = Math.min(minObserved, delay);
                maxObserved = Math.max(maxObserved, delay);
                sum += delay;
            }

            double mean = sum / sampleSize;

            // Kiểm tra phân phối đều: Giá trị nhỏ nhất tiệm cận 2000ms, lớn nhất tiệm cận 2400ms
            assertTrue(minObserved <= 2005L, "Min observed phải tiệm cận 2000ms, thực tế: " + minObserved);
            assertTrue(maxObserved >= 2395L, "Max observed phải tiệm cận 2400ms, thực tế: " + maxObserved);

            // Kỳ vọng lý thuyết của phân phối đều [2000, 2400] là 2200ms.
            // Với 10.000 mẫu, trung bình thực tế phải nằm trong khoảng [2185, 2215].
            assertTrue(mean >= 2185 && mean <= 2215, "Giá trị trung bình (" + mean + ") phải tiệm cận 2200ms");

            // Phương sai lý thuyết của phân phối đều [0, 400] là (400^2)/12 ≈ 13,333
            assertTrue(maxObserved - minObserved >= 390, "Khoảng biến thiên phải đủ rộng để giải tỏa thundering herd");
        }

        @Test
        @DisplayName("Trần maxDelaySec được bảo vệ tuyệt đối: jitter không bao giờ làm trễ vượt quá trần")
        void testStrictMaxDelayCeilingEnforcementAcrossAttempts() {
            int baseDelaySec = 3;
            int maxDelaySec = 10; // Trần 10.000ms

            for (int attempt = 1; attempt <= 30; attempt++) {
                for (int run = 0; run < 100; run++) {
                    long delay = SmtpClient.calculateBackoffWithJitterMillis(attempt, baseDelaySec, maxDelaySec);
                    assertTrue(delay <= 10000L,
                            String.format("Lần thử %d (chạy lần %d): delay %dms vượt quá trần 10000ms!", attempt, run, delay));
                }
            }
        }

        @Test
        @DisplayName("Khử jitter nhân tạo cực đại: Jitter lớn vẫn không làm delay vượt quá maxDelaySec")
        void testArbitraryJitterCeilingEnforcement() {
            // Jitter lớn (ví dụ 100.000ms vượt xa maxDelay 10.000ms) -> phải được clamp tại 10000ms
            long delay = SmtpClient.calculateBackoffWithJitterMillis(1, 2, 10, 100_000L);
            assertEquals(10000L, delay, "Jitter nhân tạo lớn (100.000ms) phải bị clamp ở trần 10000ms");

            long delayMillionJitter = SmtpClient.calculateBackoffWithJitterMillis(1, 2, 10, 10_000_000L);
            assertEquals(10000L, delayMillionJitter, "Jitter 10 triệu ms phải bị clamp ở trần 10000ms");

            long delayNegativeJitter = SmtpClient.calculateBackoffWithJitterMillis(1, 2, 10, -5000L);
            assertEquals(2000L, delayNegativeJitter, "Jitter âm phải được chặn về 0, không làm giảm baseDelay");
        }
    }

    // =========================================================================
    // 3. REL-05: Thread Interruption & Concurrency during Retry Sleep
    // =========================================================================
    @Nested
    @DisplayName("REL-05: Kiểm tra gián đoạn luồng (Thread Interruption) trong chu kỳ chờ Retry")
    class ThreadInterruptionTests {

        @Test
        @DisplayName("Luồng nền bị ngắt (interrupt) trong khi sleep -> thức dậy ngay lập tức, không đợi hết thời gian chờ")
        void testRealThreadInterruptedDuringSleep() throws Exception {
            // baseDelay = 10 giây (10.000ms). Nếu không xử lý đúng, luồng sẽ bị treo 10 giây.
            SmtpConfig config = createConfig(3, 10, 60);
            Session session = createAdversarialSession();

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress("sender@example.com"));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("dest@example.com"));
            msg.setText("Interruption Test");

            // Thiết lập transport ném ngoại lệ retryable (SocketTimeoutException)
            AdversarialTransport.sendMessageMessagingException = new MessagingException(
                    "Network error", new SocketTimeoutException("Read timed out"));

            SmtpClient client = new SmtpClient(config);
            // Sử dụng sleeper mặc định (Thread::sleep thực tế)

            AtomicReference<Throwable> thrownInThread = new AtomicReference<>();
            AtomicBoolean interruptedStatus = new AtomicBoolean(false);
            CountDownLatch threadFinishedLatch = new CountDownLatch(1);

            long startTime = System.currentTimeMillis();

            Thread workerThread = new Thread(() -> {
                try {
                    client.sendWithRetry(session, msg);
                } catch (Throwable t) {
                    interruptedStatus.set(Thread.currentThread().isInterrupted());
                    thrownInThread.set(t);
                } finally {
                    threadFinishedLatch.countDown();
                }
            }, "RetryWorkerThread");

            workerThread.start();

            // Chờ luồng worker rơi vào trạng thái TIMED_WAITING (đang sleep trong retry loop)
            boolean enteredSleep = false;
            for (int i = 0; i < 50; i++) {
                Thread.sleep(20);
                if (workerThread.getState() == Thread.State.TIMED_WAITING) {
                    enteredSleep = true;
                    break;
                }
            }
            assertTrue(enteredSleep, "Luồng worker phải đi vào trạng thái TIMED_WAITING để sleep");

            // Phát lệnh ngắt luồng ngay khi đang sleep
            workerThread.interrupt();

            // Luồng phải kết thúc gần như ngay lập tức (dưới 1000ms, không được đợi 10.000ms)
            boolean completedPromptly = threadFinishedLatch.await(1500, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - startTime;

            assertTrue(completedPromptly, "Luồng phải bị ngắt và dừng ngay, không được chờ hết 10 giây!");
            assertTrue(elapsed < 2000, "Tổng thời gian thực thi (" + elapsed + "ms) phải nhỏ hơn 2000ms");

            Throwable thrown = thrownInThread.get();
            assertNotNull(thrown, "Luồng phải ném ngoại lệ khi bị ngắt");
            assertTrue(thrown instanceof RuntimeException, "Phải bọc trong RuntimeException");
            assertTrue(thrown.getMessage().contains("bị ngắt quãng"), "Thông điệp lỗi phải giải thích tiến trình bị ngắt quãng");
            assertNotNull(thrown.getCause(), "Cause phải chứa InterruptedException");
            assertTrue(thrown.getCause() instanceof InterruptedException);

            // Kiểm tra cờ ngắt của luồng đã được phục hồi đúng theo Java Thread Interruption Specification
            assertTrue(interruptedStatus.get(), "Cờ Thread.currentThread().isInterrupted() BẮT BUỘC phải được bảo lưu");
        }

        @Test
        @DisplayName("Khôi phục cờ ngắt khi sử dụng mock sleeper ném InterruptedException")
        void testInterruptFlagPreservedWithMockSleeper() {
            SmtpConfig config = createConfig(3, 2, 60);
            Session session = createAdversarialSession();

            AdversarialTransport.sendMessageMessagingException = new MessagingException(
                    "Timeout", new SocketTimeoutException("Timed out"));

            SmtpClient client = new SmtpClient(config);
            client.setSleeper(millis -> {
                throw new InterruptedException("Simulated sleep interruption");
            });

            MimeMessage msg = new MimeMessage(session);
            assertDoesNotThrow(() -> {
                msg.setFrom(new InternetAddress("sender@example.com"));
                msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("dest@example.com"));
                msg.setText("Test");
            });

            RuntimeException re = assertThrows(RuntimeException.class, () ->
                    client.sendWithRetry(session, msg)
            );

            assertTrue(re.getMessage().contains("bị ngắt quãng"));
            assertTrue(Thread.currentThread().isInterrupted(), "Cờ ngắt luồng hiện tại phải là TRUE");

            // Reset cờ ngắt để không ảnh hưởng tới các test tiếp theo
            Thread.interrupted();
        }
    }

    // =========================================================================
    // 4. REL-06: Transport Resource Cleanup Under Simulated Failures
    // =========================================================================
    @Nested
    @DisplayName("REL-06: Đảm bảo giải phóng tài nguyên Transport trong khối finally dưới mọi tình huống sự cố")
    class TransportCleanupStressTests {

        @Test
        @DisplayName("Sự cố 1: transport.connect() ném ngoại lệ kiểm tra (MessagingException) -> Transport.close() vẫn được gọi")
        void testTransportClosedWhenConnectThrowsMessagingException() throws Exception {
            Session session = createAdversarialSession();
            SmtpClient client = new SmtpClient(createConfig(0, 1, 10));

            AdversarialTransport.connectMessagingException = new MessagingException("MTA connection refused (Port 25 blocked)");

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress("sender@example.com"));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("dest@example.com"));
            msg.setText("Test");

            MessagingException ex = assertThrows(MessagingException.class, () -> client.sendEmail(session, msg));
            assertTrue(ex.getMessage().contains("connection refused"));

            assertEquals(1, AdversarialTransport.connectCount.get(), "Phải gọi connect() 1 lần");
            assertEquals(0, AdversarialTransport.sendCount.get(), "Không được gọi sendMessage() khi connect() đã fail");
            assertEquals(1, AdversarialTransport.closeCount.get(), "Transport.close() PHẢI được gọi trong finally khi connect() ném MessagingException");
        }

        @Test
        @DisplayName("Sự cố 2: transport.connect() ném ngoại lệ thời gian chạy (RuntimeException) -> Transport.close() vẫn được gọi")
        void testTransportClosedWhenConnectThrowsRuntimeException() throws Exception {
            Session session = createAdversarialSession();
            SmtpClient client = new SmtpClient(createConfig(0, 1, 10));

            AdversarialTransport.connectRuntimeException = new IllegalStateException("SSL context crashed unexpectedly");

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress("sender@example.com"));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("dest@example.com"));
            msg.setText("Test");

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> client.sendEmail(session, msg));
            assertTrue(ex.getMessage().contains("SSL context crashed"));

            assertEquals(1, AdversarialTransport.connectCount.get());
            assertEquals(0, AdversarialTransport.sendCount.get());
            assertEquals(1, AdversarialTransport.closeCount.get(), "Transport.close() PHẢI được gọi ngay cả khi xảy ra RuntimeException bất thường");
        }

        @Test
        @DisplayName("Sự cố 3: transport.sendMessage() ném MessagingException (451) -> Transport.close() được giải phóng")
        void testTransportClosedWhenSendMessageFails() throws Exception {
            Session session = createAdversarialSession();
            SmtpClient client = new SmtpClient(createConfig(0, 1, 10));

            AdversarialTransport.sendMessageMessagingException = new MessagingException("451 4.3.0 Mailbox temporarily unavailable");

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress("sender@example.com"));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("dest@example.com"));
            msg.setText("Test");

            MessagingException ex = assertThrows(MessagingException.class, () -> client.sendEmail(session, msg));
            assertTrue(ex.getMessage().contains("451"));

            assertEquals(1, AdversarialTransport.connectCount.get());
            assertEquals(1, AdversarialTransport.sendCount.get());
            assertEquals(1, AdversarialTransport.closeCount.get(), "Transport.close() PHẢI được gọi khi sendMessage ném MessagingException");
        }

        @Test
        @DisplayName("Sự cố 4: transport.sendMessage() ném Error/OutOfMemory -> Transport.close() VẪN được đóng")
        void testTransportClosedWhenSendMessageThrowsError() throws Exception {
            Session session = createAdversarialSession();
            SmtpClient client = new SmtpClient(createConfig(0, 1, 10));

            AdversarialTransport.sendMessageError = new AssertionError("Simulated fatal JVM assertion");

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress("sender@example.com"));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("dest@example.com"));
            msg.setText("Test");

            assertThrows(AssertionError.class, () -> client.sendEmail(session, msg));

            assertEquals(1, AdversarialTransport.connectCount.get());
            assertEquals(1, AdversarialTransport.sendCount.get());
            assertEquals(1, AdversarialTransport.closeCount.get(), "Transport.close() BẮT BUỘC phải dọn dẹp kể cả khi ném Throwable/Error");
        }

        @Test
        @DisplayName("Sự cố 5 (Dual Exception): transport.close() ALSO ném lỗi -> Không làm nuốt mất ngoại lệ gốc của sendMessage")
        void testDualExceptionCloseFailureDoesNotMaskSendMessageException() throws Exception {
            Session session = createAdversarialSession();
            SmtpClient client = new SmtpClient(createConfig(0, 1, 10));

            // Cả sendMessage và close() đều ném lỗi
            AdversarialTransport.sendMessageMessagingException = new MessagingException("451 Temporary queue full");
            AdversarialTransport.closeMessagingException = new MessagingException("Socket transport reset by peer on close");

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress("sender@example.com"));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("dest@example.com"));
            msg.setText("Test");

            // Ngoại lệ ném ra ngoài phải là lỗi gốc của sendMessage (451), KHÔNG ĐƯỢC bị lỗi close() đè bẹp
            MessagingException ex = assertThrows(MessagingException.class, () -> client.sendEmail(session, msg));
            assertTrue(ex.getMessage().contains("451 Temporary queue full"),
                    "Lỗi gốc từ sendMessage phải được ném ra, không bị ghi đè bởi lỗi từ close(). Thực tế: " + ex.getMessage());

            assertEquals(1, AdversarialTransport.connectCount.get());
            assertEquals(1, AdversarialTransport.sendCount.get());
            assertEquals(1, AdversarialTransport.closeCount.get());
        }

        @Test
        @DisplayName("Chu kỳ Retry: Mỗi lần retry mở kết nối mới và đóng kết nối cũ -> không tích tụ socket")
        void testTransportLifecyclePerRetryCycle() throws Exception {
            int maxRetry = 3;
            SmtpConfig config = createConfig(maxRetry, 1, 10);
            Session session = createAdversarialSession();

            // Giả lập lỗi tạm thời ở lần 1, lần 2; thành công ở lần 3
            AtomicInteger attempts = new AtomicInteger(0);
            SmtpClient client = new SmtpClient(config) {
                @Override
                public void sendEmail(Session session, MimeMessage message) throws MessagingException {
                    int att = attempts.incrementAndGet();
                    if (att < 3) {
                        AdversarialTransport.sendMessageMessagingException = new MessagingException("Temporary network hitch", new SocketTimeoutException("Timeout"));
                    } else {
                        AdversarialTransport.sendMessageMessagingException = null;
                    }
                    super.sendEmail(session, message);
                }
            };
            client.setSleeper(millis -> {}); // Zero-latency sleeper

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress("sender@example.com"));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("dest@example.com"));
            msg.setText("Multi retry test");

            client.sendWithRetry(session, msg);

            // 3 lần gọi sendEmail -> 3 lần connect, 3 lần close
            assertEquals(3, attempts.get());
            assertEquals(3, AdversarialTransport.connectCount.get(), "Mỗi lần thử phải thực hiện connect()");
            assertEquals(3, AdversarialTransport.sendCount.get(), "Mỗi lần thử phải thực hiện sendMessage()");
            assertEquals(3, AdversarialTransport.closeCount.get(), "Mỗi lần thử PHẢI đóng kết nối độc lập, không rò rỉ!");
        }

        @Test
        @DisplayName("Kiệt quệ Retry (Exhaustion): Gặp lỗi bền bỉ -> Tất cả các lần thử (1 + maxRetry) đều đóng transport an toàn")
        void testTransportCleanupOnFullExhaustion() {
            int maxRetry = 2;
            SmtpConfig config = createConfig(maxRetry, 1, 10);
            Session session = createAdversarialSession();

            AdversarialTransport.sendMessageMessagingException = new MessagingException("450 Busy", new SocketTimeoutException("Timed out"));

            SmtpClient client = new SmtpClient(config);
            client.setSleeper(millis -> {});

            MimeMessage msg = new MimeMessage(session);
            assertDoesNotThrow(() -> {
                msg.setFrom(new InternetAddress("sender@example.com"));
                msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("dest@example.com"));
                msg.setText("Exhaustion test");
            });

            assertThrows(MessagingException.class, () -> client.sendWithRetry(session, msg));

            // 1 lần ban đầu + 2 lần retry = 3 lần
            assertEquals(3, AdversarialTransport.connectCount.get());
            assertEquals(3, AdversarialTransport.closeCount.get(), "Cả 3 lần thử đều phải được đóng an toàn");
        }

        @Test
        @DisplayName("Bảo vệ trước khi mở kết nối: Message null hoặc không có người nhận -> Không tạo socket Transport")
        void testNoTransportCreatedOnInvalidInput() throws Exception {
            Session session = createAdversarialSession();
            SmtpClient client = new SmtpClient(createConfig(0, 1, 10));

            // Message null
            assertThrows(IllegalArgumentException.class, () -> client.sendEmail(session, null));
            assertEquals(0, AdversarialTransport.instanceCount.get(), "Không được lấy transport khi message null");

            // Message không có người nhận
            MimeMessage msgNoRecipients = new MimeMessage(session);
            msgNoRecipients.setFrom(new InternetAddress("sender@example.com"));
            assertThrows(MessagingException.class, () -> client.sendEmail(session, msgNoRecipients));
            assertEquals(0, AdversarialTransport.instanceCount.get(), "Không được lấy transport khi recipients rỗng");
        }
    }

    // =========================================================================
    // 5. REL-06 / REL-07: Attachment Stream Lifecycle & Windows File Lock Release
    // =========================================================================
    @Nested
    @DisplayName("REL-06 & REL-07: Quản lý Stream file đính kèm, giải phóng File Lock trên Windows và RFC 5322")
    class AttachmentStreamAndRfcComplianceTests {

        @Test
        @DisplayName("Kiểm chứng thực nghiệm giải phóng File Lock trên Windows sau khi resolveAttachment")
        void testWindowsFileLockReleaseAfterResolveAttachment() throws Exception {
            // Tạo một tệp tin tạm thời trong thư mục cho phép
            File tempFile = new File("test_lock_release.txt");
            try {
                try (FileWriter fw = new FileWriter(tempFile)) {
                    fw.write("Nội dung thử nghiệm file lock release");
                }
                assertTrue(tempFile.exists());

                // Thực hiện resolveAttachment
                File resolved = EmailMessageBuilder.resolveAttachment(tempFile.getName());
                assertNotNull(resolved);
                assertEquals(tempFile.getCanonicalFile(), resolved);

                // Trên hệ điều hành Windows, nếu InputStream bị rò rỉ (chưa đóng),
                // lệnh Files.delete hoặc tempFile.delete() sẽ THẤT BẠI với IOException
                // (The process cannot access the file because it is being used by another process).
                // Kiểm chứng tệp tin có thể xóa ngay lập tức:
                assertDoesNotThrow(() -> Files.delete(tempFile.toPath()),
                        "Tệp tin phải xóa được ngay lập tức trên Windows nếu FileInputStream đã đóng an toàn trong try-with-resources");

            } finally {
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            }
        }

        @Test
        @DisplayName("File đính kèm có kích thước 0-byte (Empty file) được chấp nhận và giải phóng stream an toàn")
        void testZeroByteAttachmentStreamHandling() throws Exception {
            File zeroByteFile = new File("empty_attachment_test.txt");
            try {
                assertTrue(zeroByteFile.createNewFile() || zeroByteFile.exists());
                assertEquals(0L, zeroByteFile.length());

                File resolved = EmailMessageBuilder.resolveAttachment(zeroByteFile.getName());
                assertNotNull(resolved);
                assertEquals(0L, resolved.length());

                // Xóa tệp tin ngay lập tức để chứng minh không bị khóa luồng
                assertTrue(zeroByteFile.delete(), "Tệp tin 0-byte phải xóa được ngay, không bị kẹt descriptor");
            } finally {
                if (zeroByteFile.exists()) {
                    zeroByteFile.delete();
                }
            }
        }

        @Test
        @DisplayName("REL-07: Tuân thủ đầy đủ RFC 5322 (Origination Date) và RFC 2183 (Explicit Attachment Disposition)")
        void testRfc5322FullCompliance() throws Exception {
            Session session = Session.getInstance(new Properties());
            File sample = new File("attachments/sample.txt");

            long beforeCreationTime = System.currentTimeMillis();

            MimeMessage message = EmailMessageBuilder.createMimeMessage(
                    session,
                    "alice@example.com",
                    "bob@example.com",
                    "RFC 5322 Subject",
                    "<p>Hello World</p>",
                    Collections.singletonList(sample)
            );

            // 1. Kiểm tra RFC 5322: Date header
            Date sentDate = message.getSentDate();
            assertNotNull(sentDate, "RFC 5322 bắt buộc phải có trường Date (Sent Date)");
            long timeDiff = Math.abs(sentDate.getTime() - beforeCreationTime);
            assertTrue(timeDiff < 5000, "Sent Date (" + sentDate + ") phải phản ánh thời gian khởi tạo thực tế");

            // 2. Kiểm tra RFC 5322: From & To headers
            assertEquals(1, message.getFrom().length);
            assertEquals("alice@example.com", ((InternetAddress) message.getFrom()[0]).getAddress());
            assertEquals("bob@example.com", ((InternetAddress) message.getAllRecipients()[0]).getAddress());

            // 3. Kiểm tra RFC 2183: Content-Disposition: attachment
            MimeMultipart multipart = (MimeMultipart) message.getContent();
            assertEquals(2, multipart.getCount());

            Part attachPart = multipart.getBodyPart(1);
            assertNotNull(attachPart.getDisposition(), "File đính kèm phải có Content-Disposition");
            assertEquals("attachment", attachPart.getDisposition().toLowerCase(),
                    "Content-Disposition phải là explicit 'attachment' theo RFC 2183");
            assertNotNull(attachPart.getFileName(), "Tên file đính kèm không được null");
        }
    }
}
