package at.jdbcodata.identity;

/**
 * Result of authenticating one OData request: a service user, a pass-through caller, or — when the operator
 * configured {@code jdbcodata.odata.anonymous-user} — the anonymous service user for a request without credentials.
 *
 * @param credentials the Basic credentials of the request; {@code null} for anonymous requests and API keys
 * @param serviceUser the matched service user; {@code null} for pass-through callers
 * @param keyCaller   the name of the API key that was used; otherwise {@code null}
 */
public record IdentityContext(BasicCredentials credentials, ServiceUser serviceUser, String keyCaller) {

    public static final String ANONYMOUS = "anonymous";

    public IdentityContext(BasicCredentials credentials, ServiceUser serviceUser) {
        this(credentials, serviceUser, null);
    }

    /** Context of a request with an API key. Like an anonymous request, it has no password to pass on. */
    public static IdentityContext apiKey(String keyCaller, ServiceUser serviceUser) {
        return new IdentityContext(null, serviceUser, keyCaller);
    }

    /** Context of a request without credentials, running as the configured anonymous service user. */
    public static IdentityContext anonymous(ServiceUser serviceUser) {
        return new IdentityContext(null, serviceUser);
    }

    public boolean isServiceUser() {
        return serviceUser != null;
    }

    /** True for every request without a password: it can only use stored logins and technical accounts. */
    public boolean isAnonymous() {
        return credentials == null;
    }

    /** Name recorded in the execution log and statistics. */
    public String caller() {
        if (keyCaller != null) return keyCaller;
        return credentials == null ? ANONYMOUS : credentials.username();
    }
}
