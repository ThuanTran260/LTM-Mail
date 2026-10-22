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
import java.net.SocketTimeoutException;

import static org.junit.jupiter.api.Assertions.*;

public class AdversarialExceptionClassifierTest {

    @Nested
    @DisplayName("Challenge Category 1: Deep Exception Nesting (StackOverflow Risk)")
    class DeepNestingTests {

        @Test
        @DisplayName("Chuỗi ngoại lệ lồng sâu 10000 cấp (không chứa số): Kiểm tra nguy cơ StackOverflowError khi duyệt đệ quy")
        void testDeepNesting3000Levels() {
            MessagingException root = new MessagingException("Deep chain node");
            MessagingException current = root;
            for (int i = 1; i <= 10000; i++) {
                MessagingException child = new MessagingException("Deep chain node");
                current.setNextException(child);
                current = child;
            }
            current.setNextException(new SocketTimeoutException("Deep timed out"));

            try {
                boolean result = ExceptionClassifier.isRetryable(root);
                assertTrue(result, "Phải tìm thấy SocketTimeoutException ở sâu và trả về true");
            } catch (StackOverflowError soe) {
                fail("Duyệt đệ quy collectExceptions bị StackOverflowError ở độ sâu 10000! Cần chuyển sang duyệt lặp (Iterative Queue/Stack).", soe);
            }
        }

        @Test
        @DisplayName("Chuỗi ngoại lệ getCause() lồng sâu 3000 cấp (không chứa số): Kiểm tra nguy cơ StackOverflowError")
        void testDeepCauseNesting3000Levels() {
            Exception current = new SocketTimeoutException("Deepest cause timed out");
            for (int i = 0; i < 3000; i++) {
                current = new Exception("Nested cause wrapper", current);
            }
            final Exception root = current;

            try {
                boolean result = ExceptionClassifier.isRetryable(root);
                assertTrue(result, "Phải tìm thấy SocketTimeoutException qua getCause() lồng sâu");
            } catch (StackOverflowError soe) {
                fail("Duyệt đệ quy getCause() bị StackOverflowError ở độ sâu 3000! Cần chuyển sang duyệt lặp.", soe);
            }
        }
    }

    @Nested
    @DisplayName("Challenge Category 2: False Permanent Classification (False Negatives on Retryable Errors)")
    class FalsePermanentTests {

        @Test
        @DisplayName("SocketTimeoutException chứa 'Read timed out after 5500 ms': Không được bị phân loại nhầm là lỗi vĩnh viễn 550")
        void testSocketTimeoutWith5500ms() {
            SocketTimeoutException ex = new SocketTimeoutException("Read timed out after 5500 ms");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "SocketTimeoutException với '5500 ms' không được bị coi là lỗi vĩnh viễn do chuỗi con '550'");
        }

