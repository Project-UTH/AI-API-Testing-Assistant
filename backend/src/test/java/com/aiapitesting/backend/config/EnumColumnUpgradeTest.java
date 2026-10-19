package com.aiapitesting.backend.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnumColumnUpgradeTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    @Test
    void nativeEnumColumns_areConvertedToVarchar_keepingNullabilityAndDefault() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("TABLE_NAME", "projects", "COLUMN_NAME", "target_auth_type", "IS_NULLABLE", "NO", "COLUMN_DEFAULT", "NONE"),
                Map.of("TABLE_NAME", "endpoints", "COLUMN_NAME", "scope_kind", "IS_NULLABLE", "YES")));

        new EnumColumnUpgrade(jdbc).run(null);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, org.mockito.Mockito.times(2)).execute(sql.capture());
        assertThat(sql.getAllValues().get(0)).isEqualTo("ALTER TABLE `projects` MODIFY `target_auth_type` VARCHAR(40) NOT NULL DEFAULT 'NONE'");
        assertThat(sql.getAllValues().get(1)).isEqualTo("ALTER TABLE `endpoints` MODIFY `scope_kind` VARCHAR(40) NULL");
    }

    @Test
    void unsafeIdentifiersAreSkipped_andFailuresNeverBlockStartup() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("TABLE_NAME", "bad`; DROP TABLE x", "COLUMN_NAME", "c", "IS_NULLABLE", "NO")));
        new EnumColumnUpgrade(jdbc).run(null);
        verify(jdbc, never()).execute(anyString());

        JdbcTemplate failing = mock(JdbcTemplate.class);
        when(failing.queryForList(anyString())).thenThrow(new RuntimeException("not mysql"));
        new EnumColumnUpgrade(failing).run(null); // không ném ngoại lệ
    }
}
