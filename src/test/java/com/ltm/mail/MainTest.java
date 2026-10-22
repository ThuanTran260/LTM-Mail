package com.ltm.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.mail.AuthenticationFailedException;
import javax.net.ssl.SSLHandshakeException;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verification test suite for Main CLI entrypoint and troubleshooting guidance.
 */
public class MainTest {

    @Test
    @DisplayName("CLI Execution: Main with missing config exits with code 1 and outputs troubleshooting guide")
    void testMainProcessExecutionWithMissingConfig() throws Exception {
        String javaHome = System.getProperty("java.home");
        String javaBin = javaHome + "/bin/java";
        String classPath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp",
                classPath,
                "com.ltm.mail.Main",
                "missing_config_99999.json"
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        assertEquals(1, exitCode, "Main should exit with status 1 on missing configuration");
        assertTrue(output.contains("HOÀN THIỆN SMTP CLIENT JAVA"), "Output should contain application banner");
        assertTrue(output.contains("HƯỚNG DẪN XỬ LÝ SỰ CỐ"), "Output should contain troubleshooting section");
    }

    @Test
    @DisplayName("Troubleshooting Guide: Exception-specific diagnostic messages")
    void testTroubleshootingGuideDiagnostics() throws Exception {
        Method guideMethod = Main.class.getDeclaredMethod("printTroubleshootingGuide", Exception.class);
        guideMethod.setAccessible(true);

        // Test 1: AuthenticationFailedException (code 535)
        ByteArrayOutputStream err1 = new ByteArrayOutputStream();
        PrintStream origErr = System.err;
        try {
            System.setErr(new PrintStream(err1));
            guideMethod.invoke(null, new AuthenticationFailedException("535 5.7.8 Bad credentials"));
            String out1 = err1.toString();
            assertTrue(out1.contains("535") && out1.contains("Mật khẩu ứng dụng"));
        } finally {
            System.setErr(origErr);
        }

        // Test 2: UnknownHostException (DNS)
        ByteArrayOutputStream err2 = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(err2));
            guideMethod.invoke(null, new UnknownHostException("smtp.invalid-domain-999.com"));
            String out2 = err2.toString();
            assertTrue(out2.contains("DNS") && out2.contains("Internet"));
        } finally {
            System.setErr(origErr);
        }

        // Test 3: SSLHandshakeException
        ByteArrayOutputStream err3 = new ByteArrayOutputStream();
        try {
            System.setErr(new PrintStream(err3));
            guideMethod.invoke(null, new SSLHandshakeException("Handshake failed"));
            String out3 = err3.toString();
            assertTrue(out3.contains("SSL") && out3.contains("Handshake"));
        } finally {
            System.setErr(origErr);
        }
    }
}
