package com.ltm.mail;

import com.ltm.mail.client.SmtpClient;
import com.ltm.mail.config.RetryConfig;
import com.ltm.mail.config.SmtpConfig;
import com.ltm.mail.util.ExceptionClassifier;
import com.sun.mail.smtp.SMTPAddressFailedException;
import com.sun.mail.smtp.SMTPSendFailedException;
import com.sun.mail.smtp.SMTPSenderFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.mail.AuthenticationFailedException;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Empirical Challenger Verification Suite for Milestone 2 Iteration 4.
 * Tests adversarial edge cases across:
 * 1. Socket resets combined with 5xx permanent codes.
 * 2. Uppercase and mixed-case duration and advisory units.
 * 3. STARTTLS port 587 and other SMTP ports.
 * 4. Standard SMTP vocabulary word boundaries following 5xx.
 * 5. Deep chain and mixed chain priority.
 */
public class EmpiricalChallengerIteration4Test {

    @Nested
    @DisplayName("Category 1: Socket Resets Combined with 5xx Errors (RFC 5321 vs Socket Reset)")
    class SocketReset5xxTests {

        @Test
        @DisplayName("550 User unknown with SocketException('Connection reset') -> false")
        void test550WithConnectionReset() {
            MessagingException ex = new MessagingException("550 5.1.1 User unknown", new SocketException("Connection reset"));
            assertFalse(ExceptionClassifier.isRetryable(ex), "550 with Connection reset must be permanent");
        }

        @Test
        @DisplayName("550 User unknown with SocketException('Connection reset by peer') -> false")
        void test550WithConnectionResetByPeer() {
            MessagingException ex = new MessagingException("550 5.1.1 User unknown", new SocketException("Connection reset by peer"));
            assertFalse(ExceptionClassifier.isRetryable(ex), "550 with Connection reset by peer must be permanent");
        }

        @Test
        @DisplayName("535 Bad credentials with SocketException('Connection reset') -> false")
        void test535WithConnectionReset() {
            MessagingException ex = new MessagingException("535 5.7.8 Authentication failed", new SocketException("Connection reset"));
            assertFalse(ExceptionClassifier.isRetryable(ex), "535 with Connection reset must be permanent");
        }

        @Test
        @DisplayName("501 Syntax error with SocketException('Broken pipe') -> false")
        void test501WithBrokenPipe() {
            MessagingException ex = new MessagingException("501 Syntax error", new SocketException("Broken pipe"));
            assertFalse(ExceptionClassifier.isRetryable(ex), "501 with Broken pipe must be permanent");
        }

        @Test
        @DisplayName("503 Bad sequence with SocketException('Software caused connection abort') -> false")
        void test503WithConnectionAbort() {
            MessagingException ex = new MessagingException("503 Bad sequence of commands", new SocketException("Software caused connection abort"));
            assertFalse(ExceptionClassifier.isRetryable(ex), "503 with abort must be permanent");
        }

        @Test
        @DisplayName("Reverse wrapping: SocketException wrapping MessagingException('550 User unknown') -> false")
        void testReverseWrappingSocketExceptionAround550() {
            SocketException se = new SocketException("Connection reset");
            se.initCause(new MessagingException("550 User unknown"));
            assertFalse(ExceptionClassifier.isRetryable(se), "Reverse wrapping with 550 cause must be permanent");
        }

        @Test
        @DisplayName("Multi-hop chain: Wrapper -> 550 User unknown -> SocketException -> false")
        void testMultiHopChainWith550AndSocketReset() {
            SocketException leaf = new SocketException("Connection reset by peer");
            MessagingException mid = new MessagingException("550 Requested action not taken: mailbox unavailable", leaf);
            MessagingException wrapper = new MessagingException("SMTP transport failure");
            wrapper.setNextException(mid);

            assertFalse(ExceptionClassifier.isRetryable(wrapper), "Multi-hop chain containing 550 must be permanent");
        }

