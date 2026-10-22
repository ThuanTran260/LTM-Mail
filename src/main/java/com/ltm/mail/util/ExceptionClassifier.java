package com.ltm.mail.util;

import com.sun.mail.smtp.SMTPAddressFailedException;
import com.sun.mail.smtp.SMTPSendFailedException;
import com.sun.mail.smtp.SMTPSenderFailedException;

import javax.mail.AuthenticationFailedException;
import javax.mail.MessagingException;
import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Phân loại ngoại lệ trong giao thức SMTP theo chuẩn RFC 5321 / RFC 5322 & Slide 30 (Bảng 7):
 * - REL-01: Phân loại SMTPAddressFailedException dựa trên mã phản hồi getReturnCode() (4xx -> retryable, 5xx -> permanent)
 * - REL-02: Bổ sung xử lý SMTPSenderFailedException dựa trên mã phản hồi getReturnCode() (4xx vs 5xx)
 * - REL-03: Duyệt lặp (Iterative Queue) thu thập chuỗi ngoại lệ qua getNextException() và getCause() chống StackOverflowError
 * - REL-04: Đảm bảo thứ tự ưu tiên: Lỗi vĩnh viễn (5xx, AuthenticationFailedException) đứng trước kiểm tra lỗi tạm thời,
 *           đồng thời ưu tiên mã trạng thái giao thức trên thông điệp văn bản (RFC 5321 §4.2).
 */
public class ExceptionClassifier {

    // Danh sách mã trạng thái RFC 5321 / RFC 7504 / RFC 4954 / RFC 5336 / RFC 3207
    private static final Set<Integer> RFC_PERMANENT_5XX_CODES = Set.of(
            500, 501, 502, 503, 504, 520, 521, 530, 534, 535, 538,
            541, 550, 551, 552, 553, 554, 555, 556
    );

    private static final Set<Integer> RFC_TRANSIENT_4XX_CODES = Set.of(
            421, 432, 450, 451, 452, 453, 454, 455
    );

    // Negative lookahead loại trừ các đơn vị đo lường thời gian, dung lượng, và số lượng tư vấn
    // BẮT BUỘC có ranh giới từ \b ở cuối nhóm đơn vị để không triệt tiêu mã hợp lệ (như 550 Service, 550 security, 550 secondary)
    private static final String ADVISORY_UNIT_LOOKAHEAD =
            "(?![\\s-]*(?:ms|millis(?:econds?)?|seconds?|sec|s|minutes?|min|m|hours?|hr|h|bytes|kb|mb|gb|items?|entries)\\b)";

    // Regex nhận diện mã phản hồi chính đứng đầu dòng phản hồi hoặc sau tiền tố phản hồi giao thức chuẩn per RFC 5321 §4.2
    private static final Pattern PRIMARY_REPLY_CODE_PATTERN = Pattern.compile(
            "(?:^|[\\r\\n]|(?:smtp|server|reply|response|code|status):\\s*)"
            + "((?:50[0-4]|52[01]|53[0458]|541|55[0-6]|421|432|45[0-5]))"
            + ADVISORY_UNIT_LOOKAHEAD
            + "(?:[ -]|$)",
            Pattern.CASE_INSENSITIVE
    );

