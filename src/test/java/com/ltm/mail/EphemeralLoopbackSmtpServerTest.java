package com.ltm.mail;

import com.ltm.mail.client.EmailMessageBuilder;
import com.ltm.mail.client.SmtpClient;
import com.ltm.mail.config.RetryConfig;
import com.ltm.mail.config.SmtpConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ephemeral Loopback SMTP Server Test Suite (Milestone 3 / R3).
 * Verifies real OS-level TCP network failure semantics against loopback (127.0.0.1:0):
 * - Real OS TCP RST packet delivery via SO_LINGER(true, 0).
 * - Real OS connection refusal (ConnectException) on closed loopback port.
 * - Real OS greeting banner disconnect.
 */
public class EphemeralLoopbackSmtpServerTest {

    public enum DropMode {
        RESET_IMMEDIATELY,      // SO_LINGER(true, 0) RST on accept
        DROP_AFTER_GREETING,    // Send 220 banner, then SO_LINGER(true, 0) RST
        HANG                    // Accept and stay silent (trigger timeout)
    }

    public static class EphemeralLoopbackSmtpServer implements Closeable {
        private final ServerSocket serverSocket;
        private final ExecutorService executor;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicInteger acceptedConnections = new AtomicInteger(0);

        public EphemeralLoopbackSmtpServer(DropMode mode) throws IOException {
            this.serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            this.executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "EphemeralLoopbackServer-" + serverSocket.getLocalPort());
                t.setDaemon(true);
                return t;
            });

            this.executor.submit(() -> {
                while (running.get() && !serverSocket.isClosed()) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        acceptedConnections.incrementAndGet();
                        handleClient(clientSocket, mode);
                    } catch (IOException ignored) {
                        // Normal during shutdown
                    }
                }
            });
        }

        private void handleClient(Socket socket, DropMode mode) {
            try (socket) {
                switch (mode) {
                    case RESET_IMMEDIATELY -> {
                        // Force TCP RST packet by setting SO_LINGER to 0 and closing
                        socket.setSoLinger(true, 0);
                    }
                    case DROP_AFTER_GREETING -> {
                        OutputStream out = socket.getOutputStream();
                        out.write("220 127.0.0.1 ESMTP TestReady\r\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        socket.setSoLinger(true, 0);
                    }
                    case HANG -> {
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }

        public int getPort() {
            return serverSocket.getLocalPort();
        }

        public int getAcceptedConnections() {
            return acceptedConnections.get();
        }

        @Override
        public void close() throws IOException {
            running.set(false);
            executor.shutdownNow();
            if (!serverSocket.isClosed()) {
                serverSocket.close();
            }
        }
    }

    private EphemeralLoopbackSmtpServer server;

    @BeforeEach
    void setUp() {
        server = null;
    }

    @AfterEach
    void tearDown() throws IOException {
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("TC-NET-01: Real OS TCP Connection Refusal (ConnectException) on closed loopback port")
    void testRealOsConnectionRefusalOnClosedPort() throws Exception {
        // Allocate ephemeral port and immediately close it
        ServerSocket temp = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        int closedPort = temp.getLocalPort();
        temp.close();

        SmtpConfig config = new SmtpConfig();
        config.setHost("127.0.0.1");
        config.setPort(closedPort);
        config.setUsername("test@127.0.0.1");
        config.setPassword("secret");
        config.setFrom("test@127.0.0.1");
        config.setTo("dest@127.0.0.1");
        config.setSubject("TCP Refusal Test");
        config.setBody("<p>Testing Connection Refusal</p>");
        config.setEnableSsl(false);

        RetryConfig retryConfig = new RetryConfig();
        retryConfig.setMaxRetry(2); // 1 initial + 2 retries = 3 attempts total
        retryConfig.setBaseDelaySeconds(1);
        retryConfig.setMaxDelaySeconds(5);
        config.setRetry(retryConfig);

        SmtpClient client = new SmtpClient(config);
        AtomicInteger retryAttempts = new AtomicInteger(0);
        client.setSleeper(millis -> retryAttempts.incrementAndGet());

        Properties props = new Properties();
        props.put("mail.smtp.host", "127.0.0.1");
        props.put("mail.smtp.port", String.valueOf(closedPort));
        props.put("mail.smtp.connectiontimeout", "1000");
        props.put("mail.smtp.timeout", "1000");
        Session session = Session.getInstance(props);

        MimeMessage message = EmailMessageBuilder.createMimeMessage(
                session,
                config.getFrom(),
                config.getTo(),
                config.getSubject(),
                config.getBody(),
                Collections.singletonList(new File("attachments/sample.txt"))
        );

        MessagingException ex = assertThrows(MessagingException.class, () ->
                client.sendWithRetry(session, message));

        // ConnectException should be in the exception chain
        assertTrue(ex.getMessage().contains("Could not connect to SMTP host") || ex.getMessage().contains("connect"));
        assertEquals(2, retryAttempts.get(), "Must retry exactly maxRetry (2) times before giving up");
    }

    @Test
    @DisplayName("TC-NET-02: Real OS TCP RST packet delivery via SO_LINGER(true, 0) triggers retry loop")
    void testRealOsTcpResetViaSoLingerTriggersRetries() throws Exception {
        server = new EphemeralLoopbackSmtpServer(DropMode.RESET_IMMEDIATELY);
        int port = server.getPort();

        SmtpConfig config = new SmtpConfig();
        config.setHost("127.0.0.1");
        config.setPort(port);
        config.setUsername("test@127.0.0.1");
        config.setPassword("secret");
        config.setFrom("test@127.0.0.1");
        config.setTo("dest@127.0.0.1");
        config.setSubject("TCP RST Test");
        config.setBody("<p>Testing TCP RST</p>");
        config.setEnableSsl(false);

        RetryConfig retryConfig = new RetryConfig();
        retryConfig.setMaxRetry(2); // 1 initial + 2 retries = 3 attempts total
        retryConfig.setBaseDelaySeconds(1);
        retryConfig.setMaxDelaySeconds(5);
        config.setRetry(retryConfig);

        SmtpClient client = new SmtpClient(config);
        AtomicInteger retryAttempts = new AtomicInteger(0);
        client.setSleeper(millis -> retryAttempts.incrementAndGet());

        Properties props = new Properties();
        props.put("mail.smtp.host", "127.0.0.1");
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.connectiontimeout", "1500");
        props.put("mail.smtp.timeout", "1500");
        Session session = Session.getInstance(props);

        MimeMessage message = EmailMessageBuilder.createMimeMessage(
                session,
                config.getFrom(),
                config.getTo(),
                config.getSubject(),
                config.getBody(),
                Collections.singletonList(new File("attachments/sample.txt"))
        );

        assertThrows(MessagingException.class, () ->
                client.sendWithRetry(session, message));

        // Verify that server actually accepted connections and client retried
        assertTrue(server.getAcceptedConnections() >= 2, "Server should have accepted multiple connection attempts");
        assertEquals(2, retryAttempts.get(), "Client must have retried 2 times");
    }

    @Test
    @DisplayName("TC-NET-03: Real OS TCP drop after 220 greeting banner triggers retry loop")
    void testRealOsDropAfterGreetingBannerTriggersRetries() throws Exception {
        server = new EphemeralLoopbackSmtpServer(DropMode.DROP_AFTER_GREETING);
        int port = server.getPort();

        SmtpConfig config = new SmtpConfig();
        config.setHost("127.0.0.1");
        config.setPort(port);
        config.setUsername("test@127.0.0.1");
        config.setPassword("secret");
        config.setFrom("test@127.0.0.1");
        config.setTo("dest@127.0.0.1");
        config.setSubject("Drop After Greeting Test");
        config.setBody("<p>Testing drop after greeting</p>");
        config.setEnableSsl(false);

        RetryConfig retryConfig = new RetryConfig();
        retryConfig.setMaxRetry(2);
        retryConfig.setBaseDelaySeconds(1);
        retryConfig.setMaxDelaySeconds(5);
        config.setRetry(retryConfig);

        SmtpClient client = new SmtpClient(config);
        AtomicInteger retryAttempts = new AtomicInteger(0);
        client.setSleeper(millis -> retryAttempts.incrementAndGet());

        Properties props = new Properties();
        props.put("mail.smtp.host", "127.0.0.1");
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.connectiontimeout", "1500");
        props.put("mail.smtp.timeout", "1500");
        Session session = Session.getInstance(props);

        MimeMessage message = EmailMessageBuilder.createMimeMessage(
                session,
                config.getFrom(),
                config.getTo(),
                config.getSubject(),
                config.getBody(),
                Collections.singletonList(new File("attachments/sample.txt"))
        );

        assertThrows(MessagingException.class, () ->
                client.sendWithRetry(session, message));

        assertTrue(server.getAcceptedConnections() >= 2);
        assertEquals(2, retryAttempts.get());
    }
}
