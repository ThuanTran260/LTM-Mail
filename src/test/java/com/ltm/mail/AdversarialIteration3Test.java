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

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial Stress Test Suite for Milestone 2 Iteration 3
 * Evaluates port collisions (465, 25, 2525, 587), extensive advisory metric collision vectors,
 * genuine RFC 5321 / RFC 3463 status codes preservation, and typed JavaMail priority.
 */
public class AdversarialIteration3Test {

    @Nested
    @DisplayName("Adversarial Attack 1: Non-587 Port Collisions (Port 465, Port 25, Port 2525)")
    class Non587PortCollisionTests {

        @Test
        @DisplayName("Port 465 (SMTPS) in MessagingException wrapping ConnectException -> retryable")
        void testPort465WithConnectException() {
            MessagingException ex = new MessagingException(
                    "Could not connect to SMTP host: smtp.gmail.com, port: 465",
                    new ConnectException("Connection refused: connect")
            );
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Port 465 SMTPS connection refusal must be retryable");
        }

        @Test
        @DisplayName("Port 465 in MessagingException wrapping SocketTimeoutException -> retryable")
        void testPort465WithSocketTimeoutException() {
            MessagingException ex = new MessagingException(
                    "Could not connect to SMTP host: smtp.gmail.com, port: 465",
                    new SocketTimeoutException("connect timed out")
            );
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Port 465 SMTPS timeout must be retryable");
        }

        @Test
        @DisplayName("Port 465 bare MessagingException with connection timed out -> retryable")
        void testPort465BareWithTimeout() {
            MessagingException ex = new MessagingException(
                    "Could not connect to SMTP host: smtp.gmail.com, port: 465 - connection timed out"
            );
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Port 465 bare timeout message must be retryable");
        }

        @Test
        @DisplayName("E2E SmtpClient.sendWithRetry on Port 465 connection drop -> must retry 3 times (4 attempts)")
        void testE2EPort465Retry() throws Exception {
            SmtpConfig config = new SmtpConfig();
            config.setHost("smtp.gmail.com");
            config.setPort(465);
            config.setEnableSsl(true);
            config.setUsername("user@gmail.com");
            config.setPassword("secret");
            config.setFrom("user@gmail.com");
            config.setTo("dest@example.com");
            config.setSubject("Test Port 465");
            config.setRetry(new RetryConfig(3, 1, 10));

            AtomicInteger attempts = new AtomicInteger(0);
            SmtpClient client = new SmtpClient(config) {
                @Override
                public void sendEmail(Session session, MimeMessage message) throws MessagingException {
                    attempts.incrementAndGet();
                    throw new MessagingException(
                            "Could not connect to SMTP host: smtp.gmail.com, port: 465",
                            new ConnectException("Connection refused")
                    );
                }
            };
            client.setSleeper(millis -> {});

            Session session = Session.getInstance(new Properties());
            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress("user@gmail.com"));
            msg.setRecipients(javax.mail.Message.RecipientType.TO, InternetAddress.parse("dest@example.com"));
            msg.setText("Hello 465");

            assertThrows(MessagingException.class, () -> client.sendWithRetry(session, msg));
            assertEquals(4, attempts.get(), "Must retry exactly 3 times (4 attempts total) for port 465 connection refusal");
        }

        @Test
        @DisplayName("Port 25 (Standard SMTP) in MessagingException wrapping ConnectException -> retryable")
        void testPort25WithConnectException() {
            MessagingException ex = new MessagingException(
                    "Could not connect to SMTP host: mail.internal.corp, port: 25",
                    new ConnectException("Connection refused")
            );
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Port 25 connection refusal must be retryable");
        }

        @Test
        @DisplayName("Port 25 bare MessagingException with connection timed out -> retryable")
        void testPort25BareTimeout() {
            MessagingException ex = new MessagingException(
                    "Could not connect to SMTP host: mail.internal.corp, port: 25 - connection timed out"
            );
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Port 25 bare timeout message must be retryable");
        }

