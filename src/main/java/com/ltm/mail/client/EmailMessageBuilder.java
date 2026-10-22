package com.ltm.mail.client;

import com.ltm.mail.config.SmtpConfig;
import com.ltm.mail.util.LoggerUtil;

import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeUtility;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * Xây dựng thông điệp MIME multipart/mixed theo đúng yêu cầu Slide 30 (Phần 4)
 * và gia cố an toàn bảo mật (SEC-04: Chống CRLF Injection, SEC-05: Chống Path Traversal).
 * - Part 1: Nội dung Email (text/HTML, UTF-8)
 * - Part 2+: File đính kèm (MIME multipart/mixed, Base64 encode tự động)
 */
public class EmailMessageBuilder {

    public static final long MAX_ATTACHMENT_SIZE = 25 * 1024 * 1024L; // 25 MB giới hạn chuẩn

    private static final Set<String> FORBIDDEN_FILE_NAMES = Set.of(
            "appsettings.json",
            "appsettings.example.json",
            ".env",
            ".env.local",
            "smtp-client.log"
    );

    private static final List<String> FORBIDDEN_EXTENSIONS = Arrays.asList(
            ".key",
            ".pem",
            ".crt",
            ".pfx",
            ".p12"
    );

    /**
     * SEC-04: Kiểm tra và ngăn chặn tấn công CRLF / Header Injection (CWE-93).
     * Ném ngoại lệ IllegalArgumentException nếu phát hiện ký tự \r hoặc \n.
     */
    public static void validateHeaderValue(String name, String value) {
        if (value != null && (value.indexOf('\r') != -1 || value.indexOf('\n') != -1)) {
            throw new IllegalArgumentException("Phát hiện ký tự xuống dòng (CRLF Injection) trong trường: " + name);
        }
    }

    /**
     * SEC-04: Khử ký tự điều khiển xuống dòng (\r, \n) để làm sạch giá trị header hoặc tên file.
     */
    public static String sanitizeHeader(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\r", "").replace("\n", "").trim();
    }

    /**
     * SEC-05: Kiểm tra và ngăn chặn lỗ hổng Path Traversal (CWE-22) khi xử lý đường dẫn file đính kèm.
     * Xác thực canonical path, ranh giới thư mục làm việc, danh sách đen file nhạy cảm và kích thước.
     */
    public static File resolveAttachment(String path) throws Exception {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Đường dẫn file đính kèm không được rỗng!");
        }

        String trimmedPath = path.trim();

        // 1. Kiểm tra chuỗi duyệt thư mục (Path traversal sequences)
        if (trimmedPath.contains("..")) {
            throw new SecurityException("Cảnh báo Path Traversal: Phát hiện chuỗi duyệt thư mục ('..'): " + path);
        }

        File file = new File(trimmedPath);

        // 2. Kiểm tra file tồn tại và là file thông thường
        if (!file.exists()) {
            throw new IllegalArgumentException("File đính kèm không tồn tại: " + path);
        }
        if (!file.isFile()) {
            throw new IllegalArgumentException("Đường dẫn không phải là file thông thường: " + path);
        }
        if (!file.canRead()) {
            throw new SecurityException("Không có quyền đọc file đính kèm: " + path);
        }

        // 3. Kiểm tra ranh giới thư mục dự án (Canonical Path Sandbox Boundary Check)
        File canonicalFile = file.getCanonicalFile();
        Path baseDirPath = new File(".").getCanonicalFile().toPath();
        if (!canonicalFile.toPath().startsWith(baseDirPath)) {
            throw new SecurityException("Cảnh báo Path Traversal: Tệp tin nằm ngoài phạm vi thư mục cho phép: " + path);
        }

        // 4. Kiểm tra danh sách file nhạy cảm cần bảo vệ (Credentials, Secrets, Keys)
        if (isSensitiveFile(canonicalFile)) {
            throw new SecurityException("Từ chối truy cập: Không được phép đính kèm tệp tin cấu hình hoặc thông tin nhạy cảm: " + canonicalFile.getName());
        }

        // 5. Kiểm tra giới hạn kích thước file (DoS / OutOfMemoryError defense)
        if (canonicalFile.length() > MAX_ATTACHMENT_SIZE) {
            throw new IllegalArgumentException("Tệp tin đính kèm vượt quá dung lượng tối đa 25MB: " + canonicalFile.length() + " bytes");
        }

