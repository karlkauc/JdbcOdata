package at.jdbcodata.odata;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;

/**
 * Caps the request body of OData calls ({@code $batch}, asynchronous requests read it into memory). A declared
 * Content-Length above the limit is rejected up front by the servlet; this wrapper also stops chunked bodies that
 * grow beyond it while being read.
 */
final class BoundedRequest extends HttpServletRequestWrapper {

    static final long MAX_BODY_BYTES = 10L * 1024 * 1024;

    private ServletInputStream stream;

    BoundedRequest(HttpServletRequest request) {
        super(request);
    }

    static boolean declaredTooLarge(HttpServletRequest request) {
        return request.getContentLengthLong() > MAX_BODY_BYTES;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (stream == null) stream = new Limited(super.getInputStream());
        return stream;
    }

    private static final class Limited extends ServletInputStream {
        private final ServletInputStream in;
        private long read;

        Limited(ServletInputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = in.read(buf, off, len);
            if (n > 0) count(n);
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > MAX_BODY_BYTES) throw new IOException("Request body larger than " + MAX_BODY_BYTES + " bytes");
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
