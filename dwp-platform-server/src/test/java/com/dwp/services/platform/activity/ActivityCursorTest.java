package com.dwp.services.platform.activity;

import com.dwp.core.exception.BaseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class ActivityCursorTest {
    private final ActivityCursor cursor = new ActivityCursor(new ObjectMapper().findAndRegisterModules());

    @Test
    void positionPreservesMicrosecondsAndStableSnapshotAndAllowsAStartWatermark() {
        String scope = cursor.scope(7L, 8L, Set.of("APP.ACTIVITY:VIEW"), "ko", ActivityQuery.defaults());
        var start = cursor.decode(null, scope);
        assertThat(cursor.decode(cursor.encode(start, null, null), scope)).isEqualTo(start);
        OffsetDateTime time = OffsetDateTime.parse("2026-01-01T01:02:03.123456Z");
        UUID id = UUID.randomUUID();
        var after = cursor.decode(cursor.encode(start, time, id), scope);
        assertThat(after.occurredAt()).isEqualTo(time);
        assertThat(after.snapshotAt()).isEqualTo(start.snapshotAt());
        assertThat(after.id()).isEqualTo(id);
    }

    @Test
    void cursorCannotBeReusedAfterTenantUserPermissionOrFilterChanges() {
        var query = ActivityQuery.defaults();
        Set<String> permissions = Set.of("APP.ACTIVITY:VIEW", "APP.WORK:VIEW");
        String scope = cursor.scope(7L, 8L, permissions, "ko", query);
        String token = cursor.encode(cursor.decode(null, scope), OffsetDateTime.now(), UUID.randomUUID());
        for (String other : new String[] {
                cursor.scope(9L, 8L, permissions, "ko", query),
                cursor.scope(7L, 9L, permissions, "ko", query),
                cursor.scope(7L, 8L, Set.of("APP.ACTIVITY:VIEW"), "ko", query),
                cursor.scope(7L, 8L, permissions, "en", query),
                cursor.scope(7L, 8L, permissions, "ko", new ActivityQuery("AGENT", null, null,
                        null, null, null, null, null, null, null, 50, false))}) {
            assertThatThrownBy(() -> cursor.decode(token, other)).isInstanceOf(BaseException.class);
        }
        assertThatThrownBy(() -> cursor.decode("not-a-cursor", scope)).isInstanceOf(BaseException.class);
    }

    @Test
    void validatesBoundsEnumsAndDateRange() {
        assertThat(ActivityQuery.defaults().normalized().limit()).isEqualTo(50);
        for (int limit : new int[] {0, -1, 101}) {
            assertThatThrownBy(() -> new ActivityQuery(null, null, null, null, null,
                    null, null, null, null, null, limit, false).normalized()).isInstanceOf(BaseException.class);
        }
        OffsetDateTime now = OffsetDateTime.now();
        assertThatThrownBy(() -> new ActivityQuery(null, null, null, null, null,
                null, null, now, now, null, 50, false).normalized()).isInstanceOf(BaseException.class);
        assertThatThrownBy(() -> new ActivityQuery("person", null, null, null, null,
                null, null, null, null, null, 50, false).normalized()).isInstanceOf(BaseException.class);
    }
}
