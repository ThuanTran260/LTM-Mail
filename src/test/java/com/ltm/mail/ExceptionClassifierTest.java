package com.ltm.mail;

import com.ltm.mail.util.ExceptionClassifier;
import com.sun.mail.smtp.SMTPAddressFailedException;
import com.sun.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.mail.AuthenticationFailedException;
import javax.mail.MessagingException;
import javax.mail.internet.InternetAddress;
import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExceptionClassifierTest {

    @Test
    @DisplayName("Lỗi xác thực 535 không được phép retry")
    void testAuthenticationFailedException() {
        AuthenticationFailedException ex = new AuthenticationFailedException("535 5.7.8 Bad credentials");
        assertFalse(ExceptionClassifier.isRetryable(ex));
    }

    @Test
    @DisplayName("Lỗi sai Host/DNS không được phép retry")
    void testUnknownHostException() {
        UnknownHostException ex = new UnknownHostException("smtp.invalid-domain-xyz.com");
        assertFalse(ExceptionClassifier.isRetryable(ex));
    }

    @Test
    @DisplayName("Lỗi SSLHandshake không được phép retry")
    void testSSLHandshakeException() {
        SSLHandshakeException ex = new SSLHandshakeException("No appropriate protocol");
        assertFalse(ExceptionClassifier.isRetryable(ex));
    }

    @Test
    @DisplayName("Lỗi SocketTimeout được phép retry")
    void testSocketTimeoutException() {
        SocketTimeoutException ex = new SocketTimeoutException("Read timed out");
        assertTrue(ExceptionClassifier.isRetryable(ex));
    }

    @Test
    @DisplayName("Lỗi ConnectException được phép retry")
    void testConnectException() {
        ConnectException ex = new ConnectException("Connection refused: connect");
        assertTrue(ExceptionClassifier.isRetryable(ex));
    }

    @Test
    @DisplayName("Lỗi 4xx (450 mailbox busy) trong thông điệp được phép retry")
    void testMessage4xxException() {
        MessagingException ex = new MessagingException("450 4.2.1 Mailbox busy, try again later");
        assertTrue(ExceptionClassifier.isRetryable(ex));
    }

    @Test
    @DisplayName("Lỗi 5xx (550 user not found) không được phép retry")
    void testMessage5xxException() {
        MessagingException ex = new MessagingException("550 5.1.1 User unknown");
        assertFalse(ExceptionClassifier.isRetryable(ex));
    }
}