    // Regex nhận diện mã trạng thái nâng cao RFC 3463 (5.X.X vĩnh viễn vs 4.X.X tạm thời)
    private static final Pattern PERMANENT_ENHANCED_PATTERN = Pattern.compile(
            "\\b5\\.\\d+\\.\\d+\\b",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern TRANSIENT_ENHANCED_PATTERN = Pattern.compile(
            "\\b4\\.\\d+\\.\\d+\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Regex nhận diện mã trạng thái 5xx RFC 5321 độc lập có ranh giới từ, loại trừ đơn vị đo lường và cổng mạng (như 587)
    private static final Pattern PERMANENT_CODE_PATTERN = Pattern.compile(
            "\\b(?:50[0-4]|52[01]|53[0458]|541|55[0-6])"
            + ADVISORY_UNIT_LOOKAHEAD + "\\b|\\b5\\.\\d+\\.\\d+\\b",
            Pattern.CASE_INSENSITIVE
    );

    // Regex nhận diện mã trạng thái 4xx RFC 5321 độc lập có ranh giới từ, loại trừ đơn vị đo lường và cổng mạng (như 465)
    private static final Pattern TRANSIENT_CODE_PATTERN = Pattern.compile(
            "\\b(?:421|432|45[0-5])"
            + ADVISORY_UNIT_LOOKAHEAD + "\\b|\\b4\\.\\d+\\.\\d+\\b",
            Pattern.CASE_INSENSITIVE
    );

    public static boolean isRetryable(Exception ex) {
        if (ex == null) {
            return false;
        }

        // REL-03: Duyệt lặp (Iterative Queue) thu thập toàn bộ chuỗi ngoại lệ chống StackOverflowError
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        collectExceptions(ex, chain, visited);

        // REL-04 PASS 1: Kiểm tra lỗi VĨNH VIỄN (Non-retryable) trên toàn bộ chuỗi ngoại lệ trước tiên
        // Nếu bất kỳ ngoại lệ nào trong chuỗi là lỗi vĩnh viễn, lập tức từ chối retry.
        for (Throwable t : chain) {
            // 1.1 Kiểm tra kiểu ngoại lệ xác thực / cấu hình vĩnh viễn
            if (t instanceof AuthenticationFailedException) {
                LoggerUtil.warn("[Lỗi vĩnh viễn] Xác thực thất bại (Sai Username/App Password hoặc mã 535). Không thử lại.");
                return false;
            }

            if (t instanceof UnknownHostException) {
                LoggerUtil.warn("[Lỗi vĩnh viễn] Không thể phân giải tên miền máy chủ SMTP (DNS/Host sai). Không thử lại.");
                return false;
            }

            if (t instanceof SSLHandshakeException) {
                LoggerUtil.warn("[Lỗi vĩnh viễn] Lỗi bắt tay SSL/TLS (Phiên bản TLS hoặc chứng chỉ không hợp lệ). Không thử lại.");
                return false;
            }

            // 1.2 REL-01 / REL-02: Kiểm tra mã trạng thái trả về từ các lớp chuyên biệt của JavaMail SMTP
            int code = extractSmtpReturnCode(t);
            if (code >= 500 && code < 600) {
                LoggerUtil.warn("[Lỗi vĩnh viễn 5xx] JavaMail SMTP trả về mã vĩnh viễn " + code + ". Không thử lại.");
                return false;
            }
            if (code >= 400 && code < 500) {
                // ĐẶC QUYỀN MIỄN TRỪ VĂN BẢN (Permanent Text Exemption):
                // Ngoại lệ mang mã 4xx chuẩn xác thực RFC 5321 là lỗi tạm thời.
                // TUYỆT ĐỐI KHÔNG quét mẫu 5xx trên thông điệp văn bản của ngoại lệ này (vd: "try again in 500 seconds").
                continue;
            }

            // 1.3 Kiểm tra thông điệp lỗi chứa mã hoặc cụm từ lỗi vĩnh viễn (5xx)
            if (hasPermanentIndicator(t.getMessage())) {
                LoggerUtil.warn("[Lỗi vĩnh viễn 5xx] Phát hiện mã 5xx trong thông điệp lỗi: " + t.getMessage() + ". Không thử lại.");
                return false;
            }
        }

        // PASS 2: Kiểm tra lỗi TẠM THỜI (Retryable) trên toàn bộ chuỗi ngoại lệ
        for (Throwable t : chain) {
            // 2.1 REL-01 / REL-02: Kiểm tra mã 4xx từ SMTPSendFailedException, SMTPAddressFailedException, SMTPSenderFailedException
            int code = extractSmtpReturnCode(t);
            if (code >= 400 && code < 500) {
                LoggerUtil.warn("[Lỗi tạm thời 4xx] JavaMail SMTP trả về mã " + code + " (Mailbox busy / deferral). Đủ điều kiện thử lại.");
                return true;
            }

            // 2.2 Ngoại lệ mạng / Socket
            if (t instanceof SocketTimeoutException) {
                LoggerUtil.warn("[Lỗi tạm thời] Hết thời gian chờ kết nối/đọc (Socket Timeout). Đủ điều kiện thử lại.");
                return true;
            }

            if (t instanceof ConnectException || t instanceof SocketException) {
                LoggerUtil.warn("[Lỗi tạm thời] Gián đoạn kết nối mạng (Socket/Connect error: " + t.getClass().getSimpleName() + "). Đủ điều kiện thử lại.");
                return true;
            }

            // 2.3 Kiểm tra thông điệp lỗi chứa mã 4xx hoặc cụm từ chỉ trạng thái tạm thời
            if (hasTransientIndicator(t.getMessage())) {
                LoggerUtil.warn("[Lỗi tạm thời] Phát hiện mã 4xx hoặc cảnh báo timeout trong thông điệp: " + t.getMessage() + ". Đủ điều kiện thử lại.");
                return true;
            }
        }

        // Mặc định: coi lỗi không xác định là không thể thử lại để tránh lặp vô hạn
        return false;
    }

    /**
     * REL-03: Duyệt lặp (Iterative Queue) thu thập toàn bộ chuỗi ngoại lệ qua
     * MessagingException.getNextException() và getCause() với IdentityHashMap bảo vệ chống chu trình.
     * Hoàn toàn triệt tiêu StackOverflowError trên các chuỗi ngoại lệ lồng sâu (10.000+ cấp).
     */
    private static void collectExceptions(Throwable root, List<Throwable> chain, Set<Throwable> visited) {
        if (root == null || !visited.add(root)) {
            return;
        }

        Queue<Throwable> queue = new ArrayDeque<>();
        queue.add(root);

        while (!queue.isEmpty()) {
            Throwable current = queue.poll();
            chain.add(current);

            // 1. Duyệt chuỗi ngoại lệ đặc thù của JavaMail (getNextException)
            if (current instanceof MessagingException) {
                MessagingException me = (MessagingException) current;
                Exception next = me.getNextException();
                if (next != null && visited.add(next)) {
                    queue.add(next);
                }
            }

            // 2. Duyệt chuỗi nguyên nhân chuẩn của Java Throwable (getCause)
            Throwable cause = current.getCause();
            if (cause != null && cause != current && visited.add(cause)) {
                queue.add(cause);
            }
        }
    }

    /**
     * Trích xuất mã phản hồi SMTP từ các lớp ngoại lệ chuyên biệt của JavaMail.
     * Trả về -1 nếu ngoại lệ không mang mã trạng thái SMTP.
     */
    private static int extractSmtpReturnCode(Throwable t) {
        if (t instanceof SMTPSendFailedException) {
            return ((SMTPSendFailedException) t).getReturnCode();
        }
        if (t instanceof SMTPAddressFailedException) {
            return ((SMTPAddressFailedException) t).getReturnCode();
        }
        if (t instanceof SMTPSenderFailedException) {
            return ((SMTPSenderFailedException) t).getReturnCode();
        }
        return -1;
    }

    /**
     * Trích xuất mã trạng thái SMTP 3 chữ số đứng đầu dòng phản hồi hoặc sau tiền tố phản hồi giao thức chuẩn.
     * Trả về null nếu không tìm thấy mã chuẩn RFC 5321.
     */
    private static Integer extractPrimaryReplyCode(String msg) {
        if (msg == null || msg.isEmpty()) {
            return null;
        }
        Matcher m = PRIMARY_REPLY_CODE_PATTERN.matcher(msg);
        if (m.find()) {
            try {
                int code = Integer.parseInt(m.group(1));
                if (RFC_PERMANENT_5XX_CODES.contains(code) || RFC_TRANSIENT_4XX_CODES.contains(code)) {
                    return code;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static boolean hasPermanentIndicator(String msg) {
        if (msg == null || msg.isEmpty()) {
            return false;
        }

        // 1. Kiểm tra mã phản hồi SMTP chính (Primary Reply Code) per RFC 5321 §4.2
        Integer replyCode = extractPrimaryReplyCode(msg);
        if (replyCode != null) {
            if (replyCode >= 500 && replyCode < 600) {
                return true;
            }
            if (replyCode >= 400 && replyCode < 500) {
                // Mã 4xx đứng đầu có thẩm quyền giao thức cao nhất: miễn trừ thông điệp khỏi kiểm tra 5xx
                return false;
            }
        }

        // 2. Kiểm tra mã Enhanced Status Code RFC 3463 (5.X.X)
        if (PERMANENT_ENHANCED_PATTERN.matcher(msg).find()) {
            return true;
        }

        // 3. Kiểm tra mã 5xx độc lập có ranh giới từ (chỉ khi không có dấu hiệu lỗi tạm thời)
        // Nếu Level 1 và Level 2 vắng mặt, và Level 3 khớp mẫu 5xx độc lập,
        // kiểm tra xem hasTransientIndicator(msg) có đúng không.
        // Nếu có cụm từ/mã tạm thời rõ ràng, số 5xx độc lập là thông số tư vấn và KHÔNG kích hoạt lỗi vĩnh viễn.
        if (PERMANENT_CODE_PATTERN.matcher(msg).find()
                && !hasTransientIndicator(msg)) {
            return true;
        }

        // 4. Kiểm tra các cụm từ ngữ nghĩa lỗi xác thực hoặc tài khoản vĩnh viễn
        String lower = msg.toLowerCase();
        return lower.contains("authentication failed")
                || lower.contains("authenticationfailedexception")
                || lower.contains("bad credentials")
                || lower.contains("user unknown")
                || lower.contains("relay access denied")
                || lower.contains("relaying denied");
    }

    private static boolean hasTransientIndicator(String msg) {
        if (msg == null || msg.isEmpty()) {
            return false;
        }

        // 1. Kiểm tra mã phản hồi SMTP chính (Primary Reply Code) per RFC 5321 §4.2
        Integer replyCode = extractPrimaryReplyCode(msg);
        if (replyCode != null && replyCode >= 400 && replyCode < 500) {
            return true;
        }

        // 2. Kiểm tra mã Enhanced Status Code RFC 3463 (4.X.X)
        if (TRANSIENT_ENHANCED_PATTERN.matcher(msg).find()) {
            return true;
        }

        // 3. Kiểm tra mã 4xx độc lập có ranh giới từ RFC 5321 / RFC 3207 / RFC 4954
        if (TRANSIENT_CODE_PATTERN.matcher(msg).find()) {
            return true;
        }

        // 4. Kiểm tra các cụm từ ngữ nghĩa trạng thái bận hoặc timeout tạm thời
        String lower = msg.toLowerCase();
        return lower.contains("mailbox busy")
                || lower.contains("temporarily busy")
                || lower.contains("temporarily deferred")
                || lower.contains("temporary error")
                || lower.contains("try again later")
                || lower.contains("timed out")
                || lower.contains("timeout")
                || lower.contains("connection reset")
                || lower.contains("connection refused");
    }
}
