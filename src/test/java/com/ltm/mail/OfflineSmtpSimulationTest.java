package com.ltm.mail;

import com.ltm.mail.client.EmailMessageBuilder;
import com.ltm.mail.client.SmtpClient;
import com.ltm.mail.config.RetryConfig;
import com.ltm.mail.config.SmtpConfig;
import com.sun.mail.smtp.SMTPAddressFailedException;
import com.sun.mail.smtp.SMTPSendFailedException;
import com.sun.mail.smtp.SMTPSenderFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.File;
import java.net.SocketException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Offline SMTP Simulation & Test Double Suite (Milestone 3 / R3).
 * Implements in-memory ProgrammableSmtpTransport via JavaMail SPI and
 * Mockito test doubles (TEST-01) to verify protocol dialogs, simulated network drops,
 * transient 4xx retries, permanent 5xx fail-fast, and guaranteed resource cleanup (REL-06).
 */
public class OfflineSmtpSimulationTest {

    // =========================================================================
    // In-Memory Test Double: ProgrammableSmtpTransport
    // =========================================================================

    public static class ProgrammableSmtpTransport extends Transport {

        public enum Phase { CONNECT, SEND_MESSAGE }

        @FunctionalInterface
        public interface TransportAction {
            void execute(Phase phase, Message msg, Address[] addresses) throws MessagingException;
        }

        private static final Queue<TransportAction> CONNECT_ACTIONS = new ConcurrentLinkedQueue<>();
        private static final Queue<TransportAction> SEND_ACTIONS = new ConcurrentLinkedQueue<>();

        public static final AtomicInteger CONNECT_CALLS = new AtomicInteger(0);
        public static final AtomicInteger SEND_MESSAGE_CALLS = new AtomicInteger(0);
        public static final AtomicInteger CLOSE_CALLS = new AtomicInteger(0);

        public static void reset() {
            CONNECT_ACTIONS.clear();
            SEND_ACTIONS.clear();
            CONNECT_CALLS.set(0);
            SEND_MESSAGE_CALLS.set(0);
            CLOSE_CALLS.set(0);
        }

        public static void enqueueConnectAction(TransportAction action) {
            CONNECT_ACTIONS.add(action);
        }

        public static void enqueueConnectFailure(MessagingException ex) {
            CONNECT_ACTIONS.add((phase, msg, addrs) -> {
                throw ex;
            });
        }

        public static void enqueueSendAction(TransportAction action) {
            SEND_ACTIONS.add(action);
        }

        public static void enqueueSendFailure(MessagingException ex) {
            SEND_ACTIONS.add((phase, msg, addrs) -> {
                throw ex;
            });
        }

        public ProgrammableSmtpTransport(Session session, URLName urlname) {
            super(session, urlname);
        }

        @Override
        public synchronized void connect() throws MessagingException {
            CONNECT_CALLS.incrementAndGet();
            TransportAction action = CONNECT_ACTIONS.poll();
            if (action != null) {
                action.execute(Phase.CONNECT, null, null);
            }
            setConnected(true);
        }

        @Override
        public synchronized void sendMessage(Message msg, Address[] addresses) throws MessagingException {
            SEND_MESSAGE_CALLS.incrementAndGet();
            TransportAction action = SEND_ACTIONS.poll();
            if (action != null) {
                action.execute(Phase.SEND_MESSAGE, msg, addresses);
            }
        }

        @Override
        public synchronized void close() throws MessagingException {
            CLOSE_CALLS.incrementAndGet();
            setConnected(false);
        }
    }

    private SmtpConfig config;
    private SmtpClient client;
    private Session session;
    private MimeMessage testMessage;

