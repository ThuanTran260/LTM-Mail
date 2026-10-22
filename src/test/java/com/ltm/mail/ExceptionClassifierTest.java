package com.ltm.mail;

import com.ltm.mail.util.ExceptionClassifier;
import com.sun.mail.smtp.SMTPAddressFailedException;
import com.sun.mail.smtp.SMTPSendFailedException;
import com.sun.mail.smtp.SMTPSenderFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.mail.Address;
import javax.mail.AuthenticationFailedException;
import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExceptionClassifierTest {

    @Test
    @DisplayName("Lỗi xác thực 535 không được phép retry")
    void testAuthenticationFailedException() {
        AuthenticationFailedException ex = new AuthenticationFailedException("535 5.7.8 Bad credentials");
        assertFalse(ExceptionClassifier.isRetryable(ex));
    }

    @Test
    @DisplayName("Lỗi sai Host/DNS không được phép retry")
    void testUnknownHostException() {
        UnknownHostException ex = new UnknownHostException("smtp.invalid-domain-xyz.com");
        assertFalse(ExceptionClassifier.isRetryable(ex));
    }

    @Test
    @DisplayName("Lỗi SSLHandshake không được phép retry")
    void testSSLHandshakeException() {
        SSLHandshakeException ex = new SSLHandshakeException("No appropriate protocol");
        assertFalse(ExceptionClassifier.isRetryable(ex));
    }

    @Test
    @DisplayName("Lỗi SocketTimeout được phép retry")
    void testSocketTimeoutException() {
        SocketTimeoutException ex = new SocketTimeoutException("Read timed out");
        assertTrue(ExceptionClassifier.isRetryable(ex));
    }

    @Test
    @DisplayName("Lỗi ConnectException được phép retry")
    void testConnectException() {
        ConnectException ex = new ConnectException("Connection refused: connect");
        assertTrue(ExceptionClassifier.isRetryable(ex));
    }

    @Test
    @DisplayName("Lỗi 4xx (450 mailbox busy) trong thông điệp được phép retry")
    void testMessage4xxException() {
        MessagingException ex = new MessagingException("450 4.2.1 Mailbox busy, try again later");
        assertTrue(ExceptionClassifier.isRetryable(ex));
    }

    @Test
    @DisplayName("Lỗi 5xx (550 user not found) không được phép retry")
    void testMessage5xxException() {
        MessagingException ex = new MessagingException("550 5.1.1 User unknown");
        assertFalse(ExceptionClassifier.isRetryable(ex));
    }

    @Nested
    @DisplayName("REL-01: Kiểm tra SMTPAddressFailedException với mã 4xx vs 5xx")
    class SmtpAddressFailedExceptionTests {

        @Test
        @DisplayName("Mã 450 (Mailbox busy) là lỗi tạm thời -> Phải retry")
        void testAddressFailed450() throws Exception {
            InternetAddress recipient = new InternetAddress("user@example.com");
            SMTPAddressFailedException ex = new SMTPAddressFailedException(recipient, "RCPT TO", 450, "450 4.2.1 Mailbox busy");
            assertTrue(ExceptionClassifier.isRetryable(ex), "Mã 450 của SMTPAddressFailedException phải retryable");
        }

        @Test
        @DisplayName("Mã 451 (Local error) là lỗi tạm thời -> Phải retry")
        void testAddressFailed451() throws Exception {
            InternetAddress recipient = new InternetAddress("user@example.com");
            SMTPAddressFailedException ex = new SMTPAddressFailedException(recipient, "RCPT TO", 451, "451 4.3.0 Temporary local problem");
            assertTrue(ExceptionClassifier.isRetryable(ex), "Mã 451 của SMTPAddressFailedException phải retryable");
        }

        @Test
        @DisplayName("Mã 452 (Insufficient storage) là lỗi tạm thời -> Phải retry")
        void testAddressFailed452() throws Exception {
            InternetAddress recipient = new InternetAddress("user@example.com");
            SMTPAddressFailedException ex = new SMTPAddressFailedException(recipient, "RCPT TO", 452, "452 4.2.2 Mailbox full");
            assertTrue(ExceptionClassifier.isRetryable(ex), "Mã 452 của SMTPAddressFailedException phải retryable");
        }

        @Test
        @DisplayName("Mã 550 (User unknown) là lỗi vĩnh viễn -> Không retry")
        void testAddressFailed550() throws Exception {
            InternetAddress recipient = new InternetAddress("invalid@example.com");
            SMTPAddressFailedException ex = new SMTPAddressFailedException(recipient, "RCPT TO", 550, "550 5.1.1 User unknown");
            assertFalse(ExceptionClassifier.isRetryable(ex), "Mã 550 của SMTPAddressFailedException không được retry");
        }

        @Test
        @DisplayName("Mã 554 (Transaction failed) là lỗi vĩnh viễn -> Không retry")
        void testAddressFailed554() throws Exception {
            InternetAddress recipient = new InternetAddress("blocked@example.com");
            SMTPAddressFailedException ex = new SMTPAddressFailedException(recipient, "RCPT TO", 554, "554 5.7.1 Delivery not authorized");
            assertFalse(ExceptionClassifier.isRetryable(ex), "Mã 554 của SMTPAddressFailedException không được retry");
        }
    }

    @Nested
    @DisplayName("REL-02: Kiểm tra SMTPSenderFailedException với mã 4xx vs 5xx")
    class SmtpSenderFailedExceptionTests {

        @Test
        @DisplayName("Mã 451 (Sender rate limit / deferral) -> Phải retry")
        void testSenderFailed451() throws Exception {
            InternetAddress sender = new InternetAddress("sender@example.com");
            SMTPSenderFailedException ex = new SMTPSenderFailedException(sender, "MAIL FROM", 451, "451 4.7.0 Rate limit temporarily exceeded");
            assertTrue(ExceptionClassifier.isRetryable(ex), "SMTPSenderFailedException 451 phải retryable");
        }

        @Test
        @DisplayName("Mã 452 (Too many senders / deferral) -> Phải retry")
        void testSenderFailed452() throws Exception {
            InternetAddress sender = new InternetAddress("sender@example.com");
            SMTPSenderFailedException ex = new SMTPSenderFailedException(sender, "MAIL FROM", 452, "452 4.5.3 Too many recipients/senders");
            assertTrue(ExceptionClassifier.isRetryable(ex), "SMTPSenderFailedException 452 phải retryable");
        }

        @Test
        @DisplayName("Mã 550 (Sender rejected) -> Không retry")
        void testSenderFailed550() throws Exception {
            InternetAddress sender = new InternetAddress("spammer@evil.com");
            SMTPSenderFailedException ex = new SMTPSenderFailedException(sender, "MAIL FROM", 550, "550 5.1.8 Sender address rejected");
            assertFalse(ExceptionClassifier.isRetryable(ex), "SMTPSenderFailedException 550 không được retry");
        }

        @Test
        @DisplayName("Mã 501 (Syntax error in sender address) -> Không retry")
        void testSenderFailed501() throws Exception {
            InternetAddress sender = new InternetAddress("invalid-sender-format@example.com");
            SMTPSenderFailedException ex = new SMTPSenderFailedException(sender, "MAIL FROM", 501, "501 5.1.7 Bad sender address syntax");
            assertFalse(ExceptionClassifier.isRetryable(ex), "SMTPSenderFailedException 501 không được retry");
        }
    }

    @Nested
    @DisplayName("Kiểm tra SMTPSendFailedException với mã 4xx vs 5xx")
    class SmtpSendFailedExceptionTests {

        @Test
        @DisplayName("Mã 421 (Service unavailable / server shut down) -> Phải retry")
        void testSendFailed421() {
            SMTPSendFailedException ex = new SMTPSendFailedException(
                    "DATA", 421, "421 4.4.2 Service unavailable, closing transmission channel",
                    null, new Address[0], new Address[0], new Address[0]
            );
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Mã 550 (DATA rejected) -> Không retry")
        void testSendFailed550() {
            SMTPSendFailedException ex = new SMTPSendFailedException(
                    "DATA", 550, "550 5.7.1 Message rejected as spam",
                    null, new Address[0], new Address[0], new Address[0]
            );
            assertFalse(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Mã 554 (Transaction failed) -> Không retry")
        void testSendFailed554() {
            SMTPSendFailedException ex = new SMTPSendFailedException(
                    "DATA", 554, "554 5.5.4 Transaction failed",
                    null, new Address[0], new Address[0], new Address[0]
            );
            assertFalse(ExceptionClassifier.isRetryable(ex));
        }
    }

    @Nested
    @DisplayName("REL-03: Mở gói đệ quy getNextException() và getCause()")
    class ChainedExceptionTests {

        @Test
        @DisplayName("MessagingException lồng SocketTimeoutException trong getNextException() -> Phải retry")
        void testNextExceptionSocketTimeout() {
            MessagingException parent = new MessagingException("Lỗi cấp cao khi gửi email");
            parent.setNextException(new SocketTimeoutException("Read timed out"));
            assertTrue(ExceptionClassifier.isRetryable(parent), "Phải phát hiện SocketTimeoutException qua getNextException()");
        }

        @Test
        @DisplayName("MessagingException lồng ConnectException trong getNextException() -> Phải retry")
        void testNextExceptionConnect() {
            MessagingException parent = new MessagingException("Lỗi truyền tin");
            parent.setNextException(new ConnectException("Connection refused"));
            assertTrue(ExceptionClassifier.isRetryable(parent), "Phải phát hiện ConnectException qua getNextException()");
        }

        @Test
        @DisplayName("MessagingException lồng SMTPAddressFailedException mã 450 trong getNextException() -> Phải retry")
        void testNextExceptionAddress450() throws Exception {
            MessagingException parent = new MessagingException("Send failed");
            SMTPAddressFailedException child = new SMTPAddressFailedException(new InternetAddress("u@ex.com"), "RCPT", 450, "450 Busy");
            parent.setNextException(child);
            assertTrue(ExceptionClassifier.isRetryable(parent), "Phải phân loại 450 từ getNextException() là retryable");
        }

        @Test
        @DisplayName("MessagingException lồng AuthenticationFailedException trong getNextException() -> Không retry")
        void testNextExceptionAuthFail() {
            MessagingException parent = new MessagingException("Send failed");
            parent.setNextException(new AuthenticationFailedException("535 Authentication credentials invalid"));
            assertFalse(ExceptionClassifier.isRetryable(parent), "Lỗi xác thực lồng nhau trong getNextException() không được retry");
        }

        @Test
        @DisplayName("Chuỗi 3 tầng: MessagingException -> MessagingException -> SocketTimeoutException -> Phải retry")
        void testMultiLevelChain() {
            MessagingException top = new MessagingException("Top error");
            MessagingException mid = new MessagingException("Mid error");
            mid.setNextException(new SocketTimeoutException("Connection timed out"));
            top.setNextException(mid);
            assertTrue(ExceptionClassifier.isRetryable(top), "Chuỗi 3 tầng lồng getNextException() phải nhận diện được transient error");
        }

        @Test
        @DisplayName("Chuỗi hỗn hợp cả getCause() và getNextException()")
        void testMixedCauseAndNextException() {
            MessagingException mid = new MessagingException("Mid error");
            mid.setNextException(new SocketException("Connection reset by peer"));
            Exception standardEx = new Exception("Standard wrapper", mid);
            MessagingException top = new MessagingException("Top error", standardEx);
            assertTrue(ExceptionClassifier.isRetryable(top));
        }

        @Test
        @DisplayName("Bảo vệ chống vòng lặp tham chiếu vô hạn (Cyclic exception reference)")
        void testCyclicExceptionReferenceSafety() {
            MessagingException e1 = new MessagingException("Error 1");
            MessagingException e2 = new MessagingException("Error 2");
            e1.setNextException(e2);
            e2.setNextException(e1); // Chu trình vòng lặp

            // Không được ném StackOverflowError và trả về false (do không có mã 4xx)
            assertFalse(ExceptionClassifier.isRetryable(e1), "Chu trình lặp ngoại lệ phải dừng an toàn");
        }
    }

    @Nested
    @DisplayName("REL-04: Thứ tự ưu tiên nghiêm ngặt (Lỗi vĩnh viễn 5xx đi trước chuỗi 'timeout')")
    class PrecedenceTests {

        @Test
        @DisplayName("Thông điệp chứa cả mã 554 và từ khóa 'timed out' -> Lỗi vĩnh viễn 554 chiếm ưu tiên tuyệt đối (Không retry)")
        void test554WithTimedOutString() {
            MessagingException ex = new MessagingException("554 5.7.1 Connection timed out during transaction");
            assertFalse(ExceptionClassifier.isRetryable(ex), "554 phải chiếm ưu tiên trước chuỗi 'timed out'");
        }

        @Test
        @DisplayName("Thông điệp chứa cả mã 550 và từ khóa 'timeout' -> Không retry")
        void test550WithTimeoutString() {
            MessagingException ex = new MessagingException("550 5.1.1 User unknown (socket timeout during verification)");
            assertFalse(ExceptionClassifier.isRetryable(ex), "550 phải chiếm ưu tiên trước 'timeout'");
        }

        @Test
        @DisplayName("Thông điệp chứa mã 535 và 'timed out' -> Không retry")
        void test535WithTimeoutString() {
            MessagingException ex = new MessagingException("535 5.7.8 Bad credentials: auth timed out");
            assertFalse(ExceptionClassifier.isRetryable(ex), "535 phải chiếm ưu tiên trước 'timed out'");
        }

        @Test
        @DisplayName("AuthenticationFailedException chứa thông điệp 'Connection timed out' -> Vẫn là lỗi vĩnh viễn (Không retry)")
        void testAuthExceptionWithTimeoutMessage() {
            AuthenticationFailedException ex = new AuthenticationFailedException("Connection timed out waiting for credentials");
            assertFalse(ExceptionClassifier.isRetryable(ex), "AuthenticationFailedException phải luôn là vĩnh viễn");
        }

        @Test
        @DisplayName("Lỗi cấp cao có thông điệp 'timeout' nhưng ngoại lệ lồng nhau là AuthenticationFailedException -> Không retry")
        void testWrapperTimeoutWithInnerAuthFailure() {
            MessagingException wrapper = new MessagingException("Operation timed out");
            wrapper.setNextException(new AuthenticationFailedException("535 Bad credentials"));
            assertFalse(ExceptionClassifier.isRetryable(wrapper), "Authentication failure trong chuỗi phải phủ quyết thông điệp timeout ở wrapper");
        }

        @Test
        @DisplayName("Thông điệp chứa 501, 502, 503, 504 -> Không retry")
        void testOther5xxCodes() {
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("501 Syntax error in parameters")));
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("502 Command not implemented")));
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("503 Bad sequence of commands")));
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("504 Command parameter not implemented")));
        }

        @Test
        @DisplayName("Thông điệp chứa mã Enhanced Status Code 5.X.X -> Không retry")
        void testEnhancedPermanentCodes() {
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("Permanent failure 5.1.1 Bad destination mailbox")));
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("Delivery failed: 5.7.1 Access denied")));
        }
    }

    @Nested
    @DisplayName("Edge Cases & Null Safety")
    class EdgeCaseTests {

        @Test
        @DisplayName("Ngoại lệ null -> Trả về false an toàn")
        void testNullSafety() {
            assertFalse(ExceptionClassifier.isRetryable(null));
        }

        @Test
        @DisplayName("Ngoại lệ không xác định (RuntimeException) -> Mặc định false để tránh lặp vô hạn")
        void testUnrecognizedException() {
            assertFalse(ExceptionClassifier.isRetryable(new RuntimeException("Lỗi logic không mong muốn")));
        }
    }
}
