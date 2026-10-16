package at.jdbcodata.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class BasicCredentialsTest {

    @Test
    void parsesHeader() {
        String header = "Basic " + Base64.getEncoder().encodeToString("maria:se:cret".getBytes());
        var creds = BasicCredentials.fromHeader(header).orElseThrow();
        assertThat(creds.username()).isEqualTo("maria");
        assertThat(creds.password()).isEqualTo("se:cret");
        assertThat(creds.toString()).doesNotContain("se:cret");
    }

    @Test
    void rejectsMissingOrOtherSchemes() {
        assertThat(BasicCredentials.fromHeader(null)).isEmpty();
        assertThat(BasicCredentials.fromHeader("Bearer abc")).isEmpty();
        assertThat(BasicCredentials.fromHeader("Basic !!!")).isEmpty();
        assertThat(BasicCredentials.fromHeader("Basic " + Base64.getEncoder().encodeToString("nocolon".getBytes()))).isEmpty();
    }
}