    @BeforeEach
    void setUp() throws Exception {
        ProgrammableSmtpTransport.reset();

        config = new SmtpConfig();
        config.setHost("smtp.simulated.local");
        config.setPort(465);
        config.setUsername("tester@simulated.local");
        config.setPassword("SimulatedSecret123!");
        config.setFrom("tester@simulated.local");
        config.setTo("recipient@simulated.local");
        config.setSubject("Test Subject");
        config.setBody("<p>Test Body</p>");
        config.setEnableSsl(true);

        RetryConfig retryConfig = new RetryConfig();
        retryConfig.setMaxRetry(3);
        retryConfig.setBaseDelaySeconds(1);
        retryConfig.setMaxDelaySeconds(10);
        config.setRetry(retryConfig);

        client = new SmtpClient(config);
        // By default, bypass real wall-clock sleep delays for instant test execution
        client.setSleeper(millis -> {});

        // Configure Session with ProgrammableSmtpTransport provider
        Properties props = new Properties();
        props.put("mail.smtp.host", config.getHost());
        props.put("mail.smtp.port", String.valueOf(config.getPort()));
        session = Session.getInstance(props);

        Provider provider = new Provider(
                Provider.Type.TRANSPORT,
                "smtp",
                ProgrammableSmtpTransport.class.getName(),
                "LTM-TestDouble",
                "1.0"
        );
        session.setProvider(provider);

        testMessage = EmailMessageBuilder.createMimeMessage(
                session,
                config.getFrom(),
                config.getTo(),
                config.getSubject(),
                config.getBody(),
                Collections.singletonList(new File("attachments/sample.txt"))
        );
    }

    // =========================================================================
    // Test Category 1: Network Drops & Connection Glitches
    // =========================================================================

    @Nested
    @DisplayName("Layer 1: Network Drops & Connection Glitches (Simulated)")
    class NetworkDropSimulationTests {

        @Test
        @DisplayName("TC-SIM-01: Connect drop (SocketException Connection reset) triggers retry loop and recovers on attempt 2")
        void testConnectFailureWithSocketExceptionConnectionResetRetriesAndRecovers() throws Exception {
            // Attempt 1 fails during connect with Connection reset
            ProgrammableSmtpTransport.enqueueConnectFailure(
                    new MessagingException("Could not connect to SMTP host: smtp.simulated.local",
                            new SocketException("Connection reset"))
            );
            // Attempt 2 succeeds (no action queued)

            List<Long> sleepDelays = new ArrayList<>();
            client.setSleeper(sleepDelays::add);

            client.sendWithRetry(session, testMessage);

            assertEquals(2, ProgrammableSmtpTransport.CONNECT_CALLS.get(), "Connect must be called twice");
            assertEquals(1, ProgrammableSmtpTransport.SEND_MESSAGE_CALLS.get(), "SendMessage must succeed on attempt 2");
            assertEquals(2, ProgrammableSmtpTransport.CLOSE_CALLS.get(), "Transport.close() must be called on both attempts (REL-06)");

            assertEquals(1, sleepDelays.size(), "Should have slept exactly once between attempt 1 and 2");
            assertTrue(sleepDelays.get(0) >= 1000L, "Backoff delay must be >= 1000ms");
        }

        @Test
        @DisplayName("TC-SIM-02: DATA phase drop (Broken pipe mid-stream) retries and recovers on attempt 2")
        void testDataPhaseDropWithBrokenPipeRetriesAndRecovers() throws Exception {
            // Attempt 1: connect succeeds, but sendMessage throws SMTPSendFailedException with Broken pipe
            ProgrammableSmtpTransport.enqueueSendFailure(
                    new SMTPSendFailedException("DATA", 451,
                            "451 4.3.0 Transmission dropped mid-stream",
                            new SocketException("Broken pipe"), null, null, null)
            );
            // Attempt 2: succeeds cleanly

            client.sendWithRetry(session, testMessage);

            assertEquals(2, ProgrammableSmtpTransport.CONNECT_CALLS.get(), "Must reconnect for attempt 2");
            assertEquals(2, ProgrammableSmtpTransport.SEND_MESSAGE_CALLS.get(), "Must retry sendMessage on attempt 2");
            assertEquals(2, ProgrammableSmtpTransport.CLOSE_CALLS.get(), "Transport must be closed after each attempt");
        }

