package com.ltm.mail.config;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * Cấu hình kết nối và nội dung email SMTP
 */
public class SmtpConfig {

    @SerializedName("Host")
    private String host = "smtp.gmail.com";

    @SerializedName("Port")
    private int port = 465;

    @SerializedName("EnableSsl")
    private boolean enableSsl = true;

    @SerializedName("Username")
    private String username;

    @SerializedName("Password")
    private String password;

    @SerializedName("AuthType")
    private String authType = "LOGIN";

    @SerializedName("From")
    private String from;

    @SerializedName("To")
    private String to;

    @SerializedName("Subject")
    private String subject;

    @SerializedName("Body")
    private String body;

    @SerializedName("AttachmentPaths")
    private List<String> attachmentPaths = new ArrayList<>();

    @SerializedName("Retry")
    private RetryConfig retry = new RetryConfig();

    public SmtpConfig() {
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isEnableSsl() {
        return enableSsl;
    }

    public void setEnableSsl(boolean enableSsl) {
        this.enableSsl = enableSsl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getAuthType() {
        return authType;
    }

    public void setAuthType(String authType) {
        this.authType = authType;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

    public List<String> getAttachmentPaths() {
        return attachmentPaths;
    }

    public void setAttachmentPaths(List<String> attachmentPaths) {
        this.attachmentPaths = attachmentPaths;
    }

    public RetryConfig getRetry() {
        return retry;
    }

    public void setRetry(RetryConfig retry) {
        this.retry = retry;
    }

    @Override
    public String toString() {
        return "SmtpConfig{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", enableSsl=" + enableSsl +
                ", username='" + username + '\'' +
                ", authType='" + authType + '\'' +
                ", from='" + from + '\'' +
                ", to='" + to + '\'' +
                ", subject='" + subject + '\'' +
                ", attachmentsCount=" + (attachmentPaths != null ? attachmentPaths.size() : 0) +
                ", retry=" + retry +
                '}';
    }
}