        @Test
        @DisplayName("Pure SocketException without 5xx -> true (retryable)")
        void testPureSocketExceptionRetryable() {
            SocketException se = new SocketException("Connection reset by peer");
            assertTrue(ExceptionClassifier.isRetryable(se), "Pure SocketException without 5xx must be retryable");
        }

        @Test
        @DisplayName("Pure MessagingException with 'Connection reset' text -> true (retryable)")
        void testPureMessagingExceptionConnectionReset() {
            MessagingException me = new MessagingException("Network error: connection reset by peer");
            assertTrue(ExceptionClassifier.isRetryable(me), "MessagingException with connection reset must be retryable");
        }
    }

    @Nested
    @DisplayName("Category 2: Uppercase & Mixed-case Duration Units")
    class UppercaseDurationUnitsTests {

        @Test
        @DisplayName("500 MS uppercase -> true")
        void test500MsUpper() {
            assertTrue(ExceptionClassifier.isRetryable(new MessagingException("Connection timed out after 500 MS")));
        }

        @Test
        @DisplayName("500 MILLIS uppercase -> true")
        void test500MillisUpper() {
            assertTrue(ExceptionClassifier.isRetryable(new MessagingException("Connection timed out after 500 MILLIS")));
        }

        @Test
        @DisplayName("500 MILLISECONDS uppercase -> true")
        void test500MillisecondsUpper() {
            assertTrue(ExceptionClassifier.isRetryable(new MessagingException("Connection timed out after 500 MILLISECONDS")));
        }

        @Test
        @DisplayName("500 SEC uppercase -> true")
        void test500SecUpper() {
            assertTrue(ExceptionClassifier.isRetryable(new MessagingException("Server temporarily busy, retry in 500 SEC")));
        }

        @Test
        @DisplayName("500 SECONDS uppercase -> true")
        void test500SecondsUpper() {
            assertTrue(ExceptionClassifier.isRetryable(new MessagingException("Server temporarily busy, retry in 500 SECONDS")));
        }

        @Test
        @DisplayName("500 S uppercase -> true")
        void test500SUpper() {
            assertTrue(ExceptionClassifier.isRetryable(new MessagingException("Server temporarily busy, retry in 500 S")));
        }

        @Test
        @DisplayName("500 MIN uppercase -> true")
        void test500MinUpper() {
            assertTrue(ExceptionClassifier.isRetryable(new MessagingException("Server temporarily busy, retry in 500 MIN")));
        }

        @Test
        @DisplayName("500 MINUTES uppercase -> true")
        void test500MinutesUpper() {
            assertTrue(ExceptionClassifier.isRetryable(new MessagingException("Server temporarily busy, retry in 500 MINUTES")));
        }

        @Test
        @DisplayName("500 M uppercase -> true")
        void test500MUpper() {
            assertTrue(ExceptionClassifier.isRetryable(new MessagingException("Server temporarily busy, retry in 500 M")));
        }

        @Test
        @DisplayName("500 HR uppercase -> true")
        void test500HrUpper() {
            assertTrue(ExceptionClassifier.isRetryable(new MessagingException("Server temporarily busy, retry in 500 HR")));
        }

        @Test
        @DisplayName("500 HOURS uppercase -> true")
        void test500HoursUpper() {
            assertTrue(ExceptionClassifier.isRetryable(new MessagingException("Server temporarily busy, retry in 500 HOURS")));
        }

        @Test
        @DisplayName("500 H uppercase -> true")
        void test500HUpper() {
            assertTrue(ExceptionClassifier.isRetryable(new MessagingException("Server temporarily busy, retry in 500 H")));
        }

        @Test
        @DisplayName("500 KB uppercase -> true")
        void test500KbUpper() {
            assertTrue(ExceptionClassifier.isRetryable(new MessagingException("Temporary error: queue buffer 500 KB exceeded, try again later")));
        }

        @Test
        @DisplayName("500 MB uppercase -> true")
        void test500MbUpper() {
            assertTrue(ExceptionClassifier.isRetryable(new MessagingException("Temporary error: queue buffer 500 MB exceeded, try again later")));
        }

