package com.ltm.mail.ui;

import com.ltm.mail.client.EmailMessageBuilder;
import com.ltm.mail.client.SmtpClient;
import com.ltm.mail.config.AppConfig;
import com.ltm.mail.config.ConfigLoader;
import com.ltm.mail.config.SmtpConfig;
import com.ltm.mail.util.ExceptionClassifier;
import com.ltm.mail.util.LoggerUtil;

import javax.mail.Session;
import javax.mail.internet.MimeMessage;
import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Cửa sổ chính giao diện đồ họa Desktop (Modern Outlook Style) cho ứng dụng SMTP Client:
 * - Thanh công cụ Ribbon phong cách Outlook / Windows 11
 * - Form soạn thảo hoàn chỉnh (To, CC, BCC, Subject, HTML/UTF-8 Editor + Preview)
 * - Quản lý file đính kèm trực quan với dung lượng và kiểm tra an toàn
 * - Tiến trình thời gian thực, bộ đếm ngược Retry (Exponential Backoff), và bảng nhật ký chi tiết
 */
public class MailAppFrame extends JFrame {

    private AppConfig appConfig;

    // Ribbon Action Buttons
    private JButton btnSend;
    private JButton btnAttach;
    private JButton btnSettings;
    private JButton btnNew;
    private JButton btnToggleTheme;
    private JLabel lblActiveAccount;

    // Compose Form Fields
    private JLabel lblFrom;
    private JTextField txtTo;
    private JTextField txtCc;
    private JTextField txtBcc;
    private JTextField txtSubject;

    // Attachments UI
    private DefaultTableModel attachmentTableModel;
    private JTable attachmentTable;
    private JLabel lblAttachmentSummary;
    private JButton btnAddAttachment;
    private JButton btnRemoveAttachment;
    private JButton btnClearAttachment;
    private final List<AttachmentItem> attachments = new ArrayList<>();

    // Body Editor
    private JTabbedPane bodyTabbedPane;
    private JTextArea txtBodyEditor;
    private JEditorPane htmlPreviewPane;

    // Bottom Progress & Log Panel
    private JProgressBar progressBar;
    private JLabel lblStatus;
    private JPanel retryBannerPanel;
    private JLabel lblRetryText;
    private Timer retryCountdownTimer;
    private JTextArea txtLogConsole;
    private LoggerUtil.LogListener logListener;

