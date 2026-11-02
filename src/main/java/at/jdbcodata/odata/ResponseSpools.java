package at.jdbcodata.odata;

import at.jdbcodata.datasource.TooManyExecutionsException;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limits for response bodies: the size of one response (NFR-13) and the disk space of all of them together. Status and
 * ETag of a response are only known at its end, so a body is written to a {@link ResponseSpool} first and only then
 * sent. A failing or oversized result still gets a proper error instead of a broken 200.
 */
@Component
public class ResponseSpools {

    private static final long MB = 1024 * 1024;
    /** Larger bodies go to disk. */
    static final int MEMORY_BYTES = 1024 * 1024;

    private final long maxResponseBytes;
    private final long maxDiskBytes;
    private final AtomicLong diskBytes = new AtomicLong();

    public ResponseSpools(@Value("${jdbcodata.odata.max-response-mb:256}") long maxResponseMb,
                          @Value("${jdbcodata.odata.spool-max-mb:2048}") long maxDiskMb) {
        this.maxResponseBytes = maxResponseMb * MB;
        this.maxDiskBytes = maxDiskMb * MB;
    }

    ResponseSpool open() {
        return new ResponseSpool(this);
    }

    /** @throws ResponseSpool.TooLarge above the limit of one response; 0 means no limit */
    void checkSize(long bytes) {
        if (maxResponseBytes > 0 && bytes > maxResponseBytes) throw new ResponseSpool.TooLarge(maxResponseBytes / MB);
    }

    /** @throws TooManyExecutionsException the spool directory holds too much at the moment */
    void reserveDisk(long bytes) {
        if (diskBytes.addAndGet(bytes) > maxDiskBytes) {
            diskBytes.addAndGet(-bytes);
            throw new TooManyExecutionsException("The service is busy with large responses. Try again shortly.");
        }
    }

    void releaseDisk(long bytes) {
        diskBytes.addAndGet(-bytes);
    }

    public long diskBytes() {
        return diskBytes.get();
    }
}
