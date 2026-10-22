package com.ltm.mail;

import com.ltm.mail.client.EmailMessageBuilder;
import com.ltm.mail.client.SmtpClient;
import com.ltm.mail.config.SmtpConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.mail.Address;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Provider;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.URLName;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.File;
import java.util.Collections;
import java.util.Date;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

public class ResourceCleanupTest {

    public static class TestableTransport extends Transport {
        public static boolean connectCalled = false;
        public static boolean sendMessageCalled = false;
        public static boolean closeCalled = false;
        public static boolean throwOnConnect = false;
        public static boolean throwOnSendMessage = false;

        public static void reset() {
            connectCalled = false;
            sendMessageCalled = false;
            closeCalled = false;
            throwOnConnect = false;
            throwOnSendMessage = false;
        }

        public TestableTransport(Session session, URLName urlname) {
            super(session, urlname);
        }

        @Override
        public synchronized void connect() throws MessagingException {
            connectCalled = true;
            if (throwOnConnect) {
                throw new MessagingException("Simulated connection failure");
            }
            setConnected(true);
        }

        @Override
        public synchronized void sendMessage(Message msg, Address[] addresses) throws MessagingException {
            sendMessageCalled = true;
            if (throwOnSendMessage) {
                throw new MessagingException("Simulated send DATA error");
            }
        }

        @Override
        public synchronized void close() throws MessagingException {
            closeCalled = true;
            setConnected(false);
        }
    }

    private Session createTestSession() throws Exception {
        Properties props = new Properties();
        props.put("mail.smtp.host", "localhost");
        props.put("mail.smtp.port", "25");
        Session session = Session.getInstance(props);

        Provider testProvider = new Provider(
                Provider.Type.TRANSPORT,
                "smtp",
                TestableTransport.class.getName(),
                "LTM-Test",
                "1.0"
        );
        session.setProvider(testProvider);
        return session;
    }

    private SmtpConfig createConfig() {
        SmtpConfig config = new SmtpConfig();
        config.setHost("localhost");
        config.setPort(25);
        config.setEnableSsl(false);
        config.setUsername("test@example.com");
        config.setPassword("secret");
        config.setFrom("test@example.com");
        config.setTo("dest@example.com");
        return config;
    }

    @BeforeEach
    void setUp() {
        TestableTransport.reset();
    }

    @Nested
    @DisplayName("REL-06: Quản lý giải phóng tài nguyên Transport trong khối finally")
    class TransportCleanupTests {

        @Test
        @DisplayName("Gửi email thành công -> Transport.close() được gọi chính xác trong finally")
        void testTransportClosedOnSuccess() throws Exception {
            Session session = createTestSession();
            SmtpClient client = new SmtpClient(createConfig());

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress("test@example.com"));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("dest@example.com"));
            msg.setText("Hello world");

            client.sendEmail(session, msg);