    public MailAppFrame() {
        super("Outlook Mail Client - Gửi Thư SMTP (Lập Trình Mạng)");
        loadInitialConfig();
        initComponents();
        setupLogListener();

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1020, 780);
        setMinimumSize(new Dimension(840, 620));
        setLocationRelativeTo(null);

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                cleanupResources();
            }
        });
    }

    @Override
    public void dispose() {
        cleanupResources();
        super.dispose();
    }

    private void cleanupResources() {
        if (logListener != null) {
            LoggerUtil.removeLogListener(logListener);
            logListener = null;
        }
    }

    private void loadInitialConfig() {
        try {
            this.appConfig = ConfigLoader.loadConfig();
        } catch (Exception e) {
            LoggerUtil.warn("Không thể tải cấu hình tự động: " + e.getMessage() + ". Khởi tạo cấu hình mặc định.");
            this.appConfig = new AppConfig(new SmtpConfig());
        }
    }

    private void initComponents() {
        JPanel rootPanel = new JPanel(new BorderLayout(0, 0));
        setContentPane(rootPanel);

        // 1. TOP: Ribbon Bar
        JPanel ribbonBar = createRibbonBar();
        rootPanel.add(ribbonBar, BorderLayout.NORTH);

        // 2. CENTER: Main Split (Compose Form on top, Attachments & Body in center)
        JPanel mainContentPanel = createMainContentPanel();

        // 3. BOTTOM: Progress, Retry Countdown, and Real-time Log Console
        JPanel bottomPanel = createBottomPanel();

        JSplitPane verticalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, mainContentPanel, bottomPanel);
        verticalSplit.setResizeWeight(0.72);
        verticalSplit.setContinuousLayout(true);
        verticalSplit.setBorder(null);

        rootPanel.add(verticalSplit, BorderLayout.CENTER);

        // Phím tắt bàn phím (Accelerators)
        setupKeyboardShortcuts();
        updateActiveAccountBadge();
    }

    private JPanel createRibbonBar() {
        JPanel ribbon = new JPanel(new BorderLayout(10, 0));
        ribbon.setBorder(new CompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground") != null ? UIManager.getColor("Separator.foreground") : Color.LIGHT_GRAY),
                new EmptyBorder(8, 12, 8, 12)
        ));

        // Group Action Buttons
        JPanel actionGroup = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

        btnSend = new JButton("✉ Gửi Thư");
        btnSend.setFont(btnSend.getFont().deriveFont(Font.BOLD, 13f));
        btnSend.putClientProperty("JButton.buttonType", "default");
        btnSend.setBackground(ThemeManager.getAccentColor());
        btnSend.setForeground(Color.WHITE);
        btnSend.setToolTipText("Gửi email (Ctrl + Enter)");
        btnSend.addActionListener(e -> performSendEmail());
        actionGroup.add(btnSend);

        btnAttach = new JButton("📎 Đính Kèm File");
        btnAttach.putClientProperty("JButton.buttonType", "roundRect");
        btnAttach.setToolTipText("Chọn file đính kèm từ máy tính (Ctrl + O)");
        btnAttach.addActionListener(e -> chooseAttachments());
        actionGroup.add(btnAttach);

        btnSettings = new JButton("⚙ Cài Đặt Tài Khoản");
        btnSettings.putClientProperty("JButton.buttonType", "roundRect");
        btnSettings.setToolTipText("Mẫu SMTP (Gmail, Outlook, Yahoo) & Lưu cấu hình");
        btnSettings.addActionListener(e -> openAccountSettings());
        actionGroup.add(btnSettings);

        btnNew = new JButton("📝 Soạn Thư Mới");
        btnNew.putClientProperty("JButton.buttonType", "roundRect");
        btnNew.setToolTipText("Làm mới form soạn thư (Ctrl + N)");
        btnNew.addActionListener(e -> resetForm());
        actionGroup.add(btnNew);

        btnToggleTheme = new JButton(ThemeManager.isDarkMode() ? "☀ Sáng" : "🌙 Tối");
        btnToggleTheme.putClientProperty("JButton.buttonType", "roundRect");
        btnToggleTheme.setToolTipText("Chuyển đổi chế độ Giao diện Sáng / Tối (Ctrl + T)");
        btnToggleTheme.addActionListener(e -> {
            ThemeManager.toggleTheme(this);
            btnToggleTheme.setText(ThemeManager.isDarkMode() ? "☀ Sáng" : "🌙 Tối");
            if (retryBannerPanel != null) {
                retryBannerPanel.setBackground(ThemeManager.getWarningBackground());
            }
        });
        actionGroup.add(btnToggleTheme);

        ribbon.add(actionGroup, BorderLayout.WEST);

        // Right side: Active Account Status Badge
        JPanel accountPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 2));
        lblActiveAccount = new JLabel();
        lblActiveAccount.setFont(lblActiveAccount.getFont().deriveFont(Font.PLAIN, 12f));
        lblActiveAccount.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        lblActiveAccount.setToolTipText("Nhấp để chỉnh sửa cài đặt tài khoản");
        lblActiveAccount.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                openAccountSettings();
            }
        });
        accountPanel.add(lblActiveAccount);
        ribbon.add(accountPanel, BorderLayout.EAST);

        return ribbon;
    }

    private JPanel createMainContentPanel() {
        JPanel mainPanel = new JPanel(new BorderLayout(0, 8));
        mainPanel.setBorder(new EmptyBorder(10, 14, 4, 14));

        // 1. Header Fields (To, CC, BCC, Subject)
        JPanel headerPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // From
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.08;
        headerPanel.add(new JLabel("Từ (From):"), gbc);
        lblFrom = new JLabel("Chưa thiết lập tài khoản");
        lblFrom.setFont(lblFrom.getFont().deriveFont(Font.BOLD));
        gbc.gridx = 1; gbc.weightx = 0.92;
        headerPanel.add(lblFrom, gbc);

        // To
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.08;
        headerPanel.add(new JLabel("Đến (To):"), gbc);
        txtTo = new JTextField();
        txtTo.putClientProperty("JTextField.placeholderText", "Địa chỉ email người nhận (ví dụ: recipient@example.com, user2@gmail.com)");
        txtTo.putClientProperty("JTextField.showClearButton", true);
        if (appConfig.getSmtp() != null && appConfig.getSmtp().getTo() != null) {
            txtTo.setText(appConfig.getSmtp().getTo());
        }
        gbc.gridx = 1; gbc.weightx = 0.92;
        headerPanel.add(txtTo, gbc);

        // CC & BCC in a collapsible or two-column row
        JPanel ccBccPanel = new JPanel(new GridBagLayout());
        GridBagConstraints ccGbc = new GridBagConstraints();
        ccGbc.insets = new Insets(0, 0, 0, 8);
        ccGbc.fill = GridBagConstraints.HORIZONTAL;

        ccGbc.gridx = 0; ccGbc.gridy = 0; ccGbc.weightx = 0.05;
        ccBccPanel.add(new JLabel("CC:"), ccGbc);
        txtCc = new JTextField();
        txtCc.putClientProperty("JTextField.placeholderText", "Đồng kính gửi (CC)");
        txtCc.putClientProperty("JTextField.showClearButton", true);
        if (appConfig.getSmtp() != null && appConfig.getSmtp().getCc() != null) {
            txtCc.setText(appConfig.getSmtp().getCc());
        }
        ccGbc.gridx = 1; ccGbc.weightx = 0.45;
        ccBccPanel.add(txtCc, ccGbc);

        ccGbc.gridx = 2; ccGbc.weightx = 0.05;
        ccBccPanel.add(new JLabel("BCC:"), ccGbc);
        txtBcc = new JTextField();
        txtBcc.putClientProperty("JTextField.placeholderText", "Kính gửi ẩn (BCC)");
        txtBcc.putClientProperty("JTextField.showClearButton", true);
        if (appConfig.getSmtp() != null && appConfig.getSmtp().getBcc() != null) {
            txtBcc.setText(appConfig.getSmtp().getBcc());
        }
        ccGbc.gridx = 3; ccGbc.weightx = 0.45;
        ccBccPanel.add(txtBcc, ccGbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2; gbc.weightx = 1.0;
        headerPanel.add(ccBccPanel, gbc);
        gbc.gridwidth = 1; // reset

        // Subject
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.08;
        headerPanel.add(new JLabel("Tiêu đề:"), gbc);
        txtSubject = new JTextField();
        txtSubject.putClientProperty("JTextField.placeholderText", "Nhập tiêu đề thư (hỗ trợ Tiếng Việt có dấu UTF-8)...");
        txtSubject.putClientProperty("JTextField.showClearButton", true);
        if (appConfig.getSmtp() != null && appConfig.getSmtp().getSubject() != null) {
            txtSubject.setText(appConfig.getSmtp().getSubject());
        }
        gbc.gridx = 1; gbc.weightx = 0.92;
        headerPanel.add(txtSubject, gbc);

        mainPanel.add(headerPanel, BorderLayout.NORTH);

        // 2. Attachments & Body Split
        JPanel centerPanel = new JPanel(new BorderLayout(0, 8));

        // Attachments Area
        JPanel attachmentPanel = createAttachmentPanel();
        centerPanel.add(attachmentPanel, BorderLayout.NORTH);

        // Body Editor Area
        JPanel bodyPanel = createBodyPanel();
        centerPanel.add(bodyPanel, BorderLayout.CENTER);

        mainPanel.add(centerPanel, BorderLayout.CENTER);
        return mainPanel;
    }

    private JPanel createAttachmentPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 4));
        panel.setBorder(new CompoundBorder(
                new TitledBorder("Quản Lý File Đính Kèm (Tối đa 25MB)"),
                new EmptyBorder(4, 8, 4, 8)
        ));

        // Table showing attached files
        attachmentTableModel = new DefaultTableModel(new Object[]{"Tên File", "Dung Lượng", "Đường Dẫn Đầy Đủ"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        attachmentTable = new JTable(attachmentTableModel);
        attachmentTable.setRowHeight(24);
        attachmentTable.getColumnModel().getColumn(0).setPreferredWidth(220);
        attachmentTable.getColumnModel().getColumn(1).setPreferredWidth(100);
        attachmentTable.getColumnModel().getColumn(2).setPreferredWidth(500);

        // Phím tắt Delete/Backspace để gỡ bỏ file đính kèm
        attachmentTable.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deleteAttachment");
        attachmentTable.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "deleteAttachment");
        attachmentTable.getActionMap().put("deleteAttachment", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                removeSelectedAttachment();
            }
        });

        JScrollPane scrollPane = new JScrollPane(attachmentTable);
        scrollPane.setPreferredSize(new Dimension(panel.getWidth(), 80));
        panel.add(scrollPane, BorderLayout.CENTER);

        // Buttons for attachment actions
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        lblAttachmentSummary = new JLabel("0 tệp (0 B)");
        lblAttachmentSummary.setForeground(UIManager.getColor("Label.disabledForeground"));

        btnAddAttachment = new JButton("➕ Thêm File...");
        btnAddAttachment.putClientProperty("JButton.buttonType", "roundRect");
        btnAddAttachment.addActionListener(e -> chooseAttachments());

        btnRemoveAttachment = new JButton("➖ Gỡ Bỏ");
        btnRemoveAttachment.putClientProperty("JButton.buttonType", "roundRect");
        btnRemoveAttachment.addActionListener(e -> removeSelectedAttachment());

        btnClearAttachment = new JButton("🗑 Xóa Hết");
        btnClearAttachment.putClientProperty("JButton.buttonType", "roundRect");
        btnClearAttachment.addActionListener(e -> clearAllAttachments());

        btnPanel.add(lblAttachmentSummary);
        btnPanel.add(Box.createHorizontalStrut(10));
        btnPanel.add(btnAddAttachment);
        btnPanel.add(btnRemoveAttachment);
        btnPanel.add(btnClearAttachment);

        panel.add(btnPanel, BorderLayout.SOUTH);

        // Load any attachments from initial config if they exist
        if (appConfig.getSmtp() != null && appConfig.getSmtp().getAttachmentPaths() != null) {
            for (String path : appConfig.getSmtp().getAttachmentPaths()) {
                File f = new File(path);
                if (f.exists() && f.isFile()) {
                    addAttachmentItem(new AttachmentItem(f));
                }
            }
        }

        return panel;
    }

    private JPanel createBodyPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));

        bodyTabbedPane = new JTabbedPane();

        // Tab 1: Soạn Thảo (Văn bản & HTML)
        JPanel editTab = new JPanel(new BorderLayout(0, 4));

        // Quick HTML format toolbar
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        toolbar.add(new JLabel("Chèn nhanh HTML:"));

        toolbar.add(createFormatButton("<b>Đậm</b>", "<b>", "</b>"));
        toolbar.add(createFormatButton("<i>Nghiêng</i>", "<i>", "</i>"));
        toolbar.add(createFormatButton("<u>Gạch chân</u>", "<u>", "</u>"));
        toolbar.add(createFormatButton("<h1>H1</h1>", "<h1>", "</h1>"));
        toolbar.add(createFormatButton("<p>Đoạn</p>", "<p>", "</p>"));
        toolbar.add(createFormatButton("<a>Link</a>", "<a href=\"https://...\">", "</a>"));
        toolbar.add(createFormatButton("<br>Xuống dòng", "<br>\n", ""));

        JButton btnTemplate = new JButton("📋 Mẫu Email Chuẩn");
        btnTemplate.putClientProperty("JButton.buttonType", "roundRect");
        btnTemplate.addActionListener(e -> insertSampleTemplate());
        toolbar.add(btnTemplate);

        editTab.add(toolbar, BorderLayout.NORTH);

        txtBodyEditor = new JTextArea();
        txtBodyEditor.setFont(new Font("Consolas", Font.PLAIN, 13));
        txtBodyEditor.setLineWrap(true);
        txtBodyEditor.setWrapStyleWord(true);
        if (appConfig.getSmtp() != null && appConfig.getSmtp().getBody() != null) {
            txtBodyEditor.setText(appConfig.getSmtp().getBody());
        } else {
            insertSampleTemplate();
        }

        JScrollPane editScroll = new JScrollPane(txtBodyEditor);
        editTab.add(editScroll, BorderLayout.CENTER);

        // Tab 2: Xem Trước (HTML Preview)
        JPanel previewTab = new JPanel(new BorderLayout());
        htmlPreviewPane = new JEditorPane();
        htmlPreviewPane.setContentType("text/html; charset=UTF-8");
        htmlPreviewPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        htmlPreviewPane.setEditable(false);
        JScrollPane previewScroll = new JScrollPane(htmlPreviewPane);
        previewTab.add(previewScroll, BorderLayout.CENTER);

        bodyTabbedPane.addTab("✏ Soạn Thảo (HTML / Text)", editTab);
        bodyTabbedPane.addTab("👁 Xem Trước Trực Quan", previewTab);

        // Khi chuyển sang tab Xem Trước, tự động render HTML (hỗ trợ cả văn bản thuần có xuống dòng)
        bodyTabbedPane.addChangeListener(e -> {
            if (bodyTabbedPane.getSelectedIndex() == 1) {
                htmlPreviewPane.setText(formatBodyForPreview(txtBodyEditor.getText()));
                htmlPreviewPane.setCaretPosition(0);
            }
        });

        panel.add(bodyTabbedPane, BorderLayout.CENTER);
        return panel;
    }

    private String formatBodyForPreview(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "<html><body style='font-family: Segoe UI, sans-serif; padding: 12px; color: #888;'><i>(Thư chưa có nội dung soạn thảo)</i></body></html>";
        }
        String lower = raw.toLowerCase();
        if (lower.contains("<html") || lower.contains("<body") || lower.contains("<p>") || lower.contains("<div") || lower.contains("<br") || lower.contains("<h")) {
            return raw;
        }
        // Văn bản thuần: bảo toàn định dạng xuống dòng và thoát ký tự HTML đặc biệt
        String escaped = raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br/>\n");
        return "<html><body style='font-family: Segoe UI, sans-serif; padding: 12px;'>" + escaped + "</body></html>";
    }

    private JButton createFormatButton(String label, String openTag, String closeTag) {
        JButton btn = new JButton(label);
        btn.putClientProperty("JButton.buttonType", "roundRect");
        btn.setFont(btn.getFont().deriveFont(11f));
        btn.addActionListener(e -> {
            String selected = txtBodyEditor.getSelectedText();
            if (selected == null) {
                selected = "";
            }
            int start = txtBodyEditor.getSelectionStart();
            txtBodyEditor.replaceSelection(openTag + selected + closeTag);
            txtBodyEditor.requestFocusInWindow();
            if (selected.isEmpty()) {
                txtBodyEditor.setCaretPosition(start + openTag.length());
            } else {
                txtBodyEditor.setCaretPosition(start + openTag.length() + selected.length() + closeTag.length());
            }
        });
        return btn;
    }

    private void insertSampleTemplate() {
        String template = "<h3>Xin chào bạn,</h3>\n" +
                "<p>Đây là email được gửi tự động từ ứng dụng <b>SMTP Client Hiện Đại</b> (Bộ môn Lập Trình Mạng).</p>\n" +
                "<p>Hỗ trợ đầy đủ các tính năng nâng cao:</p>\n" +
                "<ul>\n" +
                "  <li>Giao diện hiện đại phong cách <b>Outlook Windows 11</b>.</li>\n" +
                "  <li>Bảo mật đường truyền <b>SMTPS (SSL: 465)</b> & <b>STARTTLS (587)</b>.</li>\n" +
                "  <li>MIME multipart/mixed đính kèm tệp tin đa định dạng.</li>\n" +
                "  <li>Cơ chế tự động thử lại <b>Exponential Backoff</b> khi gặp sự cố mạng tạm thời.</li>\n" +
                "</ul>\n" +
                "<p>Trân trọng,<br/><b>Đội Ngũ Phát Triển Java SMTP Client</b></p>";
        txtBodyEditor.setText(template);
    }

    private JPanel createBottomPanel() {
        JPanel bottomPanel = new JPanel(new BorderLayout(0, 4));
        bottomPanel.setBorder(new EmptyBorder(6, 14, 8, 14));

        // 1. Progress & Status
        JPanel progressPanel = new JPanel(new BorderLayout(8, 4));

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setString("Sẵn sàng");
        progressBar.setPreferredSize(new Dimension(progressBar.getPreferredSize().width, 22));
        progressPanel.add(progressBar, BorderLayout.NORTH);

        lblStatus = new JLabel("● Sẵn sàng gửi thư điện tử.");
        lblStatus.setFont(lblStatus.getFont().deriveFont(12f));
        progressPanel.add(lblStatus, BorderLayout.CENTER);

        // Retry Countdown Alert Banner (ẩn mặc định)
        retryBannerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        retryBannerPanel.setBackground(ThemeManager.getWarningBackground());
        retryBannerPanel.setBorder(new LineBorder(ThemeManager.getWarningColor(), 1, true));
        retryBannerPanel.setVisible(false);

        lblRetryText = new JLabel();
        lblRetryText.setForeground(ThemeManager.getWarningColor());
        lblRetryText.setFont(lblRetryText.getFont().deriveFont(Font.BOLD, 12f));
        retryBannerPanel.add(lblRetryText);

        progressPanel.add(retryBannerPanel, BorderLayout.SOUTH);
        bottomPanel.add(progressPanel, BorderLayout.NORTH);

        // 2. Real-time Log Console
        JPanel logSection = new JPanel(new BorderLayout(0, 4));
        JPanel logHeader = new JPanel(new BorderLayout());
        JLabel lblLogTitle = new JLabel("📋 Nhật Ký Hoạt Động Thời Gian Thực (SMTPS Activity Log):");
        lblLogTitle.setFont(lblLogTitle.getFont().deriveFont(Font.BOLD, 12f));
        logHeader.add(lblLogTitle, BorderLayout.WEST);

        JPanel logActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton btnCopyLog = new JButton("Sao Chép");
        btnCopyLog.setFont(btnCopyLog.getFont().deriveFont(11f));
        btnCopyLog.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(txtLogConsole.getText()), null);
            JOptionPane.showMessageDialog(this, "Đã sao chép toàn bộ nhật ký vào Clipboard!", "Thông Báo", JOptionPane.INFORMATION_MESSAGE);
        });

        JButton btnClearLog = new JButton("Xóa Log");
        btnClearLog.setFont(btnClearLog.getFont().deriveFont(11f));
        btnClearLog.addActionListener(e -> txtLogConsole.setText(""));

        logActions.add(btnCopyLog);
        logActions.add(btnClearLog);
        logHeader.add(logActions, BorderLayout.EAST);
        logSection.add(logHeader, BorderLayout.NORTH);

        txtLogConsole = new JTextArea();
        txtLogConsole.setEditable(false);
        txtLogConsole.setFont(new Font("Consolas", Font.PLAIN, 12));

        JScrollPane logScroll = new JScrollPane(txtLogConsole);
        logScroll.setPreferredSize(new Dimension(bottomPanel.getWidth(), 140));
        logSection.add(logScroll, BorderLayout.CENTER);

        bottomPanel.add(logSection, BorderLayout.CENTER);

        return bottomPanel;
    }

    private void setupLogListener() {
        logListener = (time, level, msg, ex, formatted) -> {
            SwingUtilities.invokeLater(() -> {
                txtLogConsole.append(formatted + "\n");
                // Giới hạn bộ đệm nhật ký tối đa 1500 dòng để chống tràn bộ nhớ
                if (txtLogConsole.getLineCount() > 1500) {
                    try {
                        int endOffset = txtLogConsole.getLineEndOffset(300);
                        txtLogConsole.replaceRange("", 0, endOffset);
                    } catch (Exception ignored) {
                    }
                }
                txtLogConsole.setCaretPosition(txtLogConsole.getDocument().getLength());
            });
        };
        LoggerUtil.addLogListener(logListener);
    }

    private void setupKeyboardShortcuts() {
        // Ctrl+Enter: Gửi Thư
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK), "sendMail");
        getRootPane().getActionMap().put("sendMail", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (btnSend.isEnabled()) {
                    performSendEmail();
                }
            }
        });

        // Ctrl+O: Đính Kèm File
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_O, KeyEvent.CTRL_DOWN_MASK), "attachFile");
        getRootPane().getActionMap().put("attachFile", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                chooseAttachments();
            }
        });

        // Ctrl+N: Soạn Thư Mới
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_N, KeyEvent.CTRL_DOWN_MASK), "newMail");
        getRootPane().getActionMap().put("newMail", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                resetForm();
            }
        });

        // Ctrl+T: Chuyển đổi Theme Sáng/Tối
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_T, KeyEvent.CTRL_DOWN_MASK), "toggleTheme");
        getRootPane().getActionMap().put("toggleTheme", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                btnToggleTheme.doClick();
            }
        });
    }

    private void updateActiveAccountBadge() {
        if (appConfig != null && appConfig.getSmtp() != null) {
            SmtpConfig smtp = appConfig.getSmtp();
            String user = smtp.getUsername() != null && !smtp.getUsername().trim().isEmpty() ? smtp.getUsername().trim() : null;
            if (user == null) {
                lblFrom.setText("Chưa cấu hình tài khoản (Nhấp 'Cài Đặt Tài Khoản' để cấu hình)");
                lblActiveAccount.setText("⚠ Chưa cấu hình tài khoản");
            } else {
                String from = smtp.getFrom() != null && !smtp.getFrom().trim().isEmpty() ? smtp.getFrom().trim() : user;
                lblFrom.setText(from + " (" + smtp.getHost() + ":" + smtp.getPort() + ")");
                String sec = smtp.isEnableSsl() ? "SMTPS:465" : "STARTTLS:587";
                lblActiveAccount.setText("👤 " + user + " [" + sec + "]");
            }
        }
    }

    private void syncFormToConfig() {
        if (appConfig != null && appConfig.getSmtp() != null) {
            SmtpConfig smtp = appConfig.getSmtp();
            smtp.setTo(txtTo.getText().trim());
            smtp.setCc(txtCc.getText().trim());
            smtp.setBcc(txtBcc.getText().trim());
            smtp.setSubject(txtSubject.getText().trim());
            smtp.setBody(txtBodyEditor.getText());
            List<String> paths = new ArrayList<>();
            for (AttachmentItem item : attachments) {
                paths.add(item.getAbsolutePath());
            }
            smtp.setAttachmentPaths(paths);
        }
    }

    private void openAccountSettings() {
        syncFormToConfig();
        AccountSettingsDialog dialog = new AccountSettingsDialog(this, appConfig, updatedConfig -> {
            this.appConfig = updatedConfig;
            updateActiveAccountBadge();
        });
        dialog.setVisible(true);
    }

    private long getTotalAttachmentsSize() {
        long totalBytes = 0;
        for (AttachmentItem item : attachments) {
            totalBytes += item.getSize();
        }
        return totalBytes;
    }

    private void chooseAttachments() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle("Chọn file đính kèm gửi qua email");

        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            File[] selectedFiles = chooser.getSelectedFiles();
            for (File file : selectedFiles) {
                AttachmentItem item = new AttachmentItem(file);

                if (!item.isValid()) {
                    if (item.isSensitive()) {
                        JOptionPane.showMessageDialog(this,
                                "Từ chối tệp tin: Không được phép đính kèm tệp tin cấu hình hoặc nhạy cảm (" + file.getName() + ")!",
                                "Cảnh Báo An Toàn", JOptionPane.WARNING_MESSAGE);
                    } else if (item.isTooLarge()) {
                        JOptionPane.showMessageDialog(this,
                                "Tệp tin '" + file.getName() + "' vượt quá giới hạn 25MB (" + item.getFormattedSize() + ")!",
                                "Dung Lượng Quá Lớn", JOptionPane.WARNING_MESSAGE);
                    } else {
                        JOptionPane.showMessageDialog(this,
                                "Tệp tin '" + file.getName() + "' không hợp lệ hoặc không có quyền đọc!",
                                "Tệp Tin Không Hợp Lệ", JOptionPane.WARNING_MESSAGE);
                    }
                    continue;
                }

                // Kiểm tra tổng dung lượng cộng dồn không vượt quá 25MB
                long currentTotal = getTotalAttachmentsSize();
                if (currentTotal + item.getSize() > EmailMessageBuilder.MAX_ATTACHMENT_SIZE) {
                    JOptionPane.showMessageDialog(this,
                            "Không thể thêm tệp '" + file.getName() + "': Tổng dung lượng đính kèm sẽ vượt quá giới hạn 25MB!",
                            "Vượt Quá Dung Lượng 25MB", JOptionPane.WARNING_MESSAGE);
                    continue;
                }

                if (!attachments.contains(item)) {
                    addAttachmentItem(item);
                }
            }
            updateAttachmentSummary();
        }
    }

    private void addAttachmentItem(AttachmentItem item) {
        attachments.add(item);
        attachmentTableModel.addRow(new Object[]{item.getName(), item.getFormattedSize(), item.getAbsolutePath()});
        updateAttachmentSummary();
    }

    private void removeSelectedAttachment() {
        int[] rows = attachmentTable.getSelectedRows();
        if (rows.length == 0) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhấp chọn file cần gỡ bỏ trong bảng!", "Chưa chọn file", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        java.util.Arrays.sort(rows);
        for (int i = rows.length - 1; i >= 0; i--) {
            int modelRow = attachmentTable.convertRowIndexToModel(rows[i]);
            attachments.remove(modelRow);
            attachmentTableModel.removeRow(modelRow);
        }
        updateAttachmentSummary();
    }

    private void clearAllAttachments() {
        attachments.clear();
        attachmentTableModel.setRowCount(0);
        updateAttachmentSummary();
    }

    private void updateAttachmentSummary() {
        long totalBytes = getTotalAttachmentsSize();
        String formattedSize;
        if (totalBytes < 1024) {
            formattedSize = totalBytes + " B";
        } else if (totalBytes < 1024 * 1024) {
            formattedSize = String.format(java.util.Locale.US, "%.1f KB", totalBytes / 1024.0);
        } else {
            formattedSize = String.format(java.util.Locale.US, "%.2f MB", totalBytes / (1024.0 * 1024.0));
        }

        lblAttachmentSummary.setText(String.format("%d tệp (%s / 25 MB)", attachments.size(), formattedSize));
        if (totalBytes > EmailMessageBuilder.MAX_ATTACHMENT_SIZE) {
            lblAttachmentSummary.setForeground(ThemeManager.getDangerColor());
        } else {
            lblAttachmentSummary.setForeground(UIManager.getColor("Label.disabledForeground"));
        }
    }

    private void resetForm() {
        txtTo.setText("");
        txtCc.setText("");
        txtBcc.setText("");
        txtSubject.setText("");
        insertSampleTemplate();
        clearAllAttachments();
        progressBar.setValue(0);
        progressBar.setString("Sẵn sàng");
        lblStatus.setText("● Đã làm mới form soạn thư.");
    }

    private void performSendEmail() {
        // 1. Kiểm tra cấu hình tài khoản
        SmtpConfig smtp = appConfig.getSmtp();
        if (smtp == null || smtp.getHost() == null || smtp.getUsername() == null || smtp.getPassword() == null
                || smtp.getUsername().trim().isEmpty() || smtp.getPassword().trim().isEmpty()) {
            int choice = JOptionPane.showConfirmDialog(this,
                    "Bạn chưa thiết lập tài khoản gửi thư (Email & App Password)!\nBạn có muốn mở hộp thoại Cài Đặt Tài Khoản ngay bây giờ?",
                    "Chưa Cấu Hình Tài Khoản", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice == JOptionPane.YES_OPTION) {
                openAccountSettings();
            }
            return;
        }

        // 2. Kiểm tra các trường người nhận & chủ đề
        String to = txtTo.getText().trim();
        if (to.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Vui lòng nhập ít nhất một địa chỉ email người nhận (To)!", "Thiếu người nhận", JOptionPane.WARNING_MESSAGE);
            txtTo.requestFocusInWindow();
            return;
        }

        String subject = txtSubject.getText().trim();
        if (subject.isEmpty()) {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Thư của bạn chưa có tiêu đề (Subject). Bạn có chắc chắn muốn gửi không?",
                    "Tiêu Đề Trống", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) {
                txtSubject.requestFocusInWindow();
                return;
            }
        }

        String cc = txtCc.getText().trim();
        String bcc = txtBcc.getText().trim();
        String body = txtBodyEditor.getText();

        // 3. Kiểm tra tính toàn vẹn và dung lượng tệp đính kèm
        if (getTotalAttachmentsSize() > EmailMessageBuilder.MAX_ATTACHMENT_SIZE) {
            JOptionPane.showMessageDialog(this,
                    "Tổng dung lượng tệp đính kèm vượt quá giới hạn 25MB! Vui lòng gỡ bớt tệp trước khi gửi.",
                    "Dung Lượng Quá Lớn", JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<File> fileList = new ArrayList<>();
        for (AttachmentItem item : attachments) {
            File f = item.getFile();
            if (!f.exists() || !f.isFile() || !f.canRead()) {
                JOptionPane.showMessageDialog(this,
                        "Tệp tin đính kèm không tồn tại hoặc không thể đọc: " + item.getName(),
                        "Lỗi Tệp Tin", JOptionPane.ERROR_MESSAGE);
                return;
            }
            fileList.add(f);
        }

        // Đồng bộ dữ liệu mới nhất vào smtp config
        smtp.setTo(to);
        smtp.setCc(cc);
        smtp.setBcc(bcc);
        smtp.setSubject(subject);
        smtp.setBody(body);

        // Khóa giao diện gửi
        setFormEnabled(false);
        progressBar.setValue(10);
        progressBar.setString("10% - Khởi tạo phiên kết nối SMTP...");
        lblStatus.setForeground(ThemeManager.getAccentColor());
        lblStatus.setText("⏳ Đang kết nối tới máy chủ SMTP: " + smtp.getHost() + ":" + smtp.getPort() + "...");

        // Khởi chạy SwingWorker gửi thư trên luồng nền (Background Thread)
        SwingWorker<Void, String> sendWorker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() throws Exception {
                SmtpClient client = new SmtpClient(smtp);

                // Gắn listener tiến trình thời gian thực
                client.setProgressListener((percent, msg) -> {
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setValue(percent);
                        progressBar.setString(percent + "% - " + msg);
                        lblStatus.setText("⏳ " + msg);
                    });
                });

                // Gắn listener đếm ngược thử lại (Exponential Backoff countdown)
                client.setRetryCountdownListener((attempt, maxRetry, delayMillis, reason) -> {
                    SwingUtilities.invokeLater(() -> startRetryCountdown(attempt, maxRetry, delayMillis, reason));
                });

                LoggerUtil.info("=== BẮT ĐẦU TIẾN TRÌNH GỬI THƯ TỪ GIAO DIỆN HIỆN ĐẠI ===");
                Session session = client.createSession();

                String from = (smtp.getFrom() != null && !smtp.getFrom().trim().isEmpty()) ? smtp.getFrom() : smtp.getUsername();
                MimeMessage message = EmailMessageBuilder.createMimeMessage(session, from, to, cc, bcc, subject, body, fileList);

                client.sendWithRetry(session, message);
                return null;
            }

            @Override
            protected void done() {
                setFormEnabled(true);
                stopRetryCountdown();

                try {
                    get();
                    progressBar.setValue(100);
                    progressBar.setString("100% - Gửi thư thành công!");
                    lblStatus.setForeground(ThemeManager.getSuccessColor());
                    lblStatus.setText("✔ Đã gửi email thành công tới: " + to);

                    JOptionPane.showMessageDialog(MailAppFrame.this,
                            "Email đã được gửi thành công!\nNgười nhận: " + to + "\nChủ đề: " + subject,
                            "Gửi Email Thành Công", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    progressBar.setValue(0);
                    progressBar.setString("Thất bại");
                    lblStatus.setForeground(ThemeManager.getDangerColor());

                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    lblStatus.setText("✖ Gửi email thất bại: " + cause.getMessage());

                    String troubleshooting = buildTroubleshootingText(cause);
                    JOptionPane.showMessageDialog(MailAppFrame.this,
                            "Không thể gửi email:\n" + cause.getMessage() + "\n\n" + troubleshooting,
                            "Gửi Email Thất Bại", JOptionPane.ERROR_MESSAGE);
                }
            }
        };

        sendWorker.execute();
    }

    private void startRetryCountdown(int attempt, int maxRetry, long delayMillis, String reason) {
        if (retryCountdownTimer != null && retryCountdownTimer.isRunning()) {
            retryCountdownTimer.stop();
        }

        retryBannerPanel.setVisible(true);
        long startTime = System.currentTimeMillis();
        long endTime = startTime + delayMillis;

        retryCountdownTimer = new Timer(100, e -> {
            long remaining = endTime - System.currentTimeMillis();
            if (remaining <= 0) {
                lblRetryText.setText(String.format("⚠ Đang tiến hành thử lại lần %d/%d...", attempt, maxRetry));
                retryCountdownTimer.stop();
            } else {
                double remainingSec = remaining / 1000.0;
                lblRetryText.setText(String.format("⚠ Gặp lỗi tạm thời: [%s]. Tự động thử lại lần %d/%d sau %.1f giây...",
                        reason != null ? reason : "Mạng gián đoạn", attempt, maxRetry, remainingSec));
            }
        });
        retryCountdownTimer.start();
    }

    private void stopRetryCountdown() {
        if (retryCountdownTimer != null && retryCountdownTimer.isRunning()) {
            retryCountdownTimer.stop();
        }
        retryBannerPanel.setVisible(false);
    }

    private void setFormEnabled(boolean enabled) {
        btnSend.setEnabled(enabled);
        btnAttach.setEnabled(enabled);
        btnSettings.setEnabled(enabled);
        btnNew.setEnabled(enabled);
        if (btnAddAttachment != null) {
            btnAddAttachment.setEnabled(enabled);
        }
        if (btnRemoveAttachment != null) {
            btnRemoveAttachment.setEnabled(enabled);
        }
        if (btnClearAttachment != null) {
            btnClearAttachment.setEnabled(enabled);
        }
        txtTo.setEnabled(enabled);
        txtCc.setEnabled(enabled);
        txtBcc.setEnabled(enabled);
        txtSubject.setEnabled(enabled);
        txtBodyEditor.setEnabled(enabled);
        attachmentTable.setEnabled(enabled);
    }

    private String buildTroubleshootingText(Throwable cause) {
        StringBuilder sb = new StringBuilder("Hướng dẫn xử lý sự cố:\n");
        String msg = cause.getMessage() != null ? cause.getMessage() : "";

        if (cause instanceof javax.mail.AuthenticationFailedException || msg.contains("535")) {
            sb.append("• Lỗi xác thực tài khoản (Mã 535):\n")
              .append("  - Nếu dùng Gmail: Bắt buộc dùng 'Mật khẩu ứng dụng' (16 ký tự).\n")
              .append("  - Kiểm tra lại Email và Mật khẩu trong nút 'Cài Đặt Tài Khoản'.\n");
        } else if (cause instanceof java.net.UnknownHostException) {
            sb.append("• Lỗi phân giải tên miền (DNS/Host):\n")
              .append("  - Máy chủ không tìm thấy địa chỉ host. Kiểm tra lại kết nối mạng Internet.\n");
        } else if (cause instanceof javax.net.ssl.SSLHandshakeException) {
            sb.append("• Lỗi bắt tay bảo mật SSL/TLS:\n")
              .append("  - Kiểm tra cổng mạng: Port 465 (bật SSL) hoặc Port 587 (bỏ SSL, dùng STARTTLS).\n");
        } else {
            sb.append("• Vui lòng kiểm tra tab 'Nhật Ký Hoạt Động' bên dưới hoặc file smtp-client.log để biết chi tiết.\n");
        }
        return sb.toString();
    }

    /**
     * Khởi chạy giao diện người dùng Desktop
     */
    public static void launchGui() {
        ThemeManager.initializeTheme();
        SwingUtilities.invokeLater(() -> {
            MailAppFrame frame = new MailAppFrame();
            frame.setVisible(true);
        });
    }
}
