package com.dwp.services.synapsex.util;

import com.dwp.core.exception.BaseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A4) Drill-down 공통 Query Param 파싱 단위 테스트
 */
class DrillDownParamUtilTest {

    @Nested
    @DisplayName("validateRangeExclusive")
    class ValidateRangeExclusive {
        @Test
        void range와_from_to_동시_제공_시_400() {
            assertThatThrownBy(() ->
                    DrillDownParamUtil.validateRangeExclusive("24h", Instant.now().minusSeconds(3600), Instant.now()))
                    .isInstanceOf(BaseException.class)
                    .hasMessageContaining("range와 from/to를 동시에 지정할 수 없습니다");
        }

        @Test
        void range만_있으면_통과() {
            DrillDownParamUtil.validateRangeExclusive("24h", null, null);
        }

        @Test
        void from만_있으면_통과() {
            DrillDownParamUtil.validateRangeExclusive(null, Instant.now(), null);
        }

        @Test
        void 둘_다_없으면_통과() {
            DrillDownParamUtil.validateRangeExclusive(null, null, null);
        }
    }

    @Nested
    @DisplayName("parseMulti")
    class ParseMulti {
        @Test
        void comma_구분_다중값() {
            assertThat(DrillDownParamUtil.parseMulti("HIGH,CRITICAL")).containsExactly("HIGH", "CRITICAL");
        }

        @Test
        void null_또는_빈문자열_빈리스트() {
            assertThat(DrillDownParamUtil.parseMulti(null)).isEmpty();
            assertThat(DrillDownParamUtil.parseMulti("")).isEmpty();
        }
    }

    @Nested
    @DisplayName("parseSortAndOrder")
    class ParseSortAndOrder {
        @Test
        void sort_comma_dir_형식() {
            String[] r = DrillDownParamUtil.parseSortAndOrder("createdAt,desc", null, "createdAt", "desc");
            assertThat(r[0]).isEqualTo("createdAt");
            assertThat(r[1]).isEqualTo("desc");
        }

        @Test
        void sort_order_분리_형식() {
            String[] r = DrillDownParamUtil.parseSortAndOrder("createdAt", "asc", "createdAt", "desc");
            assertThat(r[0]).isEqualTo("createdAt");
            assertThat(r[1]).isEqualTo("asc");
        }

        @Test
        void 기본값_적용() {
            String[] r = DrillDownParamUtil.parseSortAndOrder(null, null, "createdAt", "desc");
            assertThat(r[0]).isEqualTo("createdAt");
            assertThat(r[1]).isEqualTo("desc");
        }
    }

    @Nested
    @DisplayName("resolve")
    class Resolve {
        @Test
        void from_to_우선() {
            Instant from = Instant.now().minusSeconds(7200);
            Instant to = Instant.now();
            var tr = DrillDownParamUtil.resolve("24h", from, to);
            assertThat(tr.from()).isEqualTo(from);
            assertThat(tr.to()).isEqualTo(to);
        }

        @Test
        void range만_있으면_계산() {
            var tr = DrillDownParamUtil.resolve("1h", null, null);
            assertThat(tr.to()).isBeforeOrEqualTo(Instant.now().plusSeconds(1));
            assertThat(tr.from()).isAfterOrEqualTo(Instant.now().minusSeconds(3700));
        }
    }
}
