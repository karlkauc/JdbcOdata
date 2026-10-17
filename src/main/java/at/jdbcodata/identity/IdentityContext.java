package at.jdbcodata.identity;

/**
 * Result of authenticating one OData request: a service user, a pass-through caller, or — when the operator
 * configured {@code jdbcodata.odata.anonymous-user} — the anonymous service user for a request without credentials.
 *
 * @param credentials the Basic credentials of the request; {@code null} for anonymous requests
 * @param serviceUser the matched service user; {@code null} for pass-through callers
 */
public record IdentityContext(BasicCredentials credentials, ServiceUser serviceUser) {

    public static final String ANONYMOUS = "anonymous";

    /** Context of a request without credentials, running as the configured anonymous service user. */
    public static IdentityContext anonymous(ServiceUser serviceUser) {
        return new IdentityContext(null, serviceUser);
    }

    public boolean isServiceUser() {
        return serviceUser != null;
    }

    public boolean isAnonymous() {
        return credentials == null;
    }

    /** Name recorded in the execution log and statistics. */
    public String caller() {
        return credentials == null ? ANONYMOUS : credentials.username();
    }
}
