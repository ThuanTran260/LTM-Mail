package com.ltm.mail.util;

import com.sun.mail.smtp.SMTPAddressFailedException;
import com.sun.mail.smtp.SMTPSendFailedException;

import javax.mail.AuthenticationFailedException;
import javax.mail.SendFailedException;
import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * Phân loại ngoại lệ trong giao thức SMTP (Slide 30 - Bảng 7):
 * - Lỗi 4xx (tạm thời / timeout / busy) -> Có thể Retry
 * - Lỗi 5xx (vĩnh viễn / auth fail / sai địa chỉ / sai host) -> Không Retry
 */
public class ExceptionClassifier {

    public static boolean isRetryable(Exception ex) {
        if (ex == null) {
            return false;
        }

        // 1. Kiểm tra các lỗi vĩnh viễn (Non-retryable)
        if (ex instanceof AuthenticationFailedException) {
            LoggerUtil.warn("[Lỗi vĩnh viễn] Xác thực thất bại (Sai Username/App Password hoặc mã 535). Không thử lại.");
            return false;
        }

        if (ex instanceof UnknownHostException) {
            LoggerUtil.warn("[Lỗi vĩnh viễn] Không thể phân giải tên miền máy chủ SMTP (DNS/Host sai). Không thử lại.");
            return false;
        }

        if (ex instanceof SSLHandshakeException) {
            LoggerUtil.warn("[Lỗi vĩnh viễn] Lỗi bắt tay SSL/TLS (Phiên bản TLS hoặc chứng chỉ không hợp lệ). Không thử lại.");
            return false;
        }

        if (ex instanceof SendFailedException) {
            // Kiểm tra mã lỗi lồng nhau nếu là SMTPSendFailedException
            if (ex instanceof SMTPSendFailedException) {
                SMTPSendFailedException smtpEx = (SMTPSendFailedException) ex;
                int returnCode = smtpEx.getReturnCode();
                if (returnCode >= 500 && returnCode < 600) {
                    LoggerUtil.warn("[Lỗi vĩnh viễn 5xx] Server trả về mã " + returnCode + ". Không thử lại.");
                    return false;
                }
                if (returnCode >= 400 && returnCode < 500) {
                    LoggerUtil.warn("[Lỗi tạm thời 4xx] Server trả về mã " + returnCode + ". Đủ điều kiện thử lại.");
                    return true;
                }
            }
            if (ex instanceof SMTPAddressFailedException) {
                LoggerUtil.warn("[Lỗi vĩnh viễn] Địa chỉ người nhận không hợp lệ (Mã 550). Không thử lại.");
                return false;
            }
        }

        // 2. Kiểm tra các lỗi tạm thời (Retryable)
        if (ex instanceof SocketTimeoutException) {
            LoggerUtil.warn("[Lỗi tạm thời] Hết thời gian chờ kết nối/đọc (Socket Timeout). Đủ điều kiện thử lại.");
            return true;
        }

        if (ex instanceof ConnectException || ex instanceof SocketException) {
            LoggerUtil.warn("[Lỗi tạm thời] Gián đoạn kết nối mạng (Socket/Connect error). Đủ điều kiện thử lại.");
            return true;
        }

        // 3. Kiểm tra thông điệp lỗi hoặc nguyên nhân gốc (Cause)
        String msg = (ex.getMessage() != null ? ex.getMessage() : "").toLowerCase();
        if (msg.contains("450") || msg.contains("421") || msg.contains("451") || msg.contains("452")
                || msg.contains("mailbox busy") || msg.contains("temporarily deferred")
                || msg.contains("timed out") || msg.contains("timeout")) {
            LoggerUtil.warn("[Lỗi tạm thời] Phát hiện mã 4xx hoặc cảnh báo timeout trong thông điệp. Đủ điều kiện thử lại.");
            return true;
        }

        if (msg.contains("535") || msg.contains("550") || msg.contains("501") || msg.contains("502") || msg.contains("503")) {
            LoggerUtil.warn("[Lỗi vĩnh viễn 5xx] Phát hiện mã 5xx trong thông điệp lỗi. Không thử lại.");
            return false;
        }

        Throwable cause = ex.getCause();
        if (cause instanceof Exception && cause != ex) {
            return isRetryable((Exception) cause);
        }

        // Mặc định: coi lỗi không xác định là không thể thử lại để tránh lặp vô hạn
        return false;
    }
}