        @Test
        @DisplayName("500 GB uppercase -> true")
        void test500GbUpper() {
            assertTrue(ExceptionClassifier.isRetryable(new MessagingException("Temporary error: queue buffer 500 GB exceeded, try again later")));
        }

        @Test
        @DisplayName("500 BYTES uppercase -> true")
        void test500BytesUpper() {
            assertTrue(ExceptionClassifier.isRetryable(new MessagingException("Temporary error: payload 500 BYTES dropped, try again later")));
        }

        @Test
        @DisplayName("500 ITEMS uppercase -> true")
        void test500ItemsUpper() {
            assertTrue(ExceptionClassifier.isRetryable(new MessagingException("Temporary error: 500 ITEMS in queue, try again later")));
        }

        @Test
        @DisplayName("500 ENTRIES uppercase -> true")
        void test500EntriesUpper() {
            assertTrue(ExceptionClassifier.isRetryable(new MessagingException("Temporary error: 500 ENTRIES in queue, try again later")));
        }
    }

    @Nested
    @DisplayName("Category 3: STARTTLS Port 587 & Other Port Edge Cases")
    class Port587EdgeCasesTests {

        @Test
        @DisplayName("Port 587 with ConnectException -> true")
        void testPort587ConnectException() {
            MessagingException ex = new MessagingException("Could not connect to SMTP host: smtp.gmail.com, port: 587", new ConnectException("Connection refused"));
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Port 587 with SocketTimeoutException -> true")
        void testPort587SocketTimeoutException() {
            MessagingException ex = new MessagingException("Could not connect to SMTP host: smtp.gmail.com, port: 587", new SocketTimeoutException("connect timed out"));
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Port 587 bare message with 'connection reset by peer' -> true")
        void testPort587BareConnectionReset() {
            MessagingException ex = new MessagingException("Failed to connect to SMTP host: mail.internal.corp, port: 587: connection reset by peer");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Port 587 bare message with 'connection refused' -> true")
        void testPort587BareConnectionRefused() {
            MessagingException ex = new MessagingException("Failed to connect to SMTP host: mail.internal.corp, port: 587: connection refused");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Port 587 with genuine 550 error response -> false")
        void testPort587WithGenuine550() {
            MessagingException ex = new MessagingException("550 5.7.1 Service unavailable on port: 587");
            assertFalse(ExceptionClassifier.isRetryable(ex), "If server returned 550 on port 587, it must be permanent");
        }
    }

    @Nested
    @DisplayName("Category 4: English Word Boundary Collisions Following 550")
    class WordBoundaryCollisionsTests {

        @Test
        @DisplayName("550 message rejected -> false")
        void test550MessageRejected() {
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("550 message rejected")));
        }

        @Test
        @DisplayName("550 messages rejected -> false")
        void test550MessagesRejected() {
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("550 messages rejected")));
        }

