package at.jdbcodata.identity;

/** The caller named an existing service user but the service password did not match. Maps to HTTP 401. */
public class ServiceAuthenticationException extends RuntimeException {
    public ServiceAuthenticationException(String username) {
        super("Service login for '" + username + "' failed");
    }
}
