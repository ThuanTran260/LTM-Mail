package com.ltm.mail.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.ltm.mail.util.LoggerUtil;

import javax.swing.*;
import java.awt.*;

/**
 * Quản lý giao diện hiện đại phong cách Windows 11 / Modern Outlook sử dụng FlatLaf.
 * Hỗ trợ chuyển đổi nhanh chóng giữa Chế độ Sáng (Light Mode) và Chế độ Tối (Dark Mode).
 */
public class ThemeManager {

    private static boolean isDarkMode = false;

    /**
     * Khởi tạo giao diện FlatLaf với cấu hình bo góc Windows 11 hiện đại
     */
    public static void initializeTheme() {
        try {
            // Cấu hình bo góc Windows 11 hiện đại
            UIManager.put("Button.arc", 8);
            UIManager.put("Component.arc", 8);
            UIManager.put("CheckBox.arc", 6);
            UIManager.put("ProgressBar.arc", 8);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("ScrollBar.thumbArc", 8);
            UIManager.put("TabbedPane.showTabSeparators", true);
            UIManager.put("TabbedPane.tabSeparatorsFullHeight", true);
            UIManager.put("TabbedPane.selectedBackground", new Color(0, 120, 212, 30));

            // Thiết lập font chữ Segoe UI mượt mà trên Windows
            Font modernFont = new Font("Segoe UI", Font.PLAIN, 13);
            UIManager.put("defaultFont", modernFont);

            if (isDarkMode) {
                FlatDarkLaf.setup();
            } else {
                FlatLightLaf.setup();
            }
        } catch (Exception e) {
            LoggerUtil.warn("Không thể thiết lập theme FlatLaf, sử dụng System Look and Feel: " + e.getMessage());
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Chuyển đổi giữa chế độ Sáng và Tối trong thời gian thực
     */
    public static void toggleTheme(Component rootComponent) {
        setDarkMode(!isDarkMode, rootComponent);
    }

    /**
     * Thiết lập chế độ Sáng/Tối cụ thể
     */
    public static void setDarkMode(boolean dark, Component rootComponent) {
        isDarkMode = dark;
        try {
            if (isDarkMode) {
                FlatDarkLaf.setup();
            } else {
                FlatLightLaf.setup();
            }
            FlatLaf.updateUI();

            if (rootComponent != null) {
                SwingUtilities.updateComponentTreeUI(rootComponent);
                rootComponent.repaint();
            }
            for (Window window : Window.getWindows()) {
                SwingUtilities.updateComponentTreeUI(window);
                window.repaint();
            }
        } catch (Exception e) {
            LoggerUtil.warn("Lỗi khi chuyển đổi theme: " + e.getMessage());
        }
    }

    public static boolean isDarkMode() {
        return isDarkMode;
    }

    /**
     * Màu sắc nhấn (Accent Color) chuẩn phong cách Outlook Blue
     */
    public static Color getAccentColor() {
        return new Color(0, 120, 212); // #0078D4 Outlook Modern Blue
    }

    public static Color getSuccessColor() {
        return new Color(16, 137, 62); // #10893E Outlook Green
    }

    public static Color getWarningColor() {
        return new Color(216, 59, 1); // #D83B01 Orange-Red
    }

    public static Color getDangerColor() {
        return new Color(168, 0, 0); // #A80000 Dark Red
    }

    public static Color getWarningBackground() {
        return isDarkMode ? new Color(60, 45, 15) : new Color(255, 244, 206);
    }
}
