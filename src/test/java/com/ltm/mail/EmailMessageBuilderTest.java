package com.ltm.mail;

import com.ltm.mail.client.EmailMessageBuilder;
import com.ltm.mail.config.SmtpConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.mail.BodyPart;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

public class EmailMessageBuilderTest {

    private final Session dummySession = Session.getInstance(new Properties());

    @Test
    @DisplayName("Khởi tạo MimeMessage với Multipart/mixed và file đính kèm chính xác")
    void testCreateMimeMessageWithAttachment() throws Exception {
        File sampleFile = new File("attachments/sample.txt");
        assertTrue(sampleFile.exists(), "File attachments/sample.txt phải tồn tại");

        MimeMessage message = EmailMessageBuilder.createMimeMessage(
                dummySession,
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

    @Nested
    @DisplayName("R3 - Edge Case 1: Xử lý tệp tin đính kèm 0-byte (Zero-byte Empty File)")
    class ZeroByteAttachmentTests {

        @Test
        @DisplayName("TC-EDGE-01: File 0-byte được resolveAttachment chấp nhận, ghi log cảnh báo và không ném ngoại lệ")
        void testZeroByteAttachmentResolve() throws Exception {
            File emptyFile = File.createTempFile("empty_test_", ".txt", new File("attachments"));
            emptyFile.deleteOnExit();

            try {
                assertEquals(0, emptyFile.length(), "File mới tạo phải có dung lượng đúng 0 bytes");

                File resolved = EmailMessageBuilder.resolveAttachment(emptyFile.getPath());
                assertNotNull(resolved);
                assertEquals(0, resolved.length());
                assertTrue(resolved.exists());
            } finally {
                emptyFile.delete();
            }
        }

        @Test
        @DisplayName("TC-EDGE-02: File 0-byte tạo MimeBodyPart hợp lệ, Content-Disposition là ATTACHMENT và payload rỗng")
        void testZeroByteAttachmentGeneratesValidMimePart() throws Exception {
            File emptyFile = File.createTempFile("zero_payload_", ".dat", new File("attachments"));
            emptyFile.deleteOnExit();

            try {
                MimeMessage message = EmailMessageBuilder.createMimeMessage(
                        dummySession,
                        "sender@example.com",
                        "receiver@example.com",
                        "Thư đính kèm file rỗng 0 bytes",
                        "<p>Nội dung thử nghiệm</p>",
                        Collections.singletonList(emptyFile)
                );

                assertNotNull(message);
                Multipart mp = (Multipart) message.getContent();
                assertEquals(2, mp.getCount(), "Phải có đúng 2 phần: Body HTML và File đính kèm 0-byte");

                BodyPart attachPart = mp.getBodyPart(1);
                assertEquals(Part.ATTACHMENT.toLowerCase(), attachPart.getDisposition().toLowerCase());
                assertEquals(emptyFile.getName(), attachPart.getFileName());

                // Đọc InputStream của BodyPart: số byte đọc được phải đúng bằng 0
                try (java.io.InputStream is = attachPart.getInputStream()) {
                    byte[] bytes = is.readAllBytes();
                    assertEquals(0, bytes.length, "Dữ liệu đọc từ file đính kèm 0-byte phải có độ dài 0");
                }

                // Serialize toàn bộ MimeMessage ra ByteArrayOutputStream để kiểm chứng tính toàn vẹn MIME
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                message.writeTo(baos);
                String rawMime = baos.toString(StandardCharsets.UTF_8);
                assertTrue(rawMime.contains("Content-Disposition: attachment"));
                assertTrue(rawMime.contains(emptyFile.getName()));
            } finally {
                emptyFile.delete();
            }
        }

        @Test
        @DisplayName("TC-EDGE-03: Đính kèm đồng thời nhiều file gồm cả 0-byte và file có dữ liệu")
        void testMultipleAttachmentsMixedSizes() throws Exception {
            File emptyFile1 = File.createTempFile("empty_first_", ".txt", new File("attachments"));
            File emptyFile2 = File.createTempFile("empty_second_", ".log", new File("attachments"));
            File normalFile = new File("attachments/sample.txt");
            emptyFile1.deleteOnExit();
            emptyFile2.deleteOnExit();

            try {
                List<File> attachments = Arrays.asList(emptyFile1, normalFile, emptyFile2);
                MimeMessage message = EmailMessageBuilder.createMimeMessage(
                        dummySession,
                        "sender@example.com",
                        "receiver@example.com",
                        "Đa file đính kèm",
                        "<p>3 files</p>",
                        attachments
                );

                Multipart mp = (Multipart) message.getContent();
                assertEquals(4, mp.getCount(), "1 Body HTML + 3 Attachments = 4 parts");

                assertEquals(emptyFile1.getName(), mp.getBodyPart(1).getFileName());
                assertEquals(normalFile.getName(), mp.getBodyPart(2).getFileName());
                assertEquals(emptyFile2.getName(), mp.getBodyPart(3).getFileName());
            } finally {
                emptyFile1.delete();
                emptyFile2.delete();
            }
        }

        @Test
        @DisplayName("TC-EDGE-04: File 0-byte có tên tiếng Việt có dấu được mã hóa RFC 2047 thành công")
        void testZeroByteAttachmentWithVietnameseFilename() throws Exception {
            File emptyVietnameseFile = new File("attachments", "tập_tin_rỗng_0_byte.txt");
            if (emptyVietnameseFile.exists()) {
                emptyVietnameseFile.delete();
            }
            assertTrue(emptyVietnameseFile.createNewFile());
            emptyVietnameseFile.deleteOnExit();

            try {
                File resolved = EmailMessageBuilder.resolveAttachment(emptyVietnameseFile.getPath());
                assertEquals(0, resolved.length());

                MimeMessage message = EmailMessageBuilder.createMimeMessage(
                        dummySession,
                        "sender@example.com",
                        "receiver@example.com",
                        "File rỗng tiếng Việt",
                        "<p>Nội dung</p>",
                        Collections.singletonList(resolved)
                );

                Multipart mp = (Multipart) message.getContent();
                BodyPart part = mp.getBodyPart(1);
                String rawFileName = part.getFileName();
                assertNotNull(rawFileName);
                String decodedName = MimeUtility.decodeText(rawFileName);
                assertEquals("tập_tin_rỗng_0_byte.txt", decodedName);
            } finally {
                if (emptyVietnameseFile.exists()) {
                    emptyVietnameseFile.delete();
                }
            }
        }
    }

    @Nested
    @DisplayName("R3 - Edge Case 2: Xử lý tệp tin không tồn tại và đường dẫn bất thường (Non-Existent Files)")
    class NonExistentFileTests {

        @Test
        @DisplayName("TC-EDGE-05: Đường dẫn null hoặc chuỗi trắng ném IllegalArgumentException kèm thông điệp rõ ràng")
        void testNullOrWhitespaceAttachmentPath() {
            IllegalArgumentException ex1 = assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.resolveAttachment(null));
            assertTrue(ex1.getMessage().contains("không được rỗng"));

            IllegalArgumentException ex2 = assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.resolveAttachment(""));
            assertTrue(ex2.getMessage().contains("không được rỗng"));

            IllegalArgumentException ex3 = assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.resolveAttachment("   \t  "));
            assertTrue(ex3.getMessage().contains("không được rỗng"));
        }