        @Test
        @DisplayName("550 security check failed -> false")
        void test550SecurityCheckFailed() {
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("550 security check failed")));
        }

        @Test
        @DisplayName("550 secondary mx unavailable -> false")
        void test550SecondaryMxUnavailable() {
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("550 secondary mx unavailable")));
        }

        @Test
        @DisplayName("550 msg: delivery timed out -> false")
        void test550MsgDelivery() {
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("550 msg: delivery timed out")));
        }

        @Test
        @DisplayName("550 mbox full: connection timed out -> false")
        void test550MboxFull() {
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("550 mbox full: connection timed out")));
        }

        @Test
        @DisplayName("550 minor error: connection timed out -> false")
        void test550MinorError() {
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("550 minor error: connection timed out")));
        }

        @Test
        @DisplayName("550 Service unavailable -> false")
        void test550ServiceUnavailable() {
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("550 Service unavailable")));
        }

        @Test
        @DisplayName("550 Mailbox unavailable -> false")
        void test550MailboxUnavailable() {
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("550 Mailbox unavailable")));
        }

        @Test
        @DisplayName("550 Host unknown -> false")
        void test550HostUnknown() {
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("550 Host unknown")));
        }

        @Test
        @DisplayName("550 spam detected -> false")
        void test550SpamDetected() {
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("550 spam detected")));
        }

        @Test
        @DisplayName("550 sender rejected -> false")
        void test550SenderRejected() {
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("550 sender rejected")));
        }

        @Test
        @DisplayName("550 recipient rejected -> false")
        void test550RecipientRejected() {
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("550 recipient rejected")));
        }

        @Test
        @DisplayName("550 user not found -> false")
        void test550UserNotFound() {
            assertFalse(ExceptionClassifier.isRetryable(new MessagingException("550 user not found")));
        }
    }

    @Nested
    @DisplayName("Category 5: Mixed Exception Chains & Protocol Status Code Discrimination")
    class MixedChainTests {

        @Test
        @DisplayName("Chain with both 450 and 550: 550 must take precedence -> false")
        void testChainWithBoth450And550() {
            MessagingException ex550 = new MessagingException("550 Permanent reject");
            MessagingException ex450 = new MessagingException("450 Transient busy");
            ex450.setNextException(ex550);

            assertFalse(ExceptionClassifier.isRetryable(ex450), "Pass 1 must detect 550 and reject regardless of 450 presence");
        }

        @Test
        @DisplayName("Chain with typed SMTPSendFailedException(550) wrapping SocketException -> false")
        void testTypedSendFailed550WithSocketException() {
            SMTPSendFailedException ex = new SMTPSendFailedException(
                    "DATA",
                    550,
                    "550 Mailbox unavailable",
                    new SocketException("Connection reset"),
                    new javax.mail.Address[0],
                    new javax.mail.Address[0],
                    new javax.mail.Address[0]
            );
            assertFalse(ExceptionClassifier.isRetryable(ex), "Typed 550 with SocketException must be permanent");
        }

        @Test
        @DisplayName("Chain with typed SMTPAddressFailedException(550) wrapping SocketTimeoutException -> false")
        void testTypedAddressFailed550WithSocketTimeout() throws Exception {
            SMTPAddressFailedException ex = new SMTPAddressFailedException(
                    new InternetAddress("unknown@domain.com"),
                    "RCPT TO",
                    550,
                    "550 User unknown"
            );
            ex.setNextException(new SocketTimeoutException("Read timed out"));
            assertFalse(ExceptionClassifier.isRetryable(ex), "Typed 550 with SocketTimeout must be permanent");
        }

        @Test
        @DisplayName("Chain with typed SMTPSenderFailedException(550) wrapping SocketException -> false")
        void testTypedSenderFailed550WithSocketException() throws Exception {
            SMTPSenderFailedException ex = new SMTPSenderFailedException(
                    new InternetAddress("sender@domain.com"),
                    "MAIL FROM",
                    550,
                    "550 Sender rejected"
            );
            ex.setNextException(new SocketException("Connection reset by peer"));
            assertFalse(ExceptionClassifier.isRetryable(ex), "Typed 550 with SocketException must be permanent");
        }

        @Test
        @DisplayName("AuthenticationFailedException wrapped deeply -> false")
        void testDeepAuthenticationFailedException() {
            MessagingException root = new MessagingException("Root");
            MessagingException mid = new MessagingException("Mid", new AuthenticationFailedException("535 5.7.8 Bad credentials"));
            root.setNextException(mid);

            assertFalse(ExceptionClassifier.isRetryable(root), "AuthenticationFailedException anywhere in chain must be false");
        }

        @Test
        @DisplayName("UnknownHostException wrapped deeply -> false")
        void testDeepUnknownHostException() {
            MessagingException root = new MessagingException("Root", new UnknownHostException("smtp.nonexistent.corp"));
            assertFalse(ExceptionClassifier.isRetryable(root), "UnknownHostException must be false");
        }

        @Test
        @DisplayName("SSLHandshakeException wrapped deeply -> false")
        void testDeepSslHandshakeException() {
            MessagingException root = new MessagingException("Root", new SSLHandshakeException("No appropriate protocol"));
            assertFalse(ExceptionClassifier.isRetryable(root), "SSLHandshakeException must be false");
        }
    }
}