        @Test
        @DisplayName("Port 2525 (Alternative Submission) in MessagingException wrapping ConnectException -> retryable")
        void testPort2525WithConnectException() {
            MessagingException ex = new MessagingException(
                    "Could not connect to SMTP host: smtp.sendgrid.net, port: 2525",
                    new ConnectException("Connection refused")
            );
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Port 2525 connection refusal must be retryable");
        }
    }

    @Nested
    @DisplayName("Adversarial Attack 2: Exhaustive Advisory Units and Quantities")
    class AdvisoryUnitsExhaustiveTests {

        @Test
        @DisplayName("Connection timed out after 500 milliseconds -> retryable")
        void test500Milliseconds() {
            MessagingException ex = new MessagingException("Connection timed out after 500 milliseconds");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Connection timed out after 500millis -> retryable")
        void test500MillisWithoutSpace() {
            MessagingException ex = new MessagingException("Connection timed out after 500millis");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Connection timed out after 550 millis -> retryable")
        void test550Millis() {
            MessagingException ex = new MessagingException("Connection timed out after 550 millis");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Connection timed out after 550ms -> retryable")
        void test550MsWithoutSpace() {
            MessagingException ex = new MessagingException("Connection timed out after 550ms");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Server temporarily busy, retry in 500 sec -> retryable")
        void test500Sec() {
            MessagingException ex = new MessagingException("Server temporarily busy, retry in 500 sec");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Server temporarily busy, retry in 500sec -> retryable")
        void test500SecWithoutSpace() {
            MessagingException ex = new MessagingException("Server temporarily busy, retry in 500sec");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Server temporarily busy, retry in 500 s -> retryable")
        void test500SWithSpace() {
            MessagingException ex = new MessagingException("Server temporarily busy, retry in 500 s");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Temporary error: 500 entries in queue, try again later -> retryable")
        void test500Entries() {
            MessagingException ex = new MessagingException("Temporary error: 500 entries in queue, try again later");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Temporary error: queue buffer 500 MB exceeded, try again later -> retryable")
        void test500Mb() {
            MessagingException ex = new MessagingException("Temporary error: queue buffer 500 MB exceeded, try again later");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Temporary error: buffer 500MB exceeded, try again later -> retryable")
        void test500MbWithoutSpace() {
            MessagingException ex = new MessagingException("Temporary error: buffer 500MB exceeded, try again later");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Temporary error: queue buffer 500 KB exceeded, try again later -> retryable")
        void test500Kb() {
            MessagingException ex = new MessagingException("Temporary error: queue buffer 500 KB exceeded, try again later");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Temporary error: payload 500 bytes dropped, try again later -> retryable")
        void test500Bytes() {
            MessagingException ex = new MessagingException("Temporary error: payload 500 bytes dropped, try again later");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Temporary error: 500 messages pending, try again later -> retryable")
        void test500Messages() {
            MessagingException ex = new MessagingException("Temporary error: 500 messages pending, try again later");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Server temporarily busy, retry in 500 min -> retryable")
        void test500Min() {
            MessagingException ex = new MessagingException("Server temporarily busy, retry in 500 min");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Server temporarily busy, retry in 500 minutes -> retryable")
        void test500Minutes() {
            MessagingException ex = new MessagingException("Server temporarily busy, retry in 500 minutes");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Server temporarily busy, retry in 500 hr -> retryable")
        void test500Hr() {
            MessagingException ex = new MessagingException("Server temporarily busy, retry in 500 hr");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }

        @Test
        @DisplayName("Server temporarily busy, retry in 500 hours -> retryable")
        void test500Hours() {
            MessagingException ex = new MessagingException("Server temporarily busy, retry in 500 hours");
            assertTrue(ExceptionClassifier.isRetryable(ex));
        }
    }

    @Nested
    @DisplayName("Adversarial Attack 3: Genuine RFC 5321 / RFC 3463 Status Codes Preservation")
    class GenuineCodesPreservationTests {

        @Test
        @DisplayName("550 5.1.1 User unknown -> permanent (false)")
        void test550WithEnhancedCode() {
            MessagingException ex = new MessagingException("550 5.1.1 User unknown");
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "550 5.1.1 is permanent RFC 5321 / RFC 3463 error");
        }

        @Test
        @DisplayName("554 5.7.1 Relay access denied -> permanent (false)")
        void test554WithEnhancedCode() {
            MessagingException ex = new MessagingException("554 5.7.1 Relay access denied");
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "554 5.7.1 is permanent RFC 5321 / RFC 3463 error");
        }

        @Test
        @DisplayName("550 Mailbox quota exceeded: 500 MB limit reached -> permanent (false)")
        void test550WithAdvisoryUnitLaterInMessage() {
            MessagingException ex = new MessagingException("550 Mailbox quota exceeded: 500 MB limit reached");
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "Primary code 550 must mark permanent even when advisory units appear later");
        }

        @Test
        @DisplayName("552 5.2.2 Mailbox full, exceeded 500 messages -> permanent (false)")
        void test552WithAdvisoryUnitLaterInMessage() {
            MessagingException ex = new MessagingException("552 5.2.2 Mailbox full, exceeded 500 messages");
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "552 5.2.2 must be permanent even if 500 messages is present");
        }

        @Test
        @DisplayName("550 Service unavailable -> permanent (verifying s\\b lookahead does not match Service)")
        void test550ServiceUnavailable() {
            MessagingException ex = new MessagingException("550 Service unavailable");
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "550 Service unavailable must remain permanent (s\\b boundary must not cancel 550)");
        }

        @Test
        @DisplayName("550 Mailbox unavailable -> permanent (verifying m\\b lookahead does not match Mailbox)")
        void test550MailboxUnavailable() {
            MessagingException ex = new MessagingException("550 Mailbox unavailable");
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "550 Mailbox unavailable must remain permanent (m\\b boundary must not cancel 550)");
        }

        @Test
        @DisplayName("550 Host unknown -> permanent (verifying h\\b lookahead does not match Host)")
        void test550HostUnknown() {
            MessagingException ex = new MessagingException("550 Host unknown");
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "550 Host unknown must remain permanent (h\\b boundary must not cancel 550)");
        }

        @Test
        @DisplayName("550 5.7.1 spam detected -> permanent (verifying s\\b lookahead does not match spam)")
        void test550SpamDetected() {
            MessagingException ex = new MessagingException("550 5.7.1 spam detected");
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "550 5.7.1 spam detected must remain permanent");
        }

        @Test
        @DisplayName("451 4.7.1 Mailbox temporarily busy, limit is 500 messages -> retryable (true)")
        void test451WithAdvisoryNumber() {
            MessagingException ex = new MessagingException("451 4.7.1 Mailbox temporarily busy, limit is 500 messages");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "451 4.7.1 is transient deferral even if 500 messages is mentioned");
        }

        @Test
        @DisplayName("452 4.2.2 Mailbox full (500 items) -> retryable (true)")
        void test452WithAdvisoryNumber() {
            MessagingException ex = new MessagingException("452 4.2.2 Mailbox full (500 items)");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "452 4.2.2 transient deferral must be retryable");
        }

        @Test
        @DisplayName("421 4.4.2 Server connection dropped after 500 ms -> retryable (true)")
        void test421WithAdvisoryNumber() {
            MessagingException ex = new MessagingException("421 4.4.2 Server connection dropped after 500 ms");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "421 4.4.2 service closing must be retryable");
        }

        @Test
        @DisplayName("SMTP: 450 Mailbox busy, wait 500 seconds -> retryable (true)")
        void testSmtpPrefixed450With500Seconds() {
            MessagingException ex = new MessagingException("SMTP: 450 Mailbox busy, wait 500 seconds");
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Prefixed 450 code must be recognized as transient");
        }
    }

    @Nested
    @DisplayName("Adversarial Attack 4: Typed JavaMail Exceptions Precedence")
    class TypedExceptionsPrecedenceTests {

        @Test
        @DisplayName("SMTPAddressFailedException with return code 550 and misleading timeout message -> permanent")
        void testSmtpAddressFailed550WithMisleadingTimeout() throws Exception {
            SMTPAddressFailedException ex = new SMTPAddressFailedException(
                    new InternetAddress("baduser@example.com"),
                    "RCPT TO",
                    550,
                    "timed out after 500 ms"
            );
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "Typed return code 550 strictly overrides any timeout text");
        }

        @Test
        @DisplayName("SMTPSenderFailedException with return code 554 and connection refused message -> permanent")
        void testSmtpSenderFailed554WithRefusedMessage() throws Exception {
            SMTPSenderFailedException ex = new SMTPSenderFailedException(
                    new InternetAddress("sender@example.com"),
                    "MAIL FROM",
                    554,
                    "Connection refused by policy"
            );
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "Typed return code 554 strictly overrides transient phrases");
        }

        @Test
        @DisplayName("SMTPSendFailedException with return code 501 and temporarily busy message -> permanent")
        void testSmtpSendFailed501WithTemporarilyBusy() {
            SMTPSendFailedException ex = new SMTPSendFailedException(
                    "DATA",
                    501,
                    "temporarily busy syntax error",
                    null,
                    new javax.mail.Address[0],
                    new javax.mail.Address[0],
                    new javax.mail.Address[0]
            );
            assertFalse(ExceptionClassifier.isRetryable(ex),
                    "Typed return code 501 strictly overrides transient phrase");
        }

        @Test
        @DisplayName("SMTPAddressFailedException with return code 450 and permanent-like string -> retryable")
        void testSmtpAddressFailed450WithPermanentString() throws Exception {
            SMTPAddressFailedException ex = new SMTPAddressFailedException(
                    new InternetAddress("busy@example.com"),
                    "RCPT TO",
                    450,
                    "550 simulated quote in text"
            );
            assertTrue(ExceptionClassifier.isRetryable(ex),
                    "Typed return code 450 grants permanent text exemption");
        }
    }
}