        if (canonicalFile.length() == 0) {
            LoggerUtil.warn("Cảnh báo: File đính kèm có kích thước 0 bytes (empty file): " + canonicalFile.getName());
        }

        // 6. REL-06: Đảm bảo file descriptor có thể mở được và đóng an toàn ngay trong try-with-resources
        try (java.io.FileInputStream fis = new java.io.FileInputStream(canonicalFile)) {
            // Xác nhận quyền đọc và tài nguyên file descriptor được quản lý đóng an toàn
        } catch (java.io.IOException ioe) {
            throw new SecurityException("Không thể đọc tệp tin đính kèm: " + canonicalFile.getAbsolutePath(), ioe);
        }

        return canonicalFile;
    }

    /**
     * Kiểm tra xem tệp tin có thuộc danh sách nhạy cảm hay không.
     */
    public static boolean isSensitiveFile(File file) {
        if (file == null) {
            return false;
        }
        String name = file.getName().toLowerCase();
        if (FORBIDDEN_FILE_NAMES.contains(name)) {
            return true;
        }
        for (String ext : FORBIDDEN_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        String normalizedPath = file.getAbsolutePath().replace('\\', '/').toLowerCase();
        if (name.contains("id_rsa") || name.contains("id_ed25519") || normalizedPath.contains("/.git/")) {
            return true;
        }
        return false;
    }

    public static MimeMessage buildMessage(Session session, SmtpConfig config) throws Exception {
        List<File> attachments = new ArrayList<>();
        if (config.getAttachmentPaths() != null) {
            for (String path : config.getAttachmentPaths()) {
                if (path != null && !path.trim().isEmpty()) {
                    File file = resolveAttachment(path);
                    attachments.add(file);
                    LoggerUtil.info("Đã tìm thấy file đính kèm hợp lệ: " + file.getAbsolutePath() + " (" + file.length() + " bytes)");
                }
            }
        }

        return createMimeMessage(
                session,
                config.getFrom(),
                config.getTo(),
                config.getSubject(),
                config.getBody(),
                attachments
        );
    }

    public static MimeMessage createMimeMessage(
            Session session,
            String from,
            String to,
            String subject,
            String bodyHtml,
            List<File> attachments
    ) throws Exception {
        // SEC-04: Kiểm tra và ngăn chặn CRLF Injection trong các trường Header
        validateHeaderValue("From", from);
        validateHeaderValue("To", to);
        validateHeaderValue("Subject", subject);

        MimeMessage message = new MimeMessage(session);

        // 1. Địa chỉ người gửi & người nhận
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));

        // 2. Tiêu đề email (hỗ trợ Tiếng Việt UTF-8)
        message.setSubject(subject != null ? subject : "(Không có chủ đề)", "UTF-8");

        // 3. RFC 5322: Thiết lập thời điểm gửi thư (Sent Date)
        message.setSentDate(new Date());

        // 4. Tạo MIME multipart/mixed
        Multipart multipart = new MimeMultipart("mixed");

        // Part 1: Nội dung text/HTML
        MimeBodyPart textPart = new MimeBodyPart();
        String content = (bodyHtml != null) ? bodyHtml : "";
        textPart.setContent(content, "text/html; charset=UTF-8");
        multipart.addBodyPart(textPart);

        // Part 2+: File đính kèm (Base64 encode tự động)
        if (attachments != null) {
            for (File file : attachments) {
                if (file != null && file.exists()) {
                    if (isSensitiveFile(file)) {
                        throw new SecurityException("Từ chối truy cập: Không được phép đính kèm tệp tin nhạy cảm: " + file.getName());
                    }
                    MimeBodyPart attachPart = new MimeBodyPart();
                    attachPart.attachFile(file);
                    attachPart.setDisposition(Part.ATTACHMENT);

                    // Làm sạch tên file và mã hóa RFC 2047
                    String cleanFileName = sanitizeHeader(file.getName());
                    attachPart.setFileName(MimeUtility.encodeText(cleanFileName, "UTF-8", null));
                    multipart.addBodyPart(attachPart);
                    LoggerUtil.info("Đã đính kèm file: " + cleanFileName + " vào MimeMultipart.");
                }
            }
        }

        // Đặt nội dung multipart vào message
        message.setContent(multipart);
        message.saveChanges();

        return message;
    }
}
