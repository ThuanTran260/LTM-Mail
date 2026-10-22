package com.ltm.mail.client;

import com.ltm.mail.config.SmtpConfig;
import com.ltm.mail.util.LoggerUtil;

import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeUtility;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Xây dựng thông điệp MIME multipart/mixed theo đúng yêu cầu Slide 30 (Phần 4):
 * - Part 1: Nội dung Email (text/HTML, UTF-8)
 * - Part 2+: File đính kèm (MIME multipart/mixed, Base64 encode tự động)
 */
public class EmailMessageBuilder {

    public static MimeMessage buildMessage(Session session, SmtpConfig config) throws Exception {
        List<File> attachments = new ArrayList<>();
        if (config.getAttachmentPaths() != null) {
            for (String path : config.getAttachmentPaths()) {
                if (path != null && !path.trim().isEmpty()) {
                    File file = new File(path.trim());
                    if (file.exists() && file.isFile()) {
                        attachments.add(file);
                        LoggerUtil.info("Đã tìm thấy file đính kèm: " + file.getAbsolutePath() + " (" + file.length() + " bytes)");
                    } else {
                        LoggerUtil.warn("Không tìm thấy file đính kèm hoặc đường dẫn không hợp lệ: " + path);
                    }
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
        MimeMessage message = new MimeMessage(session);

        // 1. Địa chỉ người gửi & người nhận
        message.setFrom(new InternetAddress(from));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));

        // 2. Tiêu đề email (hỗ trợ Tiếng Việt UTF-8)
        message.setSubject(subject != null ? subject : "(Không có chủ đề)", "UTF-8");

        // 3. Tạo MIME multipart/mixed
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
                    MimeBodyPart attachPart = new MimeBodyPart();
                    attachPart.attachFile(file);
                    // Đảm bảo tên file đính kèm có dấu tiếng Việt hiển thị chính xác
                    attachPart.setFileName(MimeUtility.encodeText(file.getName(), "UTF-8", null));
                    multipart.addBodyPart(attachPart);
                    LoggerUtil.info("Đã đính kèm file: " + file.getName() + " vào MimeMultipart.");
                }
            }
        }

        // Đặt nội dung multipart vào message
        message.setContent(multipart);
        message.saveChanges();

        return message;
    }
}
