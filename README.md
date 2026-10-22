# SMTP Client Java – Gửi Email Có File Đính Kèm An Toàn

Dự án Java mô phỏng và hoàn thiện SMTP Client phục vụ môn học **Lập Trình Mạng**, bám sát đầy đủ 10 tiêu chí kỹ thuật tại **Slide 30**:
> **SSL/TLS (SMTPS) + AUTH LOGIN/PLAIN + MIME multipart/mixed + RETRY + LOG + CONFIG**

---

## 🚀 Các Tính Năng Nổi Bật

1. **Cấu hình độc lập (`appsettings.json`)**: Đọc thiết lập từ file JSON bên ngoài, không hard-code thông tin nhạy cảm.
2. **Bảo mật kênh truyền**:
   - Sử dụng **SMTPS qua SSL/TLS** trên cổng `465` (giao thức `TLSv1.2`).
   - Dự phòng **STARTTLS** trên cổng `587`.
   - Timeout kết nối (`15s`) và đọc (`30s`) chống treo tiến trình.
3. **Xác thực linh hoạt**: Hỗ trợ cơ chế `AUTH LOGIN` / `PLAIN`, tương thích hoàn hảo với Gmail App Password (2-Step Verification).
4. **Cấu trúc MIME `multipart/mixed`**:
   - Phần nội dung: Hỗ trợ mã hóa HTML và văn bản Tiếng Việt (`UTF-8`).
   - Phần đính kèm: Tự động mã hóa Base64 (~76 ký tự/dòng), kiểm tra tính hợp lệ và dung lượng file đính kèm.
5. **Cơ chế Retry thông minh (Exponential Backoff)**:
   - Tự động thử lại khi gặp lỗi tạm thời (Transient error / mã phản hồi SMTP `4xx`, `SocketTimeoutException`, `MailboxUnavailableException` 450).
   - Công thức giãn cách thời gian: $\text{delay} = \min(\text{baseDelay} \times 2^{\text{attempt}-1}, \text{maxDelay})$.
   - **Dừng ngay** khi gặp lỗi vĩnh viễn (Permanent error / mã phản hồi `5xx`, `535 AuthenticationFailed`, `550 User unknown`, `UnknownHost`).
6. **Ghi log chi tiết**:
   - Ghi song song ra màn hình Console và lưu file nhật ký `smtp-client.log`.
   - Định dạng chuẩn: `[yyyy-MM-dd HH:mm:ss] [LEVEL] Message`.

---

## 📁 Cấu Trúc Dự Án

```
Mail/
├── .gitignore                      # Bỏ qua target/, *.log, file appsettings.json chứa credentials thật
├── pom.xml                         # Cấu hình Maven (Java 17/24, JavaMail, Gson, JUnit 5)
├── appsettings.example.json        # Template cấu hình mẫu an toàn để chia sẻ
├── appsettings.json                # Cấu hình SMTP thực tế của bạn
├── attachments/                    # Thư mục lưu file đính kèm mẫu
│   └── sample.txt                  # File mẫu để kiểm thử gửi đính kèm
├── pics/                           # Đề bài & tài liệu thiết kế (Slide 30)
│   └── Screenshot 2026-10-22 201543.png
├── src/
│   ├── main/java/com/ltm/mail/
│   │   ├── Main.java               # Entry point ứng dụng
│   │   ├── client/
│   │   │   ├── SmtpClient.java     # Quản lý Session, Transport, Send & Retry logic
│   │   │   └── EmailMessageBuilder.java # Đóng gói MimeMessage multipart/mixed
│   │   ├── config/
│   │   │   ├── AppConfig.java
│   │   │   ├── SmtpConfig.java
│   │   │   ├── RetryConfig.java
│   │   │   └── ConfigLoader.java   # Đọc và xác thực cấu hình JSON
│   │   └── util/
│   │       ├── LoggerUtil.java     # Ghi log ra Console & File
│   │       └── ExceptionClassifier.java # Phân loại lỗi Retryable vs Non-retryable
│   └── test/java/com/ltm/mail/     # 10 unit tests kiểm thử tự động
└── README.md
```

---

## ⚙️ Hướng Dẫn Cấu Hình (Gmail)

### 1. Tạo Mật khẩu ứng dụng (App Password) cho Gmail:
1. Truy cập [Google Account Security](https://myaccount.google.com/security).
2. Bật tính năng **Xác minh 2 bước** (2-Step Verification).
3. Tìm mục **Mật khẩu ứng dụng** (App passwords).
4. Tạo mật khẩu mới (ví dụ đặt tên: `Java SMTP Client`) -> Google sẽ cấp mã 16 ký tự (ví dụ: `abcd efgh ijkl mnop`).

### 2. Thiết lập `appsettings.json`:
Mở file `appsettings.json` và cập nhật thông tin:
```json
{
  "Smtp": {
    "Host": "smtp.gmail.com",
    "Port": 465,
    "EnableSsl": true,
    "Username": "your_email@gmail.com",
    "Password": "abcdefghijklmnop",
    "AuthType": "LOGIN",
    "From": "your_email@gmail.com",
    "To": "receiver_email@gmail.com",
    "Subject": "Test Email từ Java - SMTPS Client",
    "Body": "<h3>Xin chào!</h3>\n<p>Đây là email thử nghiệm gửi có đính kèm file an toàn từ Java SMTP Client.</p>",
    "AttachmentPaths": [
      "attachments/sample.txt"
    ],
    "Retry": {
      "MaxRetry": 4,
      "BaseDelaySeconds": 2,
      "MaxDelaySeconds": 60
    }
  }
}
```

---

## 🛠️ Biên Dịch và Chạy Thử Nghiệm

### 1. Biên dịch và chạy Unit Tests:
```powershell
mvn clean test
```

### 2. Đóng gói file JAR thực thi (fat jar):
```powershell
mvn clean package
```

### 3. Chạy ứng dụng:
- Cách 1: Sử dụng file JAR đã đóng gói:
```powershell
java -jar target/smtp-client-1.0.0.jar
```
- Hoặc truyền file cấu hình tuỳ chỉnh:
```powershell
java -jar target/smtp-client-1.0.0.jar duong/dan/toi/config.json
```
- Cách 2: Chạy trực tiếp qua Maven:
```powershell
mvn exec:java
```

---

## 📊 Phân Loại Ngoại Lệ & Xử Lý Sự Cố

| Tên Ngoại Lệ | Nguyên Nhân | Hành Động |
| :--- | :--- | :--- |
| `AuthenticationFailedException` | Sai tài khoản / mật khẩu (mã `535`) | **Không retry**. Kiểm tra lại tài khoản và App Password Gmail. |
| `SSLHandshakeException` | Lỗi SSL/TLS (chứng chỉ, phiên bản TLS) | **Không retry**. Dùng Port 465, bật TLSv1.2. |
| `UnknownHostException` | Không phân giải được hostname máy chủ | **Không retry**. Kiểm tra mạng, DNS và tên Host. |
| `SendFailedException` | Địa chỉ người nhận không tồn tại (mã `550`) | **Không retry**. Kiểm tra lại email người nhận. |
| `SocketTimeoutException` | Hết thời gian chờ kết nối | **Retry**. Áp dụng giải thuật Exponential Backoff. |
| `MailboxUnavailableException` | Hộp thư người nhận tạm thời bận (mã `450`) | **Retry**. Thử lại sau thời gian chờ. |
