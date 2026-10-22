package com.ltm.mail;

import com.ltm.mail.config.AppConfig;
import com.ltm.mail.config.ConfigLoader;
import com.ltm.mail.config.SmtpConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigLoaderTest {

    @Test
    @DisplayName("Nạp thành công file appsettings.json hoặc appsettings.example.json")
    void testLoadConfigSuccess() throws Exception {
        AppConfig config = ConfigLoader.loadConfig("appsettings.example.json");
        assertNotNull(config);
        assertNotNull(config.getSmtp());

        SmtpConfig smtp = config.getSmtp();
        assertEquals("smtp.gmail.com", smtp.getHost());
        assertEquals(465, smtp.getPort());
        assertTrue(smtp.isEnableSsl());
        assertEquals("LOGIN", smtp.getAuthType());
        assertNotNull(smtp.getRetry());
        assertEquals(4, smtp.getRetry().getMaxRetry());
    }

    @Test
    @DisplayName("Báo lỗi khi đường dẫn file cấu hình không tồn tại")
    void testLoadConfigNotFound() {
        assertThrows(IllegalStateException.class, () -> {
            ConfigLoader.loadConfig("non_existent_config_file_12345.json");
        });
    }
}
