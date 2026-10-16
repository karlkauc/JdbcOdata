package at.jdbcodata.cache;

import at.jdbcodata.query.ResultColumn;
import at.jdbcodata.query.ResultTable;
import at.jdbcodata.query.TypeMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;

/** Compact, gzip-compressed binary form of a {@link ResultTable} for the persistent cache. */
public final class ResultTableCodec {

    private static final int VERSION = 1;
    private static final byte T_NULL = 0, T_STRING = 1, T_INT = 2, T_LONG = 3, T_DECIMAL = 4, T_DOUBLE = 5,
            T_BOOLEAN = 6, T_DATE = 7, T_TIME = 8, T_TIMESTAMP = 9, T_BYTES = 10, T_UUID = 11;

    private ResultTableCodec() {
    }

    public static byte[] encode(ResultTable table) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(VERSION);
            out.writeInt(table.columns().size());
            for (ResultColumn c : table.columns()) {
                out.writeUTF(c.name());
                out.writeInt(c.jdbcType());
                out.writeUTF(c.jdbcTypeName() == null ? "" : c.jdbcTypeName());
                out.writeInt(c.precision());
                out.writeInt(c.scale());
                out.writeBoolean(c.nullable());
                out.writeUTF(c.mapping().kind().name());
                writeNullableInt(out, c.mapping().precision());
                writeNullableInt(out, c.mapping().scale());
            }
            out.writeInt(table.rows().size());
            for (Object[] row : table.rows()) {
                for (Object v : row) {
                    writeValue(out, v);
                }
            }
            out.writeBoolean(table.truncated());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot encode result", e);
        }
        return bytes.toByteArray();
    }

    public static ResultTable decode(byte[] payload) {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(payload)))) {
            int version = in.readInt();
            if (version != VERSION) throw new IllegalStateException("Unsupported cache payload version " + version);
            int n = in.readInt();
            List<ResultColumn> columns = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                String name = in.readUTF();
                int jdbcType = in.readInt();
                String typeName = in.readUTF();
                int precision = in.readInt();
                int scale = in.readInt();
                boolean nullable = in.readBoolean();
                EdmPrimitiveTypeKind kind = EdmPrimitiveTypeKind.valueOf(in.readUTF());
                Integer mp = readNullableInt(in);
                Integer ms = readNullableInt(in);
                columns.add(new ResultColumn(name, jdbcType, typeName.isEmpty() ? null : typeName, precision, scale,
                        nullable, new TypeMapper.EdmMapping(kind, mp, ms)));
            }
            int rows = in.readInt();
            List<Object[]> data = new ArrayList<>(rows);
            for (int r = 0; r < rows; r++) {
                Object[] row = new Object[n];
                for (int c = 0; c < n; c++) {
                    row[c] = readValue(in);
                }
                data.add(row);
            }
            boolean truncated = in.readBoolean();
            return new ResultTable(List.copyOf(columns), data, truncated);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot decode cached result", e);
        }
    }

    private static void writeValue(DataOutputStream out, Object v) throws IOException {
        switch (v) {
            case null -> out.writeByte(T_NULL);
            case String s -> { out.writeByte(T_STRING); writeString(out, s); }
            case Integer i -> { out.writeByte(T_INT); out.writeInt(i); }
            case Long l -> { out.writeByte(T_LONG); out.writeLong(l); }
            case BigDecimal bd -> { out.writeByte(T_DECIMAL); writeString(out, bd.toString()); }
            case Double d -> { out.writeByte(T_DOUBLE); out.writeDouble(d); }
            case Boolean b -> { out.writeByte(T_BOOLEAN); out.writeBoolean(b); }
            case Timestamp ts -> { out.writeByte(T_TIMESTAMP); out.writeLong(ts.getTime()); out.writeInt(ts.getNanos()); }
            case Date d -> { out.writeByte(T_DATE); out.writeLong(d.getTime()); }
            case Time t -> { out.writeByte(T_TIME); out.writeLong(t.getTime()); }
            case byte[] b -> { out.writeByte(T_BYTES); out.writeInt(b.length); out.write(b); }
            case UUID u -> { out.writeByte(T_UUID); out.writeLong(u.getMostSignificantBits()); out.writeLong(u.getLeastSignificantBits()); }
            default -> { out.writeByte(T_STRING); writeString(out, v.toString()); }
        }
    }

    private static Object readValue(DataInputStream in) throws IOException {
        byte tag = in.readByte();
        return switch (tag) {
            case T_NULL -> null;
            case T_STRING -> readString(in);
            case T_INT -> in.readInt();
            case T_LONG -> in.readLong();
            case T_DECIMAL -> new BigDecimal(readString(in));
            case T_DOUBLE -> in.readDouble();
            case T_BOOLEAN -> in.readBoolean();
            case T_TIMESTAMP -> {
                Timestamp ts = new Timestamp(in.readLong());
                ts.setNanos(in.readInt());
                yield ts;
            }
            case T_DATE -> new Date(in.readLong());
            case T_TIME -> new Time(in.readLong());
            case T_BYTES -> {
                byte[] b = new byte[in.readInt()];
                in.readFully(b);
                yield b;
            }
            case T_UUID -> new UUID(in.readLong(), in.readLong());
            default -> throw new IOException("Unknown value tag " + tag);
        };
    }

    /** writeUTF is limited to 64 KB; CLOB values can be larger. */
    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readString(DataInputStream in) throws IOException {
        byte[] b = new byte[in.readInt()];
        in.readFully(b);
        return new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void writeNullableInt(DataOutputStream out, Integer v) throws IOException {
        out.writeBoolean(v != null);
        if (v != null) out.writeInt(v);
    }

    private static Integer readNullableInt(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readInt() : null;
    }
}