        @Test
        @DisplayName("TC-SIM-03: Max retries exhaustion on persistent network drops throws final MessagingException")
        void testMaxRetriesExhaustionOnPersistentDrops() {
            // Enqueue 4 failures (initial attempt + 3 retries)
            for (int i = 0; i < 4; i++) {
                ProgrammableSmtpTransport.enqueueConnectFailure(
                        new MessagingException("Network unavailable", new SocketException("Network is unreachable"))
                );
            }

            AtomicInteger sleepCount = new AtomicInteger(0);
            client.setSleeper(millis -> sleepCount.incrementAndGet());

            MessagingException thrown = assertThrows(MessagingException.class, () ->
                    client.sendWithRetry(session, testMessage));

            assertTrue(thrown.getMessage().contains("Network unavailable"));
            assertEquals(4, ProgrammableSmtpTransport.CONNECT_CALLS.get(), "Must attempt 4 times (1 + 3 retries)");
            assertEquals(0, ProgrammableSmtpTransport.SEND_MESSAGE_CALLS.get(), "No messages sent since connect failed");
            assertEquals(4, ProgrammableSmtpTransport.CLOSE_CALLS.get(), "Transport.close() called on all 4 attempts");
            assertEquals(3, sleepCount.get(), "Sleeper must be invoked 3 times between 4 attempts");
        }
    }

    // =========================================================================
    // Test Category 2: RFC 5321 Status Code Discrimination (4xx vs 5xx)
    // =========================================================================

    @Nested
    @DisplayName("Layer 2: RFC 5321 4xx Transient vs 5xx Permanent Discrimination")
    class RfcStatusDiscriminationTests {

        @Test
        @DisplayName("TC-SIM-04: Multi-stage 4xx transient deferrals (421 -> 450 -> 451) succeed on attempt 4")
        void testMultiStage4xxTransientDeferralsSucceed() throws Exception {
            // Attempt 1: 421 Service unavailable
            ProgrammableSmtpTransport.enqueueSendFailure(
                    new MessagingException("421 4.4.2 Service unavailable, closing transmission channel")
            );
            // Attempt 2: 450 Mailbox busy (typed SMTPAddressFailedException)
            ProgrammableSmtpTransport.enqueueSendFailure(
                    new SMTPAddressFailedException(new InternetAddress("recipient@simulated.local"),
                            "RCPT TO", 450, "450 4.2.1 Mailbox busy, try again later")
            );
            // Attempt 3: 451 Local error in processing (typed SMTPSenderFailedException)
            ProgrammableSmtpTransport.enqueueSendFailure(
                    new SMTPSenderFailedException(new InternetAddress("tester@simulated.local"),
                            "MAIL FROM", 451, "451 4.3.0 Local error in processing")
            );
            // Attempt 4: succeeds

            List<Long> delays = new ArrayList<>();
            client.setSleeper(delays::add);

            client.sendWithRetry(session, testMessage);

            assertEquals(4, ProgrammableSmtpTransport.CONNECT_CALLS.get());
            assertEquals(4, ProgrammableSmtpTransport.SEND_MESSAGE_CALLS.get());
            assertEquals(4, ProgrammableSmtpTransport.CLOSE_CALLS.get());
            assertEquals(3, delays.size(), "3 delays between 4 attempts");
            // Verify delays progression
            assertTrue(delays.get(0) <= delays.get(1), "Backoff must be monotonically non-decreasing (or bounded)");
        }

        @Test
        @DisplayName("TC-SIM-05: Permanent 550 User Unknown fails fast immediately with ZERO redundant retries")
        void testPermanent550UserUnknownFailsFastImmediately() {
            // Attempt 1: 550 User unknown (permanent)
            ProgrammableSmtpTransport.enqueueSendFailure(
                    new SMTPSendFailedException("DATA", 550, "550 5.1.1 User unknown", null, null, null, null)
            );

            AtomicInteger sleepCount = new AtomicInteger(0);
            client.setSleeper(millis -> sleepCount.incrementAndGet());

            SMTPSendFailedException thrown = assertThrows(SMTPSendFailedException.class, () ->
                    client.sendWithRetry(session, testMessage));

            assertEquals(550, thrown.getReturnCode());
            assertEquals(1, ProgrammableSmtpTransport.CONNECT_CALLS.get(), "Fail-fast: strictly 1 connect call");
            assertEquals(1, ProgrammableSmtpTransport.SEND_MESSAGE_CALLS.get(), "Fail-fast: strictly 1 send call");
            assertEquals(1, ProgrammableSmtpTransport.CLOSE_CALLS.get(), "Transport must be closed even on permanent error");
            assertEquals(0, sleepCount.get(), "Zero sleep delays on permanent failure!");
        }

