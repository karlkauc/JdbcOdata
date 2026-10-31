package at.jdbcodata.usage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The User-Agents are the ones seen in the access log of the public demo. */
class UsageClassifierTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Microsoft.Data.Mashup (https://go.microsoft.com/fwlink/?LinkID=304225)|powerquery|desktop",
            "python-httpx/0.28.1|odata_viewer|unknown",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/156.0.8078.4 Safari/537.36|browser|desktop",
            "Mozilla/5.0 (Linux; Android 6.0; Nexus 5 Build/MRA58N) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36|browser|mobile",
            "curl/8.18.0|script|unknown",
            "TLM-Audit-Scanner/2.0|other|bot",
            "Mozilla/5.0 (l9scan/2.0.833313e2032323e26373e2434313; +https://leakix.net)|other|bot",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 HeadlessChrome/156.0.8078.4 Safari/537.36|other|bot",
            "something-else/1.0|other|unknown"})
    void sortsClients(String userAgent, String client, String device) {
        assertThat(UsageClassifier.client(userAgent)).isEqualTo(client);
        assertThat(UsageClassifier.device(userAgent)).isEqualTo(device);
    }

    @Test
    void missingUserAgent() {
        assertThat(UsageClassifier.client(null)).isEqualTo("other");
        assertThat(UsageClassifier.device(null)).isEqualTo("unknown");
    }
}
