package com.ltm.mail.ui;

import com.ltm.mail.client.EmailMessageBuilder;

import java.io.File;

/**
 * Đại diện cho một file đính kèm hiển thị trên giao diện người dùng
 */
public class AttachmentItem {

    private final File file;

    public AttachmentItem(File file) {
        if (file == null) {
            throw new IllegalArgumentException("File đính kèm không được null");
        }
        this.file = file;
    }

    public File getFile() {
        return file;
    }

    public String getName() {
        return file.getName();
    }

    public String getAbsolutePath() {
        return file.getAbsolutePath();
    }

    public long getSize() {
        return file.length();
    }

    /**
     * Định dạng kích thước file sang định dạng người dùng dễ đọc (KB/MB)
     */
    public String getFormattedSize() {
        long bytes = file.length();
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        } else {
            return String.format(java.util.Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
        }
    }

    public boolean isTooLarge() {
        return file.length() > EmailMessageBuilder.MAX_ATTACHMENT_SIZE;
    }

    public boolean isSensitive() {
        return EmailMessageBuilder.isSensitiveFile(file);
    }

    public boolean isValid() {
        return file.exists() && file.isFile() && file.canRead() && !isTooLarge() && !isSensitive();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AttachmentItem that = (AttachmentItem) o;
        return file.getAbsolutePath().equalsIgnoreCase(that.file.getAbsolutePath());
    }

    @Override
    public int hashCode() {
        return file.getAbsolutePath().toLowerCase().hashCode();
    }

    @Override
    public String toString() {
        return getName() + " (" + getFormattedSize() + ")";
    }
}