        @Test
        @DisplayName("TC-EDGE-06: File không tồn tại ném IllegalArgumentException kèm đường dẫn chi tiết")
        void testNonExistentFileThrowsIllegalArgumentException() {
            String missingPath = "attachments/missing_file_definitely_not_exist_9999.pdf";
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.resolveAttachment(missingPath));

            assertTrue(ex.getMessage().contains("File đính kèm không tồn tại"),
                    "Thông điệp phải nêu rõ file không tồn tại");
            assertTrue(ex.getMessage().contains(missingPath),
                    "Thông điệp phải chứa đường dẫn file lỗi để dễ chẩn đoán");
        }

        @Test
        @DisplayName("TC-EDGE-07: File không tồn tại có tên tiếng Việt có dấu ném IllegalArgumentException chứa tên file")
        void testNonExistentVietnameseFileName() {
            String vietnamesePath = "attachments/hồ_sơ_tài_liệu_chưa_tạo.docx";
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.resolveAttachment(vietnamesePath));

            assertTrue(ex.getMessage().contains("File đính kèm không tồn tại"));
            assertTrue(ex.getMessage().contains(vietnamesePath));
        }

        @Test
        @DisplayName("TC-EDGE-08: Đường dẫn trỏ tới thư mục thay vì file thông thường ném IllegalArgumentException")
        void testDirectoryPathRejected() {
            IllegalArgumentException exDir = assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.resolveAttachment("attachments"));
            assertTrue(exDir.getMessage().contains("không phải là file thông thường"));

            IllegalArgumentException exDot = assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.resolveAttachment("."));
            assertTrue(exDot.getMessage().contains("không phải là file thông thường"));
        }

        @Test
        @DisplayName("TC-EDGE-09: buildMessage lan truyền IllegalArgumentException khi một file trong danh sách không tồn tại")
        void testBuildMessagePropagatesExceptionOnMissingFile() {
            SmtpConfig config = new SmtpConfig();
            config.setFrom("sender@test.com");
            config.setTo("receiver@test.com");
            config.setSubject("Chủ đề");
            config.setBody("Nội dung");
            config.setAttachmentPaths(Arrays.asList(
                    "attachments/sample.txt",
                    "attachments/does_not_exist_xyz.bin"
            ));

            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    EmailMessageBuilder.buildMessage(dummySession, config));
            assertTrue(ex.getMessage().contains("does_not_exist_xyz.bin"));
        }

        @Test
        @DisplayName("TC-EDGE-10: buildMessage tự động bỏ qua phần tử null hoặc chuỗi trắng trong danh sách đính kèm")
        void testBuildMessageSkipsNullAndWhitespaceElements() throws Exception {
            SmtpConfig config = new SmtpConfig();
            config.setFrom("sender@test.com");
            config.setTo("receiver@test.com");
            config.setSubject("Test Null Items");
            config.setBody("Hello");
            config.setAttachmentPaths(Arrays.asList(
                    null,
                    "",
                    "    ",
                    "attachments/sample.txt",
                    null
            ));

            MimeMessage msg = EmailMessageBuilder.buildMessage(dummySession, config);
            assertNotNull(msg);
            Multipart mp = (Multipart) msg.getContent();
            // Chỉ có 1 body HTML + 1 file hợp lệ (sample.txt) = 2 parts
            assertEquals(2, mp.getCount());
            assertEquals("sample.txt", mp.getBodyPart(1).getFileName());
        }
    }

    @Nested
    @DisplayName("R3 - Edge Case 3: Tiếng Việt có dấu, Emoji, Đa ngôn ngữ & Ký tự đặc biệt UTF-8")
    class Utf8AndSpecialCharactersTests {

        @Test
        @DisplayName("TC-EDGE-11: Tiêu đề tiếng Việt chứa đầy đủ 5 thanh điệu và các nguyên âm có dấu phức tạp")
        void testVietnameseDiacriticsSubject() throws Exception {
            String complexVietnamese = "Thử nghiệm gửi thư có dấu tiếng Việt: "
                    + "Cộng hòa Xã hội Chủ nghĩa Việt Nam - Độc lập, Tự do, Hạnh phúc! "
                    + "ă ắ ằ ẳ ẵ ặ â ấ ầ ổ ỗ ộ ê ế ề ể ễ ệ ô ố ồ ổ ỗ ộ ơ ớ ờ ở ỡ ợ ư ứng hướng thưởng.";

            MimeMessage msg = EmailMessageBuilder.createMimeMessage(
                    dummySession,
                    "sender@example.com",
                    "receiver@example.com",
                    complexVietnamese,
                    "<p>Nội dung tiếng Việt</p>",
                    Collections.emptyList()
            );

            assertEquals(complexVietnamese, msg.getSubject(),
                    "Tiêu đề tiếng Việt đọc lại từ MimeMessage phải bảo toàn 100% không bị méo font");
        }

        @Test
        @DisplayName("TC-EDGE-12: Tiêu đề và nội dung chứa Emoji 4-byte UTF-8 (Surrogate Pairs)")
        void testEmojisInSubjectAndBody() throws Exception {
            String subjectWithEmojis = "🚀 [Thông báo khẩn] Hệ thống bảo mật email 📧 - Hoàn tất 100% 🎉 🔒 ⚡ 🌟 🔥!";
            String bodyWithEmojis = "<div style='font-family:sans-serif;'>"
                    + "<h2>Chúc mừng! 🎉</h2>"
                    + "<p>Email đã được gửi thành công qua giao thức SMTP an toàn 🚀.</p>"
                    + "<p>Bảo mật: ✅ TLSv1.3 | Mã xác thực: 🔑 #8888 | Đánh giá: ⭐⭐⭐⭐⭐</p>"
                    + "</div>";

            MimeMessage msg = EmailMessageBuilder.createMimeMessage(
                    dummySession,
                    "bot@system.org",
                    "admin@system.org",
                    subjectWithEmojis,
                    bodyWithEmojis,
                    Collections.emptyList()
            );

            assertEquals(subjectWithEmojis, msg.getSubject(), "Emoji trong tiêu đề phải được bảo toàn nguyên vẹn");

            Multipart mp = (Multipart) msg.getContent();
            BodyPart textPart = mp.getBodyPart(0);
            assertTrue(textPart.getContentType().toLowerCase().contains("text/html"));
            assertTrue(textPart.getContentType().toLowerCase().contains("charset=utf-8"));
            assertEquals(bodyWithEmojis, textPart.getContent().toString(), "Emoji trong nội dung HTML phải được giữ nguyên");
        }

        @Test
        @DisplayName("TC-EDGE-13: Hỗ trợ văn bản đa ngôn ngữ (Nhật, Trung, Hàn, Nga, Ả Rập, Pháp, Đức)")
        void testMultilingualSubjectAndBody() throws Exception {
            String multilingualSubject = "Global Notification: "
                    + "Xin chào (VI) | こんにちは (JA) | 你好 (ZH) | "
                    + "안녕하세요 (KO) | Привет (RU) | مرحبا (AR) | "
                    + "Élève (FR) | Straße (DE)";

            String multilingualBody = "<ul>"
                    + "<li>Tiếng Việt: Chúc một ngày tốt lành!</li>"
                    + "<li>Japanese: 良い一日を！</li>"
                    + "<li>Chinese: 祝你有美好的一天！</li>"
                    + "<li>Russian: Хорошего дня!</li>"
                    + "<li>Arabic: أتمنى لك يوما سعيدا!</li>"
                    + "</ul>";

            MimeMessage msg = EmailMessageBuilder.createMimeMessage(
                    dummySession,
                    "multi@globe.org",
                    "user@globe.org",
                    multilingualSubject,
                    multilingualBody,
                    Collections.emptyList()
            );

            assertEquals(multilingualSubject, msg.getSubject());
            Multipart mp = (Multipart) msg.getContent();
            assertEquals(multilingualBody, mp.getBodyPart(0).getContent().toString());
        }

        @Test
        @DisplayName("TC-EDGE-14: Tiêu đề chứa ký tự đặc biệt, dấu nháy, ngoặc nhọn, thực thể và toán tử an toàn")
        void testSpecialCharactersAndPunctuationInSubject() throws Exception {
            String specialSubject = "Re: [TICKET #4096] \"ALERT\": <Database> & 'Report' (Status: 100% OK, P < 0.05) $500 + €200 = ~700!";

            MimeMessage msg = EmailMessageBuilder.createMimeMessage(
                    dummySession,
                    "ticket@support.com",
                    "dev@support.com",
                    specialSubject,
                    "<p>Details</p>",
                    Collections.emptyList()
            );

            assertEquals(specialSubject, msg.getSubject());
        }

        @Test
        @DisplayName("TC-EDGE-15: Tiêu đề cực dài (>500 ký tự) kích hoạt RFC 2047 Header Folding và Unfolding chính xác")
        void testExtremelyLongSubjectHeaderFolding() throws Exception {
            StringBuilder sb = new StringBuilder();
            sb.append("BÁO CÁO TỔNG KẾT ĐẶC BIỆT NĂM 2026: ");
            for (int i = 1; i <= 25; i++) {
                sb.append("Mục ").append(i).append(": Đảm bảo an toàn mạng và giao thức truyền thông; ");
            }
            String longSubject = sb.toString();
            assertTrue(longSubject.length() > 500, "Độ dài tiêu đề phải vượt quá 500 ký tự");

            MimeMessage msg = EmailMessageBuilder.createMimeMessage(
                    dummySession,
                    "sender@example.com",
                    "receiver@example.com",
                    longSubject,
                    "<p>Long subject test</p>",
                    Collections.emptyList()
            );

            // Đọc lại qua getSubject(): JavaMail phải tự động unfold header và trả về đúng 100%
            assertEquals(longSubject, msg.getSubject());

            // Kiểm tra raw header: phải chứa các đoạn bọc RFC 2047
            String[] rawHeaders = msg.getHeader("Subject");
            assertNotNull(rawHeaders);
            assertTrue(rawHeaders.length > 0);
            assertTrue(rawHeaders[0].contains("=?UTF-8?") || rawHeaders[0].contains("BÁO CÁO"));
        }

        @Test
        @DisplayName("TC-EDGE-16: Tiêu đề null tự fallback thành '(Không có chủ đề)' và tiêu đề rỗng được giữ nguyên")
        void testNullAndEmptySubject() throws Exception {
            MimeMessage nullSubMsg = EmailMessageBuilder.createMimeMessage(
                    dummySession,
                    "sender@example.com",
                    "receiver@example.com",
                    null,
                    "<p>No subject</p>",
                    Collections.emptyList()
            );
            assertEquals("(Không có chủ đề)", nullSubMsg.getSubject());

            MimeMessage emptySubMsg = EmailMessageBuilder.createMimeMessage(
                    dummySession,
                    "sender@example.com",
                    "receiver@example.com",
                    "",
                    "<p>Empty subject</p>",
                    Collections.emptyList()
            );
            assertEquals("", emptySubMsg.getSubject());
        }

        @Test
        @DisplayName("TC-EDGE-17: Nội dung email null tự fallback thành chuỗi rỗng không gây NullPointerException")
        void testNullBodyHandling() throws Exception {
            MimeMessage msg = EmailMessageBuilder.createMimeMessage(
                    dummySession,
                    "sender@example.com",
                    "receiver@example.com",
                    "Test Null Body",
                    null,
                    Collections.emptyList()
            );

            Multipart mp = (Multipart) msg.getContent();
            BodyPart textPart = mp.getBodyPart(0);
            assertEquals("", textPart.getContent().toString());
        }
    }

    @Nested
    @DisplayName("R3 - Edge Case 4: Mã hóa tên tệp tin đính kèm chuẩn RFC 2047")
    class AttachmentFilenameEncodingTests {

        @Test
        @DisplayName("TC-EDGE-18: Tên file tiếng Việt có khoảng trắng được mã hóa RFC 2047 và giải mã chính xác")
        void testVietnameseFilenameWithSpaces() throws Exception {
            File vietnameseFile = new File("attachments", "Báo Cáo Nghiệm Thu Dự Án 2026.txt");
            Files.writeString(vietnameseFile.toPath(), "Nội dung biên bản nghiệm thu.", StandardCharsets.UTF_8);
            vietnameseFile.deleteOnExit();

            try {
                File resolved = EmailMessageBuilder.resolveAttachment(vietnameseFile.getPath());
                MimeMessage message = EmailMessageBuilder.createMimeMessage(
                        dummySession,
                        "sender@example.com",
                        "receiver@example.com",
                        "Gửi kèm báo cáo",
                        "<p>Xem đính kèm</p>",
                        Collections.singletonList(resolved)
                );

                Multipart mp = (Multipart) message.getContent();
                BodyPart attachPart = mp.getBodyPart(1);
                String rawFileName = attachPart.getFileName();
                assertNotNull(rawFileName);

                // Tên file raw có thể là dạng RFC 2047: "=?UTF-8?B?...?="
                String decodedFileName = MimeUtility.decodeText(rawFileName);
                assertEquals("Báo Cáo Nghiệm Thu Dự Án 2026.txt", decodedFileName);
            } finally {
                if (vietnameseFile.exists()) {
                    vietnameseFile.delete();
                }
            }
        }

        @Test
        @DisplayName("TC-EDGE-19: Tên file chứa ký tự đặc biệt hợp lệ (ngoặc vuông, tròn, gạch ngang, dấu chấm)")
        void testFilenameWithAllowedPunctuation() throws Exception {
            File complexNameFile = new File("attachments", "Tài-Liệu_[Bản Gốc] (v1.0.2).txt");
            Files.writeString(complexNameFile.toPath(), "Dữ liệu đặc biệt", StandardCharsets.UTF_8);
            complexNameFile.deleteOnExit();

            try {
                File resolved = EmailMessageBuilder.resolveAttachment(complexNameFile.getPath());
                MimeMessage message = EmailMessageBuilder.createMimeMessage(
                        dummySession,
                        "sender@example.com",
                        "receiver@example.com",
                        "File tên phức tạp",
                        "<p>Test</p>",
                        Collections.singletonList(resolved)
                );

                Multipart mp = (Multipart) message.getContent();
                BodyPart attachPart = mp.getBodyPart(1);
                String decodedName = MimeUtility.decodeText(attachPart.getFileName());
                assertEquals("Tài-Liệu_[Bản Gốc] (v1.0.2).txt", decodedName);
            } finally {
                if (complexNameFile.exists()) {
                    complexNameFile.delete();
                }
            }
        }

        @Test
        @DisplayName("TC-EDGE-20: Tên file ASCII tiêu chuẩn giữ nguyên dạng plain text không bị encode thừa")
        void testStandardAsciiFilenamePreserved() throws Exception {
            File sampleFile = new File("attachments/sample.txt");
            MimeMessage message = EmailMessageBuilder.createMimeMessage(
                    dummySession,
                    "sender@example.com",
                    "receiver@example.com",
                    "ASCII Attachment",
                    "<p>Test</p>",
                    Collections.singletonList(sampleFile)
            );

            Multipart mp = (Multipart) message.getContent();
            BodyPart attachPart = mp.getBodyPart(1);
            assertEquals("sample.txt", attachPart.getFileName(), "Tên file ASCII không cần bọc RFC 2047");
        }
    }

    @Nested
    @DisplayName("R3 - Edge Case 5: Kịch bản kết hợp đa yếu tố biên trong một bức thư (Stress & Synergy)")
    class ComplexCombinedEdgeCasesTests {

        @Test
        @DisplayName("TC-EDGE-21: Email kết hợp Emoji + Tiếng Việt dài + File 0-byte tiếng Việt + File chuẩn")
        void testAllEdgeCasesCombinedInSingleMessage() throws Exception {
            File zeroByteVietnamese = new File("attachments", "bảng_kê_trống_0byte.dat");
            if (zeroByteVietnamese.exists()) {
                zeroByteVietnamese.delete();
            }
            assertTrue(zeroByteVietnamese.createNewFile());
            zeroByteVietnamese.deleteOnExit();

            File normalFile = new File("attachments/sample.txt");

            try {
                String combinedSubject = "🚀 [LTM-2026] Thử nghiệm tổng lực: Gửi email đa biên 📧 & Bảo mật TLS 🔒 ✨!";
                String combinedBody = "<html><body>"
                        + "<h1>Kính gửi Ban Giám khảo,</h1>"
                        + "<p>Email này kiểm tra đồng thời tất cả các kịch bản biên:</p>"
                        + "<ol>"
                        + "<li>Tiêu đề chứa tiếng Việt có dấu và Emoji 🚀📧.</li>"
                        + "<li>Nội dung HTML UTF-8 kèm ký hiệu đặc biệt: &lt;&amp;&gt; \" '</li>"
                        + "<li>File đính kèm 1: 0-byte có tên tiếng Việt: <code>bảng_kê_trống_0byte.dat</code></li>"
                        + "<li>File đính kèm 2: File văn bản chuẩn 250 bytes: <code>sample.txt</code></li>"
                        + "</ol>"
                        + "<p>Trạng thái: <b>Thành công 100% ⭐⭐⭐⭐⭐</b></p>"
                        + "</body></html>";

                List<File> attachments = Arrays.asList(zeroByteVietnamese, normalFile);

                MimeMessage message = EmailMessageBuilder.createMimeMessage(
                        dummySession,
                        "sender.security@example.com",
                        "receiver.evaluator@example.com",
                        combinedSubject,
                        combinedBody,
                        attachments
                );

                assertNotNull(message);
                assertEquals(combinedSubject, message.getSubject());

                Multipart mp = (Multipart) message.getContent();
                assertEquals(3, mp.getCount(), "1 Body HTML + 2 Files đính kèm");

                // Part 0: HTML Body
                BodyPart bodyPart = mp.getBodyPart(0);
                assertEquals(combinedBody, bodyPart.getContent().toString());

                // Part 1: 0-byte file tiếng Việt
                BodyPart part1 = mp.getBodyPart(1);
                assertEquals(Part.ATTACHMENT.toLowerCase(), part1.getDisposition().toLowerCase());
                assertEquals("bảng_kê_trống_0byte.dat", MimeUtility.decodeText(part1.getFileName()));
                try (java.io.InputStream is1 = part1.getInputStream()) {
                    assertEquals(0, is1.readAllBytes().length);
                }

                // Part 2: Normal file
                BodyPart part2 = mp.getBodyPart(2);
                assertEquals(Part.ATTACHMENT.toLowerCase(), part2.getDisposition().toLowerCase());
                assertEquals("sample.txt", part2.getFileName());
                try (java.io.InputStream is2 = part2.getInputStream()) {
                    assertTrue(is2.readAllBytes().length > 0);
                }

                // Đóng gói và ghi ra dòng byte kiểm tra tính hợp lệ của MIME message toàn vẹn
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                message.writeTo(baos);
                byte[] mimeBytes = baos.toByteArray();
                assertTrue(mimeBytes.length > 500, "MIME message đóng gói phải có kích thước đầy đủ header và boundaries");
            } finally {
                if (zeroByteVietnamese.exists()) {
                    zeroByteVietnamese.delete();
                }
            }
        }
    }
}