        @Test
        @DisplayName("SocketTimeoutException kết nối tới port '5010': Không được bị phân loại nhầm là lỗi vĩnh viễn 501")
        void testSocketTimeoutWithPortContaining501() {
            SocketTimeoutException ex = new SocketTimeoutException("connect timed out to mail.example.com:5010");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "SocketTimeoutException tới port '5010' không được bị coi là lỗi vĩnh viễn do chuỗi con '501'");
        }

        @Test
        @DisplayName("ConnectException kết nối tới port '5500': Không được bị phân loại nhầm là lỗi vĩnh viễn 550")
        void testConnectExceptionWithPortContaining550() {
            ConnectException ex = new ConnectException("Connection refused to mail.example.com:5500");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "ConnectException tới port '5500' không được bị coi là lỗi vĩnh viễn do chuỗi con '550'");
        }

        @Test
        @DisplayName("Lỗi 450 tạm thời có gợi ý 'try again in 500 seconds': Phải retryable (Không bị coi là lỗi 500)")
        void test450With500SecondsMessage() {
            MessagingException ex = new MessagingException("450 4.2.1 Mailbox busy, try again in 500 seconds");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Mã 450 với gợi ý '500 seconds' phải được retry, không bị nhầm với mã lỗi vĩnh viễn 500");
        }

        @Test
        @DisplayName("SMTPAddressFailedException có getReturnCode() = 450 và message chứa '500 seconds': Phải retryable")
        void testAddressFailed450With500Seconds() throws Exception {
            InternetAddress recipient = new InternetAddress("user@example.com");
            SMTPAddressFailedException ex = new SMTPAddressFailedException(recipient, "RCPT TO", 450,
                    "450 4.2.1 Mailbox busy, try again in 500 seconds");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "SMTPAddressFailedException mã 450 có chứa '500 seconds' phải tuyệt đối retryable");
        }

        @Test
        @DisplayName("Lỗi 451 với thông điệp 'Server load 5.01 exceeds threshold': Phải retryable (Không bị coi là lỗi 501)")
        void test451WithLoadAverage501() {
            MessagingException ex = new MessagingException("451 4.3.0 Server load 5.01 exceeds threshold");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Mã 451 với load 5.01 phải được retry, không bị nhầm với lỗi 501");
        }

        @Test
        @DisplayName("Lỗi 452 với dung lượng '550MB exceeded': Phải retryable (Không bị coi là lỗi 550)")
        void test452With550MB() {
            MessagingException ex = new MessagingException("452 4.2.2 Mailbox quota 550MB exceeded, clean up and retry");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Mã 452 tạm thời với '550MB' phải được retry, không bị nhận diện nhầm thành 550");
        }

        @Test
        @DisplayName("Lỗi 421 với tên máy chủ 'smtp503.mail.provider.com': Phải retryable (Không bị coi là lỗi 503)")
        void test421WithHostnameContaining503() {
            MessagingException ex = new MessagingException("421 4.4.2 smtp503.mail.provider.com Service shutting down");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Mã 421 với host 'smtp503' phải được retry, không bị nhận diện nhầm thành lỗi 503");
        }
    }

    @Nested
    @DisplayName("Challenge Category 3: Unhandled RFC 5321 / RFC 3207 / RFC 4954 4xx Transient Codes")
    class Unhandled4xxTests {

        @Test
        @DisplayName("Mã 454 (RFC 3207: TLS not available due to temporary reason) -> Phải retryable")
        void testRfc3207Code454() {
            MessagingException ex = new MessagingException("454 TLS not available due to temporary reason");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Mã 454 (RFC 3207 TLS temporary issue) theo chuẩn RFC 5321 4xx phải được retry");
        }

        @Test
        @DisplayName("Mã 455 (RFC 5336: Server unable to accommodate parameters) -> Phải retryable")
        void testRfc5336Code455() {
            MessagingException ex = new MessagingException("455 Server unable to accommodate parameters");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Mã 455 theo chuẩn RFC 5321 4xx phải được retry");
        }

        @Test
        @DisplayName("Mã 432 (RFC 4954: Password transition needed) -> Phải retryable")
        void testRfc4954Code432() {
            MessagingException ex = new MessagingException("432 A password transition is needed");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Mã 432 theo chuẩn RFC 4954 4xx phải được retry");
        }

        @Test
        @DisplayName("Mã 453 (Too many recipients in transaction) -> Phải retryable")
        void testCode453() {
            MessagingException ex = new MessagingException("453 Too many recipients in transaction");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Mã 453 theo chuẩn RFC 5321 4xx phải được retry");
        }
    }

    @Nested
    @DisplayName("Challenge Category 4: Unhandled RFC 5321 / RFC 7504 5xx Permanent Codes with Misleading Strings")
    class MisleadingPermanentTests {

        @Test
        @DisplayName("Mã 556 (RFC 7504: Domain does not accept mail) kèm 'connection timed out': Phải không retry (Lỗi vĩnh viễn chiếm ưu tiên)")
        void testRfc7504Code556WithTimeoutString() {
            MessagingException ex = new MessagingException("556 Domain does not accept mail - connection timed out");
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "Mã 556 (RFC 7504) là lỗi vĩnh viễn, không được phép retry dù có chuỗi 'timed out'");
        }

        @Test
        @DisplayName("Mã 541 (Recipient Address Rejected - Blacklist) kèm 'timed out': Phải không retry")
        void testCode541WithTimeoutString() {
            MessagingException ex = new MessagingException("541 Recipient rejected - connection timed out");
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "Mã 541 là lỗi từ chối vĩnh viễn, không được phép retry dù có chuỗi 'timed out'");
        }

        @Test
        @DisplayName("Mã 520 kèm 'timed out': Phải không retry")
        void testCode520WithTimeoutString() {
            MessagingException ex = new MessagingException("520 URL not allowed - connection timed out");
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "Mã 520 là lỗi 5xx vĩnh viễn, không được phép retry dù có chuỗi 'timed out'");
        }
    }

    @Nested
    @DisplayName("Challenge Category 5: Complex Cyclic and Intertwined Exception Graphs")
    class ComplexCyclicTests {

        @Test
        @DisplayName("Đồ thị chu trình getCause() giữa 2 Exception thường kèm SocketTimeoutException")
        void testCauseCycleSafety() {
            Exception w1 = new Exception("Wrapper 1");
            Exception w2 = new Exception("Wrapper 2", w1);
            w1.initCause(w2); // Chu trình vòng lặp getCause()

            MessagingException root = new MessagingException("Root error", w1);
            // Không được treo vòng lặp vô hạn và trả về false do không có transient
            assertFalse(ExceptionClassifier.isRetryable(root));
        }

        @Test
        @DisplayName("Chu trình getNextException() lồng MessagingException")
        void testNextExceptionCycleSafety() {
            MessagingException e1 = new MessagingException("Cycle 1");
            MessagingException e2 = new MessagingException("Cycle 2");
            e1.setNextException(e2);
            e2.setNextException(e1);

            assertFalse(ExceptionClassifier.isRetryable(e1));
        }
    }
}
