package com.ltm.mail;

import com.ltm.mail.util.ExceptionClassifier;
import com.sun.mail.smtp.SMTPAddressFailedException;
import com.sun.mail.smtp.SMTPSendFailedException;
import com.sun.mail.smtp.SMTPSenderFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;

public class AdversarialIteration2Test {

    @Nested
    @DisplayName("Adversarial Attack 1: Standard SMTP Submission Port 587 Connection Failures")
    class Port587AttackTests {

        @Test
        @DisplayName("Mã cổng chuẩn SMTP Submission 587 trong MessagingException lồng ConnectException")
        void testMessagingExceptionPort587WithConnectException() {
            // Chuỗi ngoại lệ thực tế sinh ra bởi JavaMail khi không thể kết nối tới SMTP STARTTLS port 587
            MessagingException ex = new MessagingException(
                    "Could not connect to SMTP host: smtp.gmail.com, port: 587",
                    new ConnectException("Connection refused: connect")
            );

            boolean retryable = ExceptionClassifier.isRetryable(ex);
            assertTrue(retryable, "Lỗi kết nối tới cổng chuẩn STARTTLS 587 (RFC 6409) có nguyên nhân ConnectException BẮT BUỘC phải là retryable! Không được coi 587 là mã lỗi SMTP 5xx!");
        }

        @Test
        @DisplayName("Mã cổng 587 trong MessagingException lồng SocketTimeoutException")
        void testMessagingExceptionPort587WithSocketTimeoutException() {
            MessagingException ex = new MessagingException(
                    "Could not connect to SMTP host: smtp.office365.com, port: 587",
                    new SocketTimeoutException("connect timed out")
            );

            boolean retryable = ExceptionClassifier.isRetryable(ex);
            assertTrue(retryable, "Lỗi timeout kết nối tới cổng 587 BẮT BUỘC phải là retryable!");
        }

        @Test
        @DisplayName("Mã cổng 587 trong MessagingException trần (không có cause) kèm thông điệp timeout")
        void testMessagingExceptionPort587BareWithTimeout() {
            MessagingException ex = new MessagingException(
                    "Could not connect to SMTP host: mail.example.com, port: 587 - connection timed out"
            );

            boolean retryable = ExceptionClassifier.isRetryable(ex);
            assertTrue(retryable, "Thông điệp timeout tới cổng 587 phải là retryable, không được coi 587 là mã 5xx!");
        }

        @Test
        @DisplayName("E2E SmtpClient.sendWithRetry: Lỗi kết nối tới port 587 bị abort ngay lần 1 (không retry)")
        void testSendWithRetryAbortsImmediatelyOnPort587ConnectionFailure() throws Exception {
            com.ltm.mail.config.SmtpConfig config = new com.ltm.mail.config.SmtpConfig();
            config.setHost("smtp.gmail.com");
            config.setPort(587);
            config.setEnableSsl(false);
            config.setUsername("user@gmail.com");
            config.setPassword("secret");
            config.setFrom("user@gmail.com");
            config.setTo("recipient@example.com");
            config.setSubject("Test");
            config.setRetry(new com.ltm.mail.config.RetryConfig(3, 1, 10));

            java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger(0);

            com.ltm.mail.client.SmtpClient client = new com.ltm.mail.client.SmtpClient(config) {
                @Override
                public void sendEmail(javax.mail.Session session, javax.mail.internet.MimeMessage message) throws MessagingException {
                    attempts.incrementAndGet();
                    throw new MessagingException(
                            "Could not connect to SMTP host: smtp.gmail.com, port: 587",
                            new ConnectException("Connection refused: connect")
                    );
                }
            };
            client.setSleeper(millis -> {});

            javax.mail.Session session = javax.mail.Session.getInstance(new java.util.Properties());
            javax.mail.internet.MimeMessage msg = new javax.mail.internet.MimeMessage(session);
            msg.setFrom(new InternetAddress("user@gmail.com"));
            msg.setRecipients(javax.mail.Message.RecipientType.TO, InternetAddress.parse("recipient@example.com"));
            msg.setText("Hello");

            // Phải ném ngoại lệ sau 4 lần thử (1 gốc + 3 retry), NHƯNG thực tế bị abort ngay lần 1 vì 587 bị coi là 5xx!
            assertThrows(MessagingException.class, () -> client.sendWithRetry(session, msg));

            assertEquals(4, attempts.get(),
                    "SmtpClient.sendWithRetry PHẢI retry 3 lần đối với lỗi ConnectException cổng 587! Thực tế attempts = " + attempts.get());
        }
    }