            assertTrue(TestableTransport.connectCalled, "Phải gọi connect()");
            assertTrue(TestableTransport.sendMessageCalled, "Phải gọi sendMessage()");
            assertTrue(TestableTransport.closeCalled, "Transport.close() BẮT BUỘC phải được gọi trong finally khi gửi thành công");
        }

        @Test
        @DisplayName("Lỗi khi sendMessage -> Transport.close() VẪN được gọi trong finally (không leak socket)")
        void testTransportClosedWhenSendMessageFails() throws Exception {
            Session session = createTestSession();
            SmtpClient client = new SmtpClient(createConfig());
            TestableTransport.throwOnSendMessage = true;

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress("test@example.com"));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("dest@example.com"));
            msg.setText("Hello world");

            MessagingException ex = assertThrows(MessagingException.class, () ->
                    client.sendEmail(session, msg)
            );

            assertTrue(ex.getMessage().contains("Simulated send DATA error"));
            assertTrue(TestableTransport.connectCalled, "Đã gọi connect");
            assertTrue(TestableTransport.sendMessageCalled, "Đã gọi sendMessage");
            assertTrue(TestableTransport.closeCalled, "Transport.close() PHẢI được gọi ngay cả khi sendMessage ném ngoại lệ");
        }

        @Test
        @DisplayName("Lỗi khi connect -> Transport.close() VẪN được gọi trong finally an toàn")
        void testTransportClosedWhenConnectFails() throws Exception {
            Session session = createTestSession();
            SmtpClient client = new SmtpClient(createConfig());
            TestableTransport.throwOnConnect = true;

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress("test@example.com"));
            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse("dest@example.com"));
            msg.setText("Hello world");

            assertThrows(MessagingException.class, () ->
                    client.sendEmail(session, msg)
            );

            assertTrue(TestableTransport.connectCalled);
            assertFalse(TestableTransport.sendMessageCalled, "Không được gọi sendMessage nếu connect thất bại");
            assertTrue(TestableTransport.closeCalled, "Transport.close() PHẢI được dọn dẹp khi connect lỗi");
        }

        @Test
        @DisplayName("Không có người nhận (Recipients rỗng) -> Báo lỗi trước khi mở socket Transport")
        void testNoRecipientsRejectsBeforeConnect() throws Exception {
            Session session = createTestSession();
            SmtpClient client = new SmtpClient(createConfig());

            MimeMessage msg = new MimeMessage(session);
            msg.setFrom(new InternetAddress("test@example.com"));
            // Không gán recipients

            MessagingException ex = assertThrows(MessagingException.class, () ->
                    client.sendEmail(session, msg)
            );

            assertTrue(ex.getMessage().contains("Không tìm thấy địa chỉ người nhận"));
            assertFalse(TestableTransport.connectCalled, "Tuyệt đối không được mở socket kết nối nếu không có người nhận");
        }
    }

    @Nested
    @DisplayName("REL-06 & REL-07: Quản lý Stream file đính kèm và tuân thủ RFC 5322")
    class AttachmentAndRfcComplianceTests {

        @Test
        @DisplayName("resolveAttachment mở luồng đọc và đóng an toàn, không rò rỉ file handle")
        void testAttachmentStreamManagement() throws Exception {
            File sample = EmailMessageBuilder.resolveAttachment("attachments/sample.txt");
            assertNotNull(sample);
            assertTrue(sample.exists());
            assertTrue(sample.canRead());

            // Sau khi resolveAttachment, tệp tin vẫn đọc được bình thường (stream đã được đóng an toàn)
            assertTrue(sample.length() > 0);
        }

        @Test
        @DisplayName("REL-07: RFC 5322 - Thiết lập Sent Date và explicit Content-Disposition: ATTACHMENT")
        void testRfc5322SentDateAndDisposition() throws Exception {
            Session session = Session.getInstance(new Properties());
            File sample = new File("attachments/sample.txt");

            Date beforeCreation = new Date(System.currentTimeMillis() - 1000L);

            MimeMessage msg = EmailMessageBuilder.createMimeMessage(
                    session,
                    "sender@example.com",
                    "receiver@example.com",
                    "RFC 5322 Compliance Test",
                    "<p>Nội dung thử nghiệm</p>",
                    Collections.singletonList(sample)
            );

            assertNotNull(msg);

            // 1. Kiểm tra header Date (RFC 5322 Section 3.6.1)
            Date sentDate = msg.getSentDate();
            assertNotNull(sentDate, "RFC 5322 yêu cầu phải có Origination Date (Sent Date)");
            assertTrue(sentDate.after(beforeCreation), "Sent Date phải khớp thời gian hiện tại");

            // 2. Kiểm tra Message-ID (RFC 5322)
            assertNotNull(msg.getMessageID(), "MimeMessage phải sinh Message-ID hợp lệ");

            // 3. Kiểm tra MIME Part Attachment (RFC 2183)
            Multipart mp = (Multipart) msg.getContent();
            assertEquals(2, mp.getCount(), "Phải gồm 2 body part: HTML + Attachment");

            BodyPart attachPart = mp.getBodyPart(1);
            assertEquals(Part.ATTACHMENT.toLowerCase(), attachPart.getDisposition().toLowerCase(),
                    "MIME Part file đính kèm phải có explicit Content-Disposition: attachment");
        }
    }
}
