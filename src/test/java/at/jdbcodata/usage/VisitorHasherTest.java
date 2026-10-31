package at.jdbcodata.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class VisitorHasherTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 31);
    private final VisitorHasher hasher = new VisitorHasher("secret");

    @Test
    void sameVisitorCountsOncePerDay() {
        assertThat(hasher.hash("10.0.0.1", "curl", DAY)).hasSize(32).isEqualTo(hasher.hash("10.0.0.1", "curl", DAY))
                .doesNotContain("10.0.0.1");
    }

    @Test
    void cannotBeFollowedAcrossDaysOrInstances() {
        String hash = hasher.hash("10.0.0.1", "curl", DAY);
        assertThat(hasher.hash("10.0.0.1", "curl", DAY.plusDays(1))).isNotEqualTo(hash);
        assertThat(hasher.hash("10.0.0.2", "curl", DAY)).isNotEqualTo(hash);
        assertThat(hasher.hash("10.0.0.1", "wget", DAY)).isNotEqualTo(hash);
        assertThat(new VisitorHasher("other").hash("10.0.0.1", "curl", DAY)).isNotEqualTo(hash);
    }
}