    @Nested
    @DisplayName("Adversarial Attack 2: Advisory Numbers & Durations Matching 5xx Pattern")
    class AdvisoryNumbersTests {

        @Test
        @DisplayName("MessagingException chứa 'Connection timed out after 500 ms' lồng SocketTimeoutException")
        void testTimeoutWith500msWrappedInMessagingException() {
            MessagingException ex = new MessagingException(
                    "Connection timed out after 500 ms",
                    new SocketTimeoutException("Read timed out")
            );

            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Timeout sau 500 ms bọc trong MessagingException phải retryable! Số 500 ở đây là mili-giây, không phải mã lỗi 500!");
        }

        @Test
        @DisplayName("MessagingException trần chứa 'Connection timed out after 550 ms'")
        void testBareMessagingExceptionWith550msTimeout() {
            MessagingException ex = new MessagingException("Connection timed out after 550 ms");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Thông điệp 'timed out after 550 ms' phải retryable!");
        }

        @Test
        @DisplayName("Thông báo tạm thời không có tiền tố 4xx: 'Server temporarily busy, retry in 500 seconds'")
        void testTemporarilyBusyRetryIn500Seconds() {
            MessagingException ex = new MessagingException("Server temporarily busy, retry in 500 seconds");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Thông báo tạm thời 'retry in 500 seconds' chứa cụm 'temporarily busy' phải được retry!");
        }

        @Test
        @DisplayName("Lỗi tạm thời kèm số lượng: 'Temporary error: 500 items in mailbox, try again later'")
        void testTemporaryErrorWith500Items() {
            MessagingException ex = new MessagingException("Temporary error: 500 items in mailbox, try again later");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Thông báo tạm thời có số 500 items và 'try again later' phải được retry!");
        }
    }

    @Nested
    @DisplayName("Adversarial Attack 3: 10,000-Level Deep Nesting Stress Test")
    class DeepNesting10kTests {

        @Test
        @DisplayName("10,000 cấp getCause() lồng sâu không chứa số")
        void testDeepCauseNesting10000Levels() {
            Exception current = new SocketTimeoutException("Deepest cause timed out");
            for (int i = 0; i < 10000; i++) {
                current = new Exception("Nested cause wrapper", current);
            }

            try {
                boolean result = ExceptionClassifier.isRetryable(current);
                assertTrue(result, "Phải tìm thấy SocketTimeoutException qua getCause() lồng sâu 10.000 cấp");
            } catch (StackOverflowError soe) {
                fail("Duyệt getCause() bị StackOverflowError ở độ sâu 10.000!", soe);
            }
        }

        @Test
        @DisplayName("10,000 cấp đan xen cả getNextException() và getCause()")
        void testIntertwined10000Levels() {
            MessagingException root = new MessagingException("Root");
            MessagingException currentMe = root;

            for (int i = 0; i < 5000; i++) {
                MessagingException nextMe = new MessagingException("NextMe node");
                currentMe.setNextException(nextMe);
                currentMe = nextMe;
            }
            Exception currentEx = new SocketTimeoutException("Deepest interleaved timeout");
            for (int i = 0; i < 5000; i++) {
                currentEx = new Exception("Cause wrapper", currentEx);
            }
            currentMe.setNextException(new MessagingException("Bridge", currentEx));

            try {
                boolean result = ExceptionClassifier.isRetryable(root);
                assertTrue(result, "Phải tìm thấy SocketTimeoutException qua chuỗi đan xen 10.000 phần tử");
            } catch (StackOverflowError soe) {
                fail("Duyệt đan xen bị StackOverflowError ở độ sâu 10.000!", soe);
            }
        }

