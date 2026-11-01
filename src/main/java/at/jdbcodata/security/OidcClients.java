package at.jdbcodata.security;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/** The configured identity providers; Spring Boot only creates the repository when there is a registration. */
@Component
public class OidcClients {

    private final ClientRegistrationRepository repository;
    private final List<ClientRegistration> all = new ArrayList<>();

    public OidcClients(ObjectProvider<ClientRegistrationRepository> repository) {
        this.repository = repository.getIfAvailable();
        if (this.repository instanceof Iterable<?> registrations) {
            registrations.forEach(r -> all.add((ClientRegistration) r));
        }
    }

    public boolean configured() {
        return repository != null;
    }

    public ClientRegistrationRepository repository() {
        return repository;
    }

    public List<ClientRegistration> all() {
        return all;
    }

    /** Origins of the providers' sign-out pages, for the Content-Security-Policy. */
    public List<String> logoutOrigins() {
        LinkedHashSet<String> origins = new LinkedHashSet<>();
        for (ClientRegistration r : all) {
            if (r.getProviderDetails().getConfigurationMetadata().get("end_session_endpoint") instanceof String url) {
                UriComponents uri = UriComponentsBuilder.fromUriString(url).build();
                origins.add(uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort()));
            }
        }
        return List.copyOf(origins);
    }
}
