package com.dwp.services.platform.productivity;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductivityRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    void itemsBindsNullableResourceKindWithExplicitSqlType() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ProductivityRepository repository = new ProductivityRepository(jdbc, new ObjectMapper());
        when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(0L);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        ProductivityRepository.ItemResult result = repository.items(7L, 11L, null, 0, 20);

        assertThat(result.content()).isEmpty();
        assertThat(result.total()).isZero();

        var countParameters = org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).queryForObject(anyString(), countParameters.capture(), eq(Long.class));
        assertThat(countParameters.getValue().getValue("resourceKind")).isNull();
        assertThat(countParameters.getValue().getSqlType("resourceKind"))
                .isEqualTo(Types.VARCHAR);

        var itemParameters = org.mockito.ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).query(anyString(), itemParameters.capture(), any(RowMapper.class));
        assertThat(itemParameters.getValue().getSqlType("resourceKind"))
                .isEqualTo(Types.VARCHAR);
    }
}
