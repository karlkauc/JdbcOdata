package at.jdbcodata.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class TracingTest {

    @Test
    void odataPathsKeepOnlyTheEntitySet() {
        assertThat(TracedUrls.shorten("/odata/Customers('Müller')", "")).isEqualTo("/odata/Customers");
        assertThat(TracedUrls.shorten("/odata/Customers%28'x'%29", "")).isEqualTo("/odata/Customers");
        assertThat(TracedUrls.shorten("/odata/Customers/4711/Orders", "")).isEqualTo("/odata/Customers");
        assertThat(TracedUrls.shorten("/odata/Revenue(year=2026)", "")).isEqualTo("/odata/Revenue");
        assertThat(TracedUrls.shorten("/odata/$metadata", "")).isEqualTo("/odata/$metadata");
        assertThat(TracedUrls.shorten("/odata/", "")).isEqualTo("/odata/");
        assertThat(TracedUrls.shorten("/odata", "")).isEqualTo("/odata");
        assertThat(TracedUrls.shorten("/app/odata/Customers(1)", "/app")).isEqualTo("/app/odata/Customers");
    }

    @Test
    void otherPathsStayAsTheyAre() {
        assertThat(TracedUrls.shorten("/admin/queries/42", "")).isEqualTo("/admin/queries/42");
        assertThat(TracedUrls.shorten("/odatax/Customers(1)", "")).isEqualTo("/odatax/Customers(1)");
        assertThat(TracedUrls.shorten(null, "")).isEqualTo("UNKNOWN");
    }

    @Test
    void theDatabaseSystemComesFromTheUrlWithoutHostOrDatabase() {
        assertThat(Tracing.system("jdbc:postgresql://db:5432/sales")).isEqualTo("postgresql");
        assertThat(Tracing.system("jdbc:oracle:thin:@//db:1521/FREE")).isEqualTo("oracle");
        assertThat(Tracing.system("JDBC:MariaDB://db/x")).isEqualTo("mariadb");
        assertThat(Tracing.system("nonsense")).isEqualTo("other");
        assertThat(Tracing.system(null)).isEqualTo("other");
    }

    @Test
    @SuppressWarnings("unchecked")
    void switchedOffItDoesNotTouchOpenTelemetry() {
        ObjectProvider<OpenTelemetry> provider = mock(ObjectProvider.class);

        new Tracing(provider, false);

        verifyNoInteractions(provider);
    }
}