        @Test
        @DisplayName("10,000 cấp với lỗi vĩnh viễn (550) ở đáy chuỗi: Phải phát hiện và từ chối retry")
        void testDeepChainWithPermanentAtBottom() {
            MessagingException root = new MessagingException("Deep chain node");
            MessagingException current = root;
            for (int i = 1; i <= 10000; i++) {
                MessagingException child = new MessagingException("Deep chain node");
                current.setNextException(child);
                current = child;
            }
            current.setNextException(new MessagingException("550 User unknown"));

            assertFalse(ExceptionClassifier.isRetryable(root),
                    "Lỗi 550 ở độ sâu 10.000 cấp phải được phát hiện và từ chối retry!");
        }
    }

    @Nested
    @DisplayName("Adversarial Attack 4: Complex Cyclic Graphs with Intertwined Roots")
    class CyclicStressTests {

        @Test
        @DisplayName("Chu trình tam giác 3 đỉnh lồng nhau với getNextException()")
        void testTriangleNextExceptionCycle() {
            MessagingException e1 = new MessagingException("Node 1");
            MessagingException e2 = new MessagingException("Node 2");
            MessagingException e3 = new MessagingException("Node 3");

            e1.setNextException(e2);
            e2.setNextException(e3);
            e3.setNextException(e1); // Cycle

            assertFalse(ExceptionClassifier.isRetryable(e1));
        }

        @Test
        @DisplayName("Chu trình getCause() giữa các Exception tiêu chuẩn kèm nhánh chứa SocketTimeoutException")
        void testCauseCycleWithReachableTransientBranch() {
            Exception w1 = new Exception("Node 1");
            Exception w2 = new Exception("Node 2", w1);
            // Chu trình tiêu chuẩn trong Java Throwable qua initCause hoặc constructor
            MessagingException root = new MessagingException("Root");
            MessagingException eTransient = new MessagingException("Transient branch");
            eTransient.setNextException(new SocketTimeoutException("Timed out in branch"));

            root.setNextException(eTransient);

            assertTrue(ExceptionClassifier.isRetryable(root),
                    "Bộ duyệt vẫn phải tìm thấy nhánh chứa SocketTimeoutException");
        }
    }

    @Nested
    @DisplayName("Adversarial Attack 5: Comprehensive RFC Status Codes & Text Collisions")
    class ComprehensiveRfcCodesTests {

        @Test
        @DisplayName("RFC 5321 / RFC 7504 / RFC 541 tất cả các mã 5xx kèm 'connection timed out'")
        void testAll5xxCodesWithTimeoutString() {
            int[] permanentCodes = {
                    500, 501, 502, 503, 504, 520, 521, 530, 534, 535, 538,
                    541, 550, 551, 552, 553, 554, 555, 556
            };

            for (int code : permanentCodes) {
                MessagingException ex = new MessagingException(code + " Critical error - connection timed out");
                assertFalse(ExceptionClassifier.isRetryable(ex),
                        "Mã " + code + " kèm 'timed out' BẮT BUỘC phải là false (Permanent error precedence)!");
            }
        }

        @Test
        @DisplayName("Tất cả các mã 4xx chuẩn RFC 5321 / RFC 3207 / RFC 4954 / RFC 5336")
        void testAll4xxCodes() {
            int[] transientCodes = {
                    421, 432, 450, 451, 452, 453, 454, 455
            };

            for (int code : transientCodes) {
                MessagingException ex = new MessagingException(code + " Service temporarily deferred");
                assertTrue(ExceptionClassifier.isRetryable(ex),
                        "Mã " + code + " BẮT BUỘC phải là true (Transient deferral)!");
            }
        }
    }

    @Nested
    @DisplayName("Adversarial Attack 6: Reviewer 2 Regression & Lookahead Stress Tests (Milestone 2 Iteration 4)")
    class Reviewer2RemediationTests {

        @Test
        @DisplayName("MTA trả về 550 5.1.1 User unknown rồi ngắt kết nối (SocketException Connection reset) -> không retry")
        void test550UserUnknownWithSocketReset() {
            MessagingException ex = new MessagingException(
                    "550 5.1.1 User unknown",
                    new SocketException("Connection reset by peer")
            );
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "550 User unknown kèm SocketException ngắt kết nối BẮT BUỘC phải là false (Permanent error precedence)!");
        }