        @Test
        @DisplayName("TC-SIM-06: Authentication failure (535 Bad Credentials) fails fast immediately with ZERO retries")
        void testAuthenticationFailure535FailsFastImmediately() {
            // Attempt 1: AuthenticationFailedException during connect
            ProgrammableSmtpTransport.enqueueConnectFailure(
                    new AuthenticationFailedException("535 5.7.8 Username and Password not accepted")
            );

            AtomicInteger sleepCount = new AtomicInteger(0);
            client.setSleeper(millis -> sleepCount.incrementAndGet());

            AuthenticationFailedException thrown = assertThrows(AuthenticationFailedException.class, () ->
                    client.sendWithRetry(session, testMessage));

            assertTrue(thrown.getMessage().contains("535"));
            assertEquals(1, ProgrammableSmtpTransport.CONNECT_CALLS.get(), "Only 1 connect attempt");
            assertEquals(0, ProgrammableSmtpTransport.SEND_MESSAGE_CALLS.get(), "SendMessage not reached");
            assertEquals(1, ProgrammableSmtpTransport.CLOSE_CALLS.get(), "Transport closed cleanly");
            assertEquals(0, sleepCount.get(), "No retries allowed for authentication errors");
        }

        @Test
        @DisplayName("TC-SIM-07: Permanent 501 Syntax error in parameters fails fast without retrying")
        void testPermanent501SyntaxErrorFailsFast() {
            ProgrammableSmtpTransport.enqueueSendFailure(
                    new SMTPSendFailedException("MAIL FROM", 501, "501 5.5.2 Syntax error in parameters", null, null, null, null)
            );

            SMTPSendFailedException thrown = assertThrows(SMTPSendFailedException.class, () ->
                    client.sendWithRetry(session, testMessage));

            assertEquals(501, thrown.getReturnCode());
            assertEquals(1, ProgrammableSmtpTransport.CONNECT_CALLS.get());
            assertEquals(1, ProgrammableSmtpTransport.SEND_MESSAGE_CALLS.get());
            assertEquals(1, ProgrammableSmtpTransport.CLOSE_CALLS.get());
        }
    }

    // =========================================================================
    // Test Category 3: Resource Cleanup & Safety (REL-06)
    // =========================================================================

    @Nested
    @DisplayName("Layer 3: Guaranteed Resource Cleanup & Robustness (REL-06)")
    class ResourceCleanupAndSafetyTests {

        @Test
        @DisplayName("TC-SIM-08: Transport.close() exception does not mask original sendMessage exception")
        void testCloseExceptionDoesNotMaskOriginalSendMessageException() throws Exception {
            // Custom provider where close() throws an exception
            Provider badCloseProvider = new Provider(
                    Provider.Type.TRANSPORT,
                    "smtp",
                    FailingCloseTransport.class.getName(),
                    "LTM-BadClose",
                    "1.0"
            );
            session.setProvider(badCloseProvider);

            // Expect the original SMTPSendFailedException to be thrown, NOT the close exception
            SMTPSendFailedException thrown = assertThrows(SMTPSendFailedException.class, () ->
                    client.sendEmail(session, testMessage));

            assertEquals(550, thrown.getReturnCode());
            assertTrue(FailingCloseTransport.closeAttempted);
        }

        @Test
        @DisplayName("TC-SIM-09: Thread interruption during backoff sleep terminates retry loop and restores flag")
        void testThreadInterruptionDuringSleepThrowsAndPreservesFlag() {
            // Attempt 1: transient failure
            ProgrammableSmtpTransport.enqueueConnectFailure(
                    new MessagingException("Transient error", new SocketException("Connection reset"))
            );

            // Sleeper that simulates Thread.sleep() throwing InterruptedException
            client.setSleeper(millis -> {
                throw new InterruptedException("Simulated shutdown interrupt");
            });

            RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                    client.sendWithRetry(session, testMessage));

