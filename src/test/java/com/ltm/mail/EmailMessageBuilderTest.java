package com.ltm.mail;

import com.ltm.mail.client.EmailMessageBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import java.io.File;
import java.util.Collections;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

public class EmailMessageBuilderTest {

    @Test
    @DisplayName("Khởi tạo MimeMessage với Multipart/mixed và file đính kèm chính xác")
    void testCreateMimeMessageWithAttachment() throws Exception {
        Session session = Session.getInstance(new Properties());

        File sampleFile = new File("attachments/sample.txt");
        assertTrue(sampleFile.exists(), "File attachments/sample.txt phải tồn tại");

        MimeMessage message = EmailMessageBuilder.createMimeMessage(
                session,
                "sender@example.com",
                "receiver@example.com",
                "Tiêu đề thử nghiệm Tiếng Việt có dấu",
                "<h3>Xin chào</h3><p>Nội dung HTML</p>",
                Collections.singletonList(sampleFile)
        );

        assertNotNull(message);
        assertEquals("Tiêu đề thử nghiệm Tiếng Việt có dấu", message.getSubject());
        assertEquals("sender@example.com", message.getFrom()[0].toString());
        assertEquals("receiver@example.com", message.getAllRecipients()[0].toString());

        // Kiểm tra nội dung là Multipart
        Object content = message.getContent();
        assertTrue(content instanceof Multipart, "Nội dung phải là Multipart");

        Multipart multipart = (Multipart) content;
        // Gồm 2 phần: Text/HTML body part + Attachment part
        assertEquals(2, multipart.getCount());
        assertTrue(multipart.getContentType().toLowerCase().contains("multipart/mixed"));
    }
}
