package at.jdbcodata.odata;

import at.jdbcodata.crypto.CredentialVault;
import at.jdbcodata.odata.AsyncRequests.Result;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Asynchronous requests in the metadata DB. Headers and body are stored as one compressed and encrypted value: a client
 * can ask for this with any query, also with a sensitive one that has the result cache switched off.
 */
@Component
class AsyncResultStore {

    /** {@code result} is null while the request runs. */
    record Stored(Instant created, Instant heartbeat, Result result) {
    }

    private final JdbcTemplate jdbc;
    private final CredentialVault vault;

    AsyncResultStore(JdbcTemplate jdbc, CredentialVault vault) {
        this.jdbc = jdbc;
        this.vault = vault;
    }

    void start(String id, String owner, Instant now) {
        jdbc.update("INSERT INTO async_request (id, owner, created_at, heartbeat_at) VALUES (?, ?, ?, ?)",
                id, owner, timestamp(now), timestamp(now));
    }

    /** Does nothing when the request has been cancelled in the meantime. */
    void finish(String id, Result result) {
        String payload = vault.encrypt(Base64.getEncoder().encodeToString(encode(result)), id);
        jdbc.update("UPDATE async_request SET http_status = ?, key_id = ?, payload = ? WHERE id = ?",
                result.status(), vault.keyId(), payload, id);
    }

    Stored find(String id, String owner) {
        List<Stored> found = jdbc.query("SELECT created_at, heartbeat_at, http_status, key_id, payload FROM async_request WHERE id = ? AND owner = ?",
                (rs, n) -> {
                    int status = rs.getInt("http_status");
                    boolean finished = !rs.wasNull();
                    return new Stored(rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("heartbeat_at", OffsetDateTime.class).toInstant(),
                            finished ? decode(status, Base64.getDecoder().decode(
                                    vault.decrypt(rs.getString("payload"), rs.getString("key_id"), id))) : null);
                }, id, owner);
        return found.isEmpty() ? null : found.getFirst();
    }

    boolean delete(String id, String owner) {
        return jdbc.update("DELETE FROM async_request WHERE id = ? AND owner = ?", id, owner) > 0;
    }

    void heartbeat(Collection<String> ids, Instant now) {
        jdbc.batchUpdate("UPDATE async_request SET heartbeat_at = ? WHERE id = ?",
                ids.stream().map(id -> new Object[] {timestamp(now), id}).toList());
    }

    long running(String owner, Instant aliveSince) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM async_request WHERE owner = ? AND http_status IS NULL AND heartbeat_at >= ?",
                Long.class, owner, timestamp(aliveSince));
        return count == null ? 0 : count;
    }

    /** Keeps the newest {@code max} requests. */
    void trim(int max) {
        jdbc.update("DELETE FROM async_request WHERE id IN (SELECT id FROM async_request ORDER BY created_at DESC OFFSET ?)", max);
    }

    /** Removes what was created before {@code createdBefore}, unless it still runs on a living instance. */
    int deleteExpired(Instant createdBefore, Instant aliveSince) {
        return jdbc.update("DELETE FROM async_request WHERE created_at < ? AND (http_status IS NOT NULL OR heartbeat_at < ?)",
                timestamp(createdBefore), timestamp(aliveSince));
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static byte[] encode(Result result) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(result.headers().size());
            for (Map.Entry<String, List<String>> header : result.headers().entrySet()) {
                out.writeUTF(header.getKey());
                out.writeInt(header.getValue().size());
                for (String value : header.getValue()) out.writeUTF(value);
            }
            out.write(result.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static Result decode(int status, byte[] payload) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(payload)))) {
            Map<String, List<String>> headers = new LinkedHashMap<>();
            int names = in.readInt();
            for (int i = 0; i < names; i++) {
                String name = in.readUTF();
                int count = in.readInt();
                List<String> values = new ArrayList<>(count);
                for (int v = 0; v < count; v++) values.add(in.readUTF());
                headers.put(name, values);
            }
            return new Result(status, headers, in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
