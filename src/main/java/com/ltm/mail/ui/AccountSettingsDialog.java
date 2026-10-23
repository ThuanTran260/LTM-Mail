package com.ltm.mail.ui;

import com.ltm.mail.client.SmtpClient;
import com.ltm.mail.config.AppConfig;
import com.ltm.mail.config.ConfigLoader;
import com.ltm.mail.config.RetryConfig;
import com.ltm.mail.config.SmtpConfig;
import com.ltm.mail.util.LoggerUtil;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.function.Consumer;

/**
 * Hộp thoại Cài Đặt Tài Khoản SMTP với mẫu thiết lập sẵn (Gmail, Outlook, Yahoo, Custom),
 * kiểm tra kết nối thời gian thực và lưu cấu hình vào appsettings.json.
 */
public class AccountSettingsDialog extends JDialog {

    private final AppConfig appConfig;
    private final Consumer<AppConfig> onConfigSaved;

    private JComboBox<String> cboPreset;
    private JTextField txtHost;
    private JSpinner spnPort;
    private JCheckBox chkEnableSsl;
    private JComboBox<String> cboAuthType;
    private JTextField txtUsername;
    private JPasswordField txtPassword;
    private JButton btnTogglePassword;
    private JTextField txtFrom;

    private JSpinner spnMaxRetry;
    private JSpinner spnBaseDelay;
    private JSpinner spnMaxDelay;

    private JLabel lblStatus;
    private JButton btnTestConnection;
    private JButton btnSave;
    private JButton btnCancel;

    private boolean isApplyingPresetProgrammatically = false;
    private char defaultEchoChar = '•';

    public AccountSettingsDialog(Frame owner, AppConfig appConfig, Consumer<AppConfig> onConfigSaved) {
        super(owner, "Cài Đặt Tài Khoản & Máy Chủ SMTP", true);
        this.appConfig = appConfig;
        this.onConfigSaved = onConfigSaved;

        initComponents();
        loadCurrentConfig();
        setSize(560, 680);
        setLocationRelativeTo(owner);
        setResizable(false);
    }