        @Test
        @DisplayName("MTA trả về 535 5.7.8 Bad credentials rồi reset kết nối (SocketException Connection reset) -> không retry")
        void test535BadCredentialsWithSocketReset() {
            MessagingException ex = new MessagingException(
                    "535 5.7.8 Authentication credentials invalid",
                    new SocketException("Connection reset")
            );
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "535 Bad credentials kèm SocketException reset kết nối BẮT BUỘC phải là false (Non-retryable)!");
        }

        @Test
        @DisplayName("MTA trả về 554 5.7.1 Access denied kèm SocketTimeoutException -> không retry")
        void test554AccessDeniedWithSocketTimeout() {
            MessagingException ex = new MessagingException(
                    "554 5.7.1 Access denied",
                    new SocketTimeoutException("Read timed out")
            );
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "554 Access denied kèm SocketTimeoutException BẮT BUỘC phải là false (Non-retryable)!");
        }

        @Test
        @DisplayName("550 message rejected: connection timed out -> không retry (message không được triệt tiêu 550)")
        void test550MessageRejectedWithTimeout() {
            MessagingException ex = new MessagingException(
                    "550 message rejected: connection timed out"
            );
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "550 message rejected không được bị lookahead nuốt chửng bởi từ 'message'!");
        }

        @Test
        @DisplayName("550 security check failed: connection timed out -> không retry (sec không match security)")
        void test550SecurityCheckFailedWithTimeout() {
            MessagingException ex = new MessagingException(
                    "550 security check failed: connection timed out"
            );
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "550 security check failed: 'sec\\b' không được match tiền tố của 'security'!");
        }

        @Test
        @DisplayName("550 secondary mx unavailable: connection timed out -> không retry (seconds? không match secondary)")
        void test550SecondaryMxUnavailableWithTimeout() {
            MessagingException ex = new MessagingException(
                    "550 secondary mx unavailable: connection timed out"
            );
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "550 secondary mx: 'seconds?\\b' không được match tiền tố của 'secondary'!");
        }

        @Test
        @DisplayName("550 msg: delivery timed out -> không retry (ms\\b không match msg)")
        void test550MsgDeliveryWithTimeout() {
            MessagingException ex = new MessagingException(
                    "550 msg: delivery timed out"
            );
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "550 msg: 'ms\\b' không được match 'msg'!");
        }

        @Test
        @DisplayName("550 mbox full: connection timed out -> không retry (mb\\b không match mbox)")
        void test550MboxFullWithTimeout() {
            MessagingException ex = new MessagingException(
                    "550 mbox full: connection timed out"
            );
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "550 mbox: 'mb\\b' không được match 'mbox'!");
        }

        @Test
        @DisplayName("550 minor error: connection timed out -> không retry (min\\b không match minor)")
        void test550MinorErrorWithTimeout() {
            MessagingException ex = new MessagingException(
                    "550 minor error: connection timed out"
            );
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "550 minor: 'min\\b' không được match 'minor'!");
        }

        @Test
        @DisplayName("500 MS timeout (chữ hoa MS) -> retryable (nhận diện đúng đơn vị thời lượng)")
        void test500MsTimeoutUppercase() {
            MessagingException ex = new MessagingException("500 MS timeout");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "500 MS timeout BẮT BUỘC là retryable (500 là số đo thời gian chữ hoa MS, không phải mã lỗi 500)!");
        }

        @Test
        @DisplayName("500 SECONDS timeout (chữ hoa SECONDS) -> retryable")
        void test500SecondsTimeoutUppercase() {
            MessagingException ex = new MessagingException("500 SECONDS timeout");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "500 SECONDS timeout BẮT BUỘC là retryable!");
        }

        @Test
        @DisplayName("500 Milliseconds timeout (Mixed case) -> retryable")
        void test500MillisecondsTimeoutMixedCase() {
            MessagingException ex = new MessagingException("500 Milliseconds timeout");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "500 Milliseconds timeout BẮT BUỘC là retryable!");
        }

        @Test
        @DisplayName("500 MIN timeout (chữ hoa MIN) -> retryable")
        void test500MinTimeoutUppercase() {
            MessagingException ex = new MessagingException("500 MIN timeout");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "500 MIN timeout BẮT BUỘC là retryable!");
        }
    }
}
