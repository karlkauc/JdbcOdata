package at.jdbcodata.cache;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import at.jdbcodata.testutil.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

class GenerationPollOutageTest {

    @Test
    void metadataOutageOnlySkipsThePoll() {
        CacheGeneration generation = mock(CacheGeneration.class);
        when(generation.current()).thenReturn(1L)
                .thenThrow(new CannotGetJdbcConnectionException("metadata DB down"))
                .thenReturn(1L);
        ResultCache cache = new ResultCache(null, null, null, null, TestData.props(10, 10, ""), generation);

        cache.followOtherInstances();
        assertThatCode(cache::followOtherInstances).doesNotThrowAnyException();
        cache.followOtherInstances();

        verify(generation, times(3)).current();
    }
}