    private void initComponents() {
        JPanel contentPane = new JPanel(new BorderLayout(10, 10));
        contentPane.setBorder(new EmptyBorder(16, 20, 16, 20));
        setContentPane(contentPane);

        // Header Banner
        JPanel headerPanel = new JPanel(new BorderLayout(8, 4));
        headerPanel.setBorder(new EmptyBorder(0, 0, 10, 0));
        JLabel lblTitle = new JLabel("⚙ Cấu Hình Máy Chủ & Tài Khoản SMTP");
        lblTitle.setFont(lblTitle.getFont().deriveFont(Font.BOLD, 16f));
        lblTitle.setForeground(ThemeManager.getAccentColor());
        JLabel lblSubtitle = new JLabel("Thiết lập thông tin gửi thư và lưu vào appsettings.json để sử dụng lâu dài.");
        lblSubtitle.setForeground(UIManager.getColor("Label.disabledForeground"));
        headerPanel.add(lblTitle, BorderLayout.NORTH);
        headerPanel.add(lblSubtitle, BorderLayout.SOUTH);
        contentPane.add(headerPanel, BorderLayout.NORTH);

        // Form Body
        JPanel formBody = new JPanel();
        formBody.setLayout(new BoxLayout(formBody, BoxLayout.Y_AXIS));

        // 1. Preset Selector Panel
        JPanel presetPanel = new JPanel(new BorderLayout(10, 5));
        presetPanel.setBorder(new CompoundBorder(
                new TitledBorder("1. Mẫu Cấu Hình Thiết Lập Sẵn (Preset)"),
                new EmptyBorder(8, 12, 10, 12)
        ));
        cboPreset = new JComboBox<>(new String[]{
                "Gmail (SMTPS - Cổng 465, Bảo mật SSL/TLS)",
                "Outlook / Office 365 (STARTTLS - Cổng 587)",
                "Yahoo Mail (SMTPS - Cổng 465, Bảo mật SSL/TLS)",
                "Tùy Chỉnh (Custom SMTP Server)"
        });
        cboPreset.putClientProperty("JComponent.roundRect", true);
        cboPreset.addActionListener(e -> applyPreset(cboPreset.getSelectedIndex()));
        presetPanel.add(cboPreset, BorderLayout.CENTER);
        formBody.add(presetPanel);
        formBody.add(Box.createVerticalStrut(10));

        // 2. Server & Security Settings
        JPanel serverPanel = new JPanel(new GridBagLayout());
        serverPanel.setBorder(new CompoundBorder(
                new TitledBorder("2. Thông Tin Máy Chủ & Bảo Mật"),
                new EmptyBorder(8, 12, 10, 12)
        ));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Host
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.25;
        serverPanel.add(new JLabel("Máy chủ (Host):"), gbc);
        txtHost = new JTextField("smtp.gmail.com");
        txtHost.putClientProperty("JTextField.placeholderText", "Ví dụ: smtp.gmail.com");
        gbc.gridx = 1; gbc.weightx = 0.75;
        serverPanel.add(txtHost, gbc);

        // Port & SSL
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.25;
        serverPanel.add(new JLabel("Cổng (Port):"), gbc);
        JPanel portPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        spnPort = new JSpinner(new SpinnerNumberModel(465, 1, 65535, 1));
        spnPort.setPreferredSize(new Dimension(85, 28));
        portPanel.add(spnPort);

        chkEnableSsl = new JCheckBox("Bật SSL/TLS trực tiếp (SMTPS 465)");
        chkEnableSsl.setSelected(true);
        chkEnableSsl.addActionListener(e -> {
            if (!chkEnableSsl.isSelected() && (int) spnPort.getValue() == 465) {
                spnPort.setValue(587);
            } else if (chkEnableSsl.isSelected() && (int) spnPort.getValue() == 587) {
                spnPort.setValue(465);
            }
        });
        portPanel.add(chkEnableSsl);
        gbc.gridx = 1; gbc.weightx = 0.75;
        serverPanel.add(portPanel, gbc);

        // AuthType
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.25;
        serverPanel.add(new JLabel("Xác thực (Auth):"), gbc);
        cboAuthType = new JComboBox<>(new String[]{"LOGIN", "PLAIN"});
        cboAuthType.setPreferredSize(new Dimension(120, 28));
        JPanel authPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        authPanel.add(cboAuthType);
        gbc.gridx = 1; gbc.weightx = 0.75;
        serverPanel.add(authPanel, gbc);

        formBody.add(serverPanel);
        formBody.add(Box.createVerticalStrut(10));

        // 3. Credentials Panel
        JPanel credPanel = new JPanel(new GridBagLayout());
        credPanel.setBorder(new CompoundBorder(
                new TitledBorder("3. Thông Tin Xác Thực (Tài Khoản & Mật Khẩu)"),
                new EmptyBorder(8, 12, 10, 12)
        ));

        // Username
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.25;
        credPanel.add(new JLabel("Email / Username:"), gbc);
        txtUsername = new JTextField();
        txtUsername.putClientProperty("JTextField.placeholderText", "your_email@gmail.com");
        gbc.gridx = 1; gbc.weightx = 0.75;
        credPanel.add(txtUsername, gbc);

        // Password with show/hide
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.25;
        credPanel.add(new JLabel("Mật khẩu ứng dụng:"), gbc);
        JPanel passPanel = new JPanel(new BorderLayout(5, 0));
        txtPassword = new JPasswordField();
        txtPassword.putClientProperty("JTextField.placeholderText", "Mã 16 ký tự (Google App Password)");
        defaultEchoChar = txtPassword.getEchoChar();
        btnTogglePassword = new JButton("👁");
        btnTogglePassword.setToolTipText("Hiện/Ẩn mật khẩu");
        btnTogglePassword.setPreferredSize(new Dimension(42, 28));
        btnTogglePassword.addActionListener(e -> {
            if (txtPassword.getEchoChar() == '\u0000') {
                txtPassword.setEchoChar(defaultEchoChar);
                btnTogglePassword.setText("👁");
            } else {
                txtPassword.setEchoChar('\u0000');
                btnTogglePassword.setText("🙈");
            }
        });
        passPanel.add(txtPassword, BorderLayout.CENTER);
        passPanel.add(btnTogglePassword, BorderLayout.EAST);
        gbc.gridx = 1; gbc.weightx = 0.75;
        credPanel.add(passPanel, gbc);

        // From Email
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.25;
        credPanel.add(new JLabel("Người gửi (From):"), gbc);
        txtFrom = new JTextField();
        txtFrom.putClientProperty("JTextField.placeholderText", "Để trống sẽ dùng Email/Username");
        gbc.gridx = 1; gbc.weightx = 0.75;
        credPanel.add(txtFrom, gbc);

        formBody.add(credPanel);
        formBody.add(Box.createVerticalStrut(10));

        // 4. Retry Configuration
        JPanel retryPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 6));
        retryPanel.setBorder(new CompoundBorder(
                new TitledBorder("4. Cấu Hình Cơ Chế Thử Lại (Retry Policy)"),
                new EmptyBorder(4, 12, 6, 12)
        ));
        retryPanel.add(new JLabel("Thử lại tối đa:"));
        spnMaxRetry = new JSpinner(new SpinnerNumberModel(3, 0, 10, 1));
        spnMaxRetry.setPreferredSize(new Dimension(55, 26));
        retryPanel.add(spnMaxRetry);

        retryPanel.add(new JLabel("Chờ cơ sở:"));
        spnBaseDelay = new JSpinner(new SpinnerNumberModel(2, 1, 30, 1));
        spnBaseDelay.setPreferredSize(new Dimension(55, 26));
        retryPanel.add(spnBaseDelay);
        retryPanel.add(new JLabel("giây"));

        retryPanel.add(new JLabel("Chờ tối đa:"));
        spnMaxDelay = new JSpinner(new SpinnerNumberModel(60, 5, 300, 5));
        spnMaxDelay.setPreferredSize(new Dimension(65, 26));
        retryPanel.add(spnMaxDelay);
        retryPanel.add(new JLabel("giây"));

        formBody.add(retryPanel);
        contentPane.add(formBody, BorderLayout.CENTER);

        // Bottom Controls & Test Connection Bar
        JPanel bottomPanel = new JPanel(new BorderLayout(5, 8));
        bottomPanel.setBorder(new EmptyBorder(10, 0, 0, 0));

        // Status Label
        lblStatus = new JLabel("● Sẵn sàng kiểm tra kết nối với máy chủ.");
        lblStatus.setFont(lblStatus.getFont().deriveFont(12f));
        lblStatus.setForeground(UIManager.getColor("Label.disabledForeground"));
        bottomPanel.add(lblStatus, BorderLayout.NORTH);

        // Button Bar
        JPanel btnBar = new JPanel(new BorderLayout());

        btnTestConnection = new JButton("🔍 Kiểm Tra Kết Nối");
        btnTestConnection.putClientProperty("JButton.buttonType", "roundRect");
        btnTestConnection.addActionListener(e -> performConnectionTest());
        btnBar.add(btnTestConnection, BorderLayout.WEST);

        JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnSave = new JButton("💾 Lưu Cấu Hình");
        btnSave.putClientProperty("JButton.buttonType", "default");
        btnSave.setBackground(ThemeManager.getAccentColor());
        btnSave.setForeground(Color.WHITE);
        btnSave.addActionListener(e -> saveConfiguration());

        btnCancel = new JButton("Đóng");
        btnCancel.addActionListener(e -> dispose());

        rightBtns.add(btnSave);
        rightBtns.add(btnCancel);
        btnBar.add(rightBtns, BorderLayout.EAST);

        bottomPanel.add(btnBar, BorderLayout.SOUTH);
        contentPane.add(bottomPanel, BorderLayout.SOUTH);
    }

    private void applyPreset(int index) {
        if (isApplyingPresetProgrammatically) {
            return;
        }
        switch (index) {
            case 0: // Gmail SMTPS 465
                txtHost.setText("smtp.gmail.com");
                spnPort.setValue(465);
                chkEnableSsl.setSelected(true);
                cboAuthType.setSelectedItem("LOGIN");
                break;
            case 1: // Outlook STARTTLS 587
                txtHost.setText("smtp.office365.com");
                spnPort.setValue(587);
                chkEnableSsl.setSelected(false);
                cboAuthType.setSelectedItem("LOGIN");
                break;
            case 2: // Yahoo SMTPS 465
                txtHost.setText("smtp.mail.yahoo.com");
                spnPort.setValue(465);
                chkEnableSsl.setSelected(true);
                cboAuthType.setSelectedItem("LOGIN");
                break;
            case 3: // Custom
            default:
                break;
        }
    }

    private void loadCurrentConfig() {
        if (appConfig == null || appConfig.getSmtp() == null) {
            return;
        }
        SmtpConfig smtp = appConfig.getSmtp();
        txtHost.setText(smtp.getHost() != null ? smtp.getHost() : "smtp.gmail.com");
        spnPort.setValue(smtp.getPort() > 0 ? smtp.getPort() : 465);
        chkEnableSsl.setSelected(smtp.isEnableSsl());
        cboAuthType.setSelectedItem("PLAIN".equalsIgnoreCase(smtp.getAuthType()) ? "PLAIN" : "LOGIN");

        txtUsername.setText(smtp.getUsername() != null ? smtp.getUsername() : "");
        txtPassword.setText(smtp.getPassword() != null ? smtp.getPassword() : "");
        txtFrom.setText(smtp.getFrom() != null ? smtp.getFrom() : "");

        RetryConfig retry = smtp.getRetry() != null ? smtp.getRetry() : new RetryConfig();
        spnMaxRetry.setValue(retry.getMaxRetry() >= 0 ? retry.getMaxRetry() : 3);
        spnBaseDelay.setValue(retry.getBaseDelaySeconds() > 0 ? retry.getBaseDelaySeconds() : 2);
        spnMaxDelay.setValue(retry.getMaxDelaySeconds() > 0 ? retry.getMaxDelaySeconds() : 60);

        // Suy đoán preset phù hợp mà không ghi đè giá trị người dùng
        isApplyingPresetProgrammatically = true;
        try {
            String host = txtHost.getText().toLowerCase();
            if (host.contains("gmail")) {
                cboPreset.setSelectedIndex(0);
            } else if (host.contains("outlook") || host.contains("office365")) {
                cboPreset.setSelectedIndex(1);
            } else if (host.contains("yahoo")) {
                cboPreset.setSelectedIndex(2);
            } else {
                cboPreset.setSelectedIndex(3);
            }
        } finally {
            isApplyingPresetProgrammatically = false;
        }
    }

    private SmtpConfig buildConfigFromInputs() {
        SmtpConfig config = new SmtpConfig();
        config.setHost(txtHost.getText().trim());
        config.setPort((Integer) spnPort.getValue());
        config.setEnableSsl(chkEnableSsl.isSelected());
        config.setAuthType((String) cboAuthType.getSelectedItem());
        config.setUsername(txtUsername.getText().trim());
        config.setPassword(new String(txtPassword.getPassword()).trim());

        String from = txtFrom.getText().trim();
        config.setFrom(from.isEmpty() ? config.getUsername() : from);

        RetryConfig retry = new RetryConfig(
                (Integer) spnMaxRetry.getValue(),
                (Integer) spnBaseDelay.getValue(),
                (Integer) spnMaxDelay.getValue()
        );
        config.setRetry(retry);
        return config;
    }

    private void performConnectionTest() {
        SmtpConfig testConfig = buildConfigFromInputs();

        if (testConfig.getHost().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập tên máy chủ SMTP Host!", "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (testConfig.getUsername().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập Email / Tên đăng nhập!", "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (testConfig.getPassword().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập Mật khẩu ứng dụng (App Password)!", "Thiếu thông tin", JOptionPane.WARNING_MESSAGE);
            return;
        }

        btnTestConnection.setEnabled(false);
        btnSave.setEnabled(false);
        lblStatus.setForeground(ThemeManager.getAccentColor());
        lblStatus.setText("⏳ Đang kết nối tới " + testConfig.getHost() + ":" + testConfig.getPort() + " và xác thực...");

        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                SmtpClient.testConnection(testConfig);
                return null;
            }

            @Override
            protected void done() {
                btnTestConnection.setEnabled(true);
                btnSave.setEnabled(true);
                try {
                    get();
                    lblStatus.setForeground(ThemeManager.getSuccessColor());
                    lblStatus.setText("✔ Kết nối & xác thực thành công tới " + testConfig.getHost() + "!");
                    JOptionPane.showMessageDialog(AccountSettingsDialog.this,
                            "Xác thực máy chủ SMTP thành công!\nTài khoản: " + testConfig.getUsername(),
                            "Kiểm Tra Thành Công", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    lblStatus.setForeground(ThemeManager.getDangerColor());
                    lblStatus.setText("✖ Thất bại: " + cause.getMessage());

                    String hint = "\n\nGợi ý khắc phục:\n";
                    if (cause instanceof javax.mail.AuthenticationFailedException || cause.getMessage().contains("535")) {
                        hint += "- Với Gmail: Hãy tạo 'Mật khẩu ứng dụng' (16 chữ cái) trong Quản lý tài khoản Google -> Bảo mật.\n"
                                + "- Không sử dụng mật khẩu đăng nhập thông thường của Gmail.";
                    } else if (cause instanceof java.net.UnknownHostException) {
                        hint += "- Kiểm tra lại tên miền máy chủ SMTP và kết nối mạng Internet.";
                    } else if (cause instanceof javax.net.ssl.SSLHandshakeException) {
                        hint += "- Kiểm tra lại cổng Port và tùy chọn SSL (465 cho SSL, 587 cho STARTTLS).";
                    }

                    JOptionPane.showMessageDialog(AccountSettingsDialog.this,
                            "Không thể kết nối hoặc xác thực SMTP:\n" + cause.getMessage() + hint,
                            "Lỗi Kiểm Tra Kết Nối", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        worker.execute();
    }

    private void saveConfiguration() {
        SmtpConfig newSmtp = buildConfigFromInputs();

        if (newSmtp.getHost().isEmpty() || newSmtp.getUsername().isEmpty() || newSmtp.getPassword().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập đầy đủ Host, Email và Mật khẩu ứng dụng!", "Thiếu dữ liệu", JOptionPane.WARNING_MESSAGE);
            return;
        }

        if ((Integer) spnBaseDelay.getValue() > (Integer) spnMaxDelay.getValue()) {
            JOptionPane.showMessageDialog(this,
                    "Thời gian chờ cơ sở (Base Delay) không thể lớn hơn thời gian chờ tối đa (Max Delay)!",
                    "Cấu Hình Không Hợp Lệ", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            // Bảo toàn To, Subject, Body, Attachments hiện tại nếu có
            if (appConfig.getSmtp() != null) {
                newSmtp.setTo(appConfig.getSmtp().getTo());
                newSmtp.setCc(appConfig.getSmtp().getCc());
                newSmtp.setBcc(appConfig.getSmtp().getBcc());
                newSmtp.setSubject(appConfig.getSmtp().getSubject());
                newSmtp.setBody(appConfig.getSmtp().getBody());
                newSmtp.setAttachmentPaths(appConfig.getSmtp().getAttachmentPaths());
            }

            appConfig.setSmtp(newSmtp);

            // Ghi vào appsettings.json
            ConfigLoader.saveConfig(appConfig);

            if (onConfigSaved != null) {
                onConfigSaved.accept(appConfig);
            }

            JOptionPane.showMessageDialog(this,
                    "Đã lưu cấu hình tài khoản vào appsettings.json thành công!\nCác lần khởi chạy sau sẽ tự động tải cấu hình này.",
                    "Lưu Cấu Hình Thành Công", JOptionPane.INFORMATION_MESSAGE);
            dispose();
        } catch (Exception ex) {
            LoggerUtil.error("Không thể lưu cấu hình", ex);
            JOptionPane.showMessageDialog(this, "Lỗi khi lưu appsettings.json: " + ex.getMessage(), "Lỗi Lưu", JOptionPane.ERROR_MESSAGE);
        }
    }
}