            assertTrue(thrown.getMessage().contains("ngắt quãng"));
            assertTrue(Thread.currentThread().isInterrupted(), "Thread interrupt flag must be restored");
            // Clear interrupt flag so other tests aren't affected
            Thread.interrupted();
            assertEquals(1, ProgrammableSmtpTransport.CLOSE_CALLS.get(), "Transport closed on interrupted attempt");
        }
    }

    // Custom transport for TC-SIM-08
    public static class FailingCloseTransport extends Transport {
        public static volatile boolean closeAttempted = false;

        public FailingCloseTransport(Session session, URLName urlname) {
            super(session, urlname);
        }

        @Override
        public synchronized void connect() throws MessagingException {
            setConnected(true);
        }

        @Override
        public synchronized void sendMessage(Message msg, Address[] addresses) throws MessagingException {
            throw new SMTPSendFailedException("DATA", 550, "550 Mailbox unavailable", null, null, null, null);
        }

        @Override
        public synchronized void close() throws MessagingException {
            closeAttempted = true;
            throw new MessagingException("Simulated socket error during close()");
        }
    }

    // =========================================================================
    // Test Category 4: Mockito Test Doubles Infrastructure (TEST-01)
    // =========================================================================

    @Nested
    @DisplayName("Layer 4: Mockito Framework Test Doubles (TEST-01)")
    class MockitoTestDoublesTests {

        @Test
        @DisplayName("TC-SIM-10: Mockito verifies Sleeper invocation count and captured delay values")
        void testMockitoVerifiesSleeperInvocationAndBackoffDelays() throws Exception {
            // Configure 2 transient failures then success
            ProgrammableSmtpTransport.enqueueConnectFailure(
                    new MessagingException("Glitch 1", new SocketException("Reset"))
            );
            ProgrammableSmtpTransport.enqueueConnectFailure(
                    new MessagingException("Glitch 2", new SocketException("Reset"))
            );

            // Create Mockito mock of Sleeper
            SmtpClient.Sleeper mockSleeper = mock(SmtpClient.Sleeper.class);
            client.setSleeper(mockSleeper);

            client.sendWithRetry(session, testMessage);

            // Capture the arguments passed to mockSleeper.sleep(long)
            ArgumentCaptor<Long> delayCaptor = ArgumentCaptor.forClass(Long.class);
            verify(mockSleeper, times(2)).sleep(delayCaptor.capture());

            List<Long> capturedDelays = delayCaptor.getAllValues();
            assertEquals(2, capturedDelays.size());
            assertTrue(capturedDelays.get(0) >= 1000L, "First backoff delay >= 1000ms");
            assertTrue(capturedDelays.get(1) >= 1000L, "Second backoff delay >= 1000ms");
        }

        @Test
        @DisplayName("TC-SIM-11: Mockito verifies zero sleep invocations on immediate success")
        void testMockitoVerifiesZeroSleepOnImmediateSuccess() throws Exception {
            SmtpClient.Sleeper mockSleeper = mock(SmtpClient.Sleeper.class);
            client.setSleeper(mockSleeper);

            client.sendWithRetry(session, testMessage);

            verify(mockSleeper, never()).sleep(anyLong());
            assertEquals(1, ProgrammableSmtpTransport.CONNECT_CALLS.get());
            assertEquals(1, ProgrammableSmtpTransport.SEND_MESSAGE_CALLS.get());
            assertEquals(1, ProgrammableSmtpTransport.CLOSE_CALLS.get());
        }

        @Test
        @DisplayName("TC-SIM-12: Mockito verifies zero sleep invocations on permanent 5xx failure")
        void testMockitoVerifiesZeroSleepOnPermanentFailure() throws Exception {
            ProgrammableSmtpTransport.enqueueSendFailure(
                    new SMTPSendFailedException("DATA", 554, "554 Transaction failed", null, null, null, null)
            );

            SmtpClient.Sleeper mockSleeper = mock(SmtpClient.Sleeper.class);
            client.setSleeper(mockSleeper);

            assertThrows(SMTPSendFailedException.class, () ->
                    client.sendWithRetry(session, testMessage));

            verify(mockSleeper, never()).sleep(anyLong());
        }
    }
}
