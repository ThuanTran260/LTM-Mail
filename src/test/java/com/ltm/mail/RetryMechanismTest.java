package com.ltm.mail;

import com.ltm.mail.client.SmtpClient;
import com.ltm.mail.config.RetryConfig;
import com.ltm.mail.config.SmtpConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.mail.AuthenticationFailedException;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class RetryMechanismTest {

    @Nested
    @DisplayName("REL-05: Kiểm tra tính toán Exponential Backoff và Jitter")
    class BackoffCalculationTests {

        @Test
        @DisplayName("Kiểm tra công thức lũy thừa baseDelay * 2^(attempt - 1) với jitter = 0")
        void testDeterministicExponentialBackoffProgression() {
            int baseDelaySec = 2;
            int maxDelaySec = 60;

            // Attempt 1: 2 * 2^0 = 2s = 2000ms
            assertEquals(2000L, SmtpClient.calculateBackoffWithJitterMillis(1, baseDelaySec, maxDelaySec, 0L));

            // Attempt 2: 2 * 2^1 = 4s = 4000ms
            assertEquals(4000L, SmtpClient.calculateBackoffWithJitterMillis(2, baseDelaySec, maxDelaySec, 0L));

            // Attempt 3: 2 * 2^2 = 8s = 8000ms
            assertEquals(8000L, SmtpClient.calculateBackoffWithJitterMillis(3, baseDelaySec, maxDelaySec, 0L));

            // Attempt 4: 2 * 2^3 = 16s = 16000ms
            assertEquals(16000L, SmtpClient.calculateBackoffWithJitterMillis(4, baseDelaySec, maxDelaySec, 0L));

            // Attempt 5: 2 * 2^4 = 32s = 32000ms
            assertEquals(32000L, SmtpClient.calculateBackoffWithJitterMillis(5, baseDelaySec, maxDelaySec, 0L));

            // Attempt 6: 2 * 2^5 = 64s > 60s -> Capped tại maxDelaySec (60000ms)
            assertEquals(60000L, SmtpClient.calculateBackoffWithJitterMillis(6, baseDelaySec, maxDelaySec, 0L));
        }

        @Test
        @DisplayName("Kiểm tra Jitter ngẫu nhiên nằm trong khoảng hợp lệ và không vượt trần maxDelaySec")
        void testRandomizedJitterBounds() {
            int baseDelaySec = 2;
            int maxDelaySec = 60;

            long minObserved = Long.MAX_VALUE;
            long maxObserved = Long.MIN_VALUE;

            for (int i = 0; i < 100; i++) {
                long delay = SmtpClient.calculateBackoffWithJitterMillis(1, baseDelaySec, maxDelaySec);

                // Attempt 1: cappedBackoff = 2000ms, maxJitter = min(1000, 2000/5=400) = 400ms
                assertTrue(delay >= 2000L, "Delay không được nhỏ hơn backoff cơ bản: " + delay);
                assertTrue(delay <= 2400L, "Delay không được vượt quá backoff + jitter tối đa: " + delay);

                minObserved = Math.min(minObserved, delay);
                maxObserved = Math.max(maxObserved, delay);
            }

            // Kiểm tra tính ngẫu nhiên: Sau 100 lần chạy, giá trị nhỏ nhất và lớn nhất phải có sự biến thiên
            assertTrue(maxObserved > minObserved, "Jitter phải tạo ra sự biến thiên ngẫu nhiên (chống thundering herd)");
        }

        @Test
        @DisplayName("Nghiêm ngặt áp dụng trần maxDelaySec (Ceiling enforcement)")
        void testStrictMaxDelayCeilingEnforcement() {
            int baseDelaySec = 5;
            int maxDelaySec = 10; // Trần rất chặt: 10 giây

            for (int attempt = 1; attempt <= 20; attempt++) {
                long delay = SmtpClient.calculateBackoffWithJitterMillis(attempt, baseDelaySec, maxDelaySec);
                assertTrue(delay <= 10000L, "Delay ở lần thử " + attempt + " (" + delay + "ms) vượt quá trần 10000ms!");
            }

            // Ngay cả khi truyền jitter cực lớn, tổng delay vẫn không được vượt quá maxDelaySec
            long delayWithBigJitter = SmtpClient.calculateBackoffWithJitterMillis(1, 5, 10, 50000L);
            assertEquals(10000L, delayWithBigJitter, "Ceiling maxDelaySec phải có hiệu lực tuyệt đối");
        }

        @Test
        @DisplayName("Chống tràn số nguyên (Bit-shift integer overflow prevention)")
        void testBitShiftOverflowGuards() {
            int baseDelaySec = 2;
            int maxDelaySec = 60;

            // Attempt 30, 31, 32, 64, Integer.MAX_VALUE
            int[] extremeAttempts = {30, 31, 32, 33, 64, 100, Integer.MAX_VALUE};

            for (int attempt : extremeAttempts) {
                long delay = SmtpClient.calculateBackoffWithJitterMillis(attempt, baseDelaySec, maxDelaySec);
                assertTrue(delay > 0, "Delay cho attempt " + attempt + " không được âm (bị tràn số): " + delay);
                assertEquals(60000L, delay, "Delay cho attempt " + attempt + " phải được clamp tại maxDelay: " + delay);
            }

            // Boundary values nhỏ hơn 1
            long delayZero = SmtpClient.calculateBackoffWithJitterMillis(0, baseDelaySec, maxDelaySec, 0L);
            assertEquals(2000L, delayZero, "Attempt <= 0 phải được xử lý an toàn như attempt 1");

            long delayNegative = SmtpClient.calculateBackoffWithJitterMillis(-10, baseDelaySec, maxDelaySec, 0L);
            assertEquals(2000L, delayNegative, "Attempt âm phải được xử lý an toàn như attempt 1");
        }
    }

    @Nested
    @DisplayName("Kiểm tra hành vi vòng lặp Retry (Retry Loop Execution)")
    class RetryLoopExecutionTests {

        private SmtpConfig createTestConfig(int maxRetry, int baseDelay, int maxDelay) {
            SmtpConfig config = new SmtpConfig();
            config.setHost("smtp.example.com");
            config.setPort(465);
            config.setUsername("test@example.com");
            config.setPassword("secret");
            config.setFrom("test@example.com");
            config.setTo("dest@example.com");
            config.setSubject("Test Subject");
            config.setRetry(new RetryConfig(maxRetry, baseDelay, maxDelay));
            return config;
        }

        @Test
        @DisplayName("Gửi thành công ở lần thử đầu tiên -> 0 lần retry, 0 lần sleep")
        void testSuccessfulSendFirstAttempt() throws Exception {
            SmtpConfig config = createTestConfig(3, 2, 60);
            List<Long> sleepDelays = new ArrayList<>();
            AtomicInteger attempts = new AtomicInteger(0);

            SmtpClient client = new SmtpClient(config) {
                @Override
                public void sendEmail(Session session, MimeMessage message) {
                    attempts.incrementAndGet();
                    // Thành công ngay
                }
            };
            client.setSleeper(sleepDelays::add);

            Session session = Session.getInstance(new Properties());
            MimeMessage msg = new MimeMessage(session);

            client.sendWithRetry(session, msg);

            assertEquals(1, attempts.get(), "Chỉ được gọi sendEmail đúng 1 lần");
            assertTrue(sleepDelays.isEmpty(), "Không được sleep khi thành công ở lần 1");
        }

        @Test
        @DisplayName("Lỗi tạm thời (SocketTimeoutException) -> Thử lại và thành công ở lần 3")
        void testTransientFailureRecoversOnAttempt3() throws Exception {
            SmtpConfig config = createTestConfig(3, 2, 60);
            List<Long> sleepDelays = new ArrayList<>();
            AtomicInteger attempts = new AtomicInteger(0);

            SmtpClient client = new SmtpClient(config) {
                @Override
                public void sendEmail(Session session, MimeMessage message) throws MessagingException {
                    int count = attempts.incrementAndGet();
                    if (count < 3) {
                        throw new MessagingException("Network glitch", new SocketTimeoutException("Read timed out"));
                    }
                    // Lần 3 thành công
                }
            };
            client.setSleeper(sleepDelays::add);

            Session session = Session.getInstance(new Properties());
            MimeMessage msg = new MimeMessage(session);

            client.sendWithRetry(session, msg);

            assertEquals(3, attempts.get(), "Tổng số lần thử phải là 3");
            assertEquals(2, sleepDelays.size(), "Phải sleep 2 lần trước lần thử 2 và lần thử 3");

            // Độ trễ lần 1 >= 2000ms, lần 2 >= 4000ms
            assertTrue(sleepDelays.get(0) >= 2000L);
            assertTrue(sleepDelays.get(1) >= 4000L);
        }

        @Test
        @DisplayName("Lỗi vĩnh viễn (AuthenticationFailedException) -> Lập tức dừng ở lần 1, không retry, không sleep")
        void testPermanentFailureAbortsImmediately() {
            SmtpConfig config = createTestConfig(3, 2, 60);
            List<Long> sleepDelays = new ArrayList<>();
            AtomicInteger attempts = new AtomicInteger(0);

            SmtpClient client = new SmtpClient(config) {
                @Override
                public void sendEmail(Session session, MimeMessage message) throws MessagingException {
                    attempts.incrementAndGet();
                    throw new AuthenticationFailedException("535 5.7.8 Bad credentials");
                }
            };
            client.setSleeper(sleepDelays::add);

            Session session = Session.getInstance(new Properties());
            MimeMessage msg = new MimeMessage(session);

            AuthenticationFailedException ex = assertThrows(AuthenticationFailedException.class, () ->
                    client.sendWithRetry(session, msg)
            );

            assertEquals(1, attempts.get(), "Lỗi vĩnh viễn phải dừng ngay tại lần thử 1");
            assertTrue(sleepDelays.isEmpty(), "Không được sleep khi gặp lỗi vĩnh viễn");
            assertTrue(ex.getMessage().contains("535"));
        }

        @Test
        @DisplayName("Lỗi tạm thời kéo dài vượt quá maxRetry -> Thất bại hoàn toàn sau 1 + maxRetry lần thử")
        void testTransientFailureExhaustsMaxRetry() {
            int maxRetry = 3;
            SmtpConfig config = createTestConfig(maxRetry, 1, 10);
            List<Long> sleepDelays = new ArrayList<>();
            AtomicInteger attempts = new AtomicInteger(0);

            SmtpClient client = new SmtpClient(config) {
                @Override
                public void sendEmail(Session session, MimeMessage message) throws MessagingException {
                    attempts.incrementAndGet();
                    throw new MessagingException("Persistent 450", new SocketTimeoutException("Timed out"));
                }
            };
            client.setSleeper(sleepDelays::add);

            Session session = Session.getInstance(new Properties());
            MimeMessage msg = new MimeMessage(session);

            MessagingException thrown = assertThrows(MessagingException.class, () ->
                    client.sendWithRetry(session, msg)
            );

            // 1 lần thử ban đầu + 3 lần retry = 4 lần gọi
            assertEquals(1 + maxRetry, attempts.get(), "Phải thực hiện đúng 1 lần thử ban đầu + maxRetry lần thử lại");
            assertEquals(maxRetry, sleepDelays.size(), "Số lần sleep phải đúng bằng maxRetry");
            assertNotNull(thrown);
        }

        @Test
        @DisplayName("Luồng bị gián đoạn (InterruptedException) khi đang chờ retry -> Khôi phục cờ ngắt và ném RuntimeException")
        void testThreadInterruptionDuringSleep() {
            SmtpConfig config = createTestConfig(3, 2, 60);

            SmtpClient client = new SmtpClient(config) {
                @Override
                public void sendEmail(Session session, MimeMessage message) throws MessagingException {
                    throw new MessagingException("Timeout", new SocketTimeoutException("Read timed out"));
                }
            };
            client.setSleeper(millis -> {
                throw new InterruptedException("Thread interrupted manually for test");
            });

            Session session = Session.getInstance(new Properties());
            MimeMessage msg = new MimeMessage(session);

            RuntimeException re = assertThrows(RuntimeException.class, () ->
                    client.sendWithRetry(session, msg)
            );

            assertTrue(re.getMessage().contains("bị ngắt quãng"));
            assertTrue(Thread.currentThread().isInterrupted(), "Cờ ngắt thread phải được khôi phục");

            // Reset interrupt flag for other tests
            Thread.interrupted();
        }
    }
}
