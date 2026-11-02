package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.jdbcodata.datasource.TooManyExecutionsException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ResponseSpoolTest {

    private static final int MB = 1024 * 1024;

    private final ResponseSpools spools = new ResponseSpools(4, 6);

    @Test
    void smallBodyStaysInMemory() throws Exception {
        ResponseSpool spool = spools.open();
        spool.write("hello".getBytes(StandardCharsets.UTF_8));
        spool.close();

        assertThat(spool.file()).isNull();
        assertThat(spool.etag()).isEqualTo(ResponseETag.of("hello".getBytes(StandardCharsets.UTF_8)));
        assertThat(spool.content().readAllBytes()).asString(StandardCharsets.UTF_8).isEqualTo("hello");
        assertThat(spools.diskBytes()).isZero();
        spool.release();
    }

    @Test
    void largeBodyGoesToAnEncryptedFileAndComesBackUnchanged() throws Exception {
        byte[] body = body(3 * MB);
        ResponseSpool spool = spools.open();
        for (int i = 0; i < body.length; i += 1000) spool.write(body, i, Math.min(1000, body.length - i));
        spool.close();

        Path file = spool.file();
        assertThat(file).exists();
        assertThat(Files.size(file)).isEqualTo(body.length);
        assertThat(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)).doesNotContain("confidential");
        assertThat(spool.content().readAllBytes()).isEqualTo(body);
        assertThat(spool.content().readAllBytes()).as("readable twice").isEqualTo(body);
        assertThat(spool.etag()).isEqualTo(ResponseETag.of(body));
        assertThat(spools.diskBytes()).isEqualTo(body.length);

        spool.release();
        spool.release();
        assertThat(file).doesNotExist();
        assertThat(spools.diskBytes()).isZero();
    }

    @Test
    void refusesABodyAboveTheLimit() throws Exception {
        ResponseSpool spool = spools.open();
        spool.write(new byte[4 * MB]);
        assertThatThrownBy(() -> spool.write(1)).isInstanceOf(ResponseSpool.TooLarge.class).hasMessageContaining("4 MB");
        spool.release();
        assertThat(spools.diskBytes()).isZero();
    }

    @Test
    void noLimitWithZero() throws Exception {
        ResponseSpool spool = new ResponseSpools(0, 6).open();
        spool.write(new byte[5 * MB]);
        spool.release();
    }

    @Test
    void refusesWhenTheDiskBudgetIsUsedUp() throws Exception {
        ResponseSpool first = spools.open();
        ResponseSpool second = spools.open();
        ResponseSpool third = spools.open();
        first.write(new byte[3 * MB]);
        second.write(new byte[2 * MB]);

        assertThatThrownBy(() -> third.write(new byte[2 * MB])).isInstanceOf(TooManyExecutionsException.class);

        first.release();
        third.write(new byte[2 * MB]);
        second.release();
        third.release();
        assertThat(spools.diskBytes()).isZero();
    }

    private static byte[] body(int size) {
        byte[] marker = "confidential ".getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[size];
        for (int i = 0; i < size; i++) body[i] = marker[i % marker.length];
        return body;
    }
}
