package at.jdbcodata.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ServiceUser(
        UUID id,
        String username,
        String displayName,
        boolean forcePassthrough,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        Instant lastLoginAt,
        List<ServiceGroup> groups) {

    public boolean inGroup(UUID groupId) {
        return groups.stream().anyMatch(g -> g.id().equals(groupId));
    }
}
