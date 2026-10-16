package at.jdbcodata.identity;

/** Result of authenticating the Basic credentials of a request: either a service user or a pass-through caller. */
public record IdentityContext(BasicCredentials credentials, ServiceUser serviceUser) {

    public boolean isServiceUser() {
        return serviceUser != null;
    }

    public String caller() {
        return credentials.username();
    }
}
