package com.dwp.services.platform.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void findingsUseAUniqueTieBreakerForStableBoundedPages() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L), eq(101)))
                .thenReturn(List.of());
        CatalogRepository repository = new CatalogRepository(jdbc, new ObjectMapper());

        repository.findings(1L, 101);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), eq(1L), eq(101));
        assertThat(sql.getValue())
                .contains("last_detected_at DESC, entity_ref, finding_code, catalog_finding_id")
                .contains("LIMIT ?");
    }
}
