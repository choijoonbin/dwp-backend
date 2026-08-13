package com.dwp.services.auth.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoleMemberRepositoryQueryContractTest {

    @Test
    void nativeQueriesDoNotUsePostgresqlGrantKeywordAsAnAlias() {
        List<Query> nativeQueries = Arrays.stream(RoleMemberRepository.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(Query.class))
                .filter(java.util.Objects::nonNull)
                .filter(Query::nativeQuery)
                .toList();

        assertThat(nativeQueries).hasSize(2);
        nativeQueries.stream()
                .map(Query::value)
                .forEach(query -> assertThat(query)
                        .doesNotContainPattern("(?i)\\b(?:FROM|JOIN)\\s+[a-z0-9_.]+\\s+grant\\b")
                        .doesNotContainPattern("(?i)\\bgrant\\."));
    }
}
