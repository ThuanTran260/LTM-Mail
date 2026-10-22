package com.ltm.mail.config;

import com.google.gson.annotations.SerializedName;

/**
 * Lớp cấu hình gốc ánh xạ file appsettings.json
 */
public class AppConfig {

    @SerializedName("Smtp")
    private SmtpConfig smtp;

    public AppConfig() {
    }

    public AppConfig(SmtpConfig smtp) {
        this.smtp = smtp;
    }

    public SmtpConfig getSmtp() {
        return smtp;
    }

    public void setSmtp(SmtpConfig smtp) {
        this.smtp = smtp;
    }

    @Override
    public String toString() {
        return "AppConfig{" +
                "smtp=" + smtp +
                '}';
    }
}
