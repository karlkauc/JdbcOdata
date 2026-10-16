package at.jdbcodata.query;

import java.time.Instant;
import java.util.UUID;

public record QueryGrant(UUID id, UUID queryId, PrincipalType principalType, UUID principalId, String principalName,
                         String grantedBy, Instant grantedAt) {
}
