import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Cliente mínimo para guardar los datos del experimento en PostgreSQL (JDBC directo).
 *
 * <p>Garantía principal: ningún dato se pierde. Cada registro se escribe primero como
 * archivo en {@code experiment/db_pending/} y solo se borra cuando PostgreSQL confirma
 * la inserción. Si no hay red, la máquina virtual está apagada o falta la configuración,
 * los archivos quedan ahí y se reenvían en orden al iniciar la siguiente sesión.</p>
 *
 * <p>Reenviar un registro que ya llegó no crea duplicados: las claves únicas de cada tabla lo
 * rechazan con el código 23505 y el cliente lo trata como "ya guardado". Así el rol del
 * laboratorio ({@code mario_lab}) solo necesita permiso de inserción.</p>
 *
 * <p>Configuración: {@code experiment/database.properties} con {@code db_host}, {@code db_port},
 * {@code db_name}, {@code db_user}, {@code db_password} (y opcional {@code db_sslmode}), o las
 * variables de entorno {@code DB_HOST}, {@code DB_PORT}, {@code DB_NAME}, {@code DB_USER},
 * {@code DB_PASSWORD}. El driver JDBC de PostgreSQL se copia dentro de {@code bin/} al compilar.</p>
 */
public final class ExperimentDB {

    /** Marca un String que ya es JSON válido (p.ej. la telemetría) para insertarlo tal cual. */
    public record RawJson(String json) {
    }

    private static final Set<String> TABLES = Set.of("participants", "level_plays", "closure", "free_play");
    private static final String IDENTIFIER = "[a-z_][a-z0-9_]*";

    /** Resultado de un envío. */
    private enum Outcome { STORED, REJECTED, RETRY }

    private final String jdbcUrl;
    private final String user;
    private final String password;
    private final String target; // host:puerto/base, para mensajes
    private final Path outbox;
    private final Path failedDir;
    private final ExecutorService worker;
    private final AtomicInteger counter = new AtomicInteger();
    private volatile String lastStatus = "sin actividad";
    private volatile boolean driverWarned = false;

    private ExperimentDB(String host, String port, String name, String user, String password, String sslmode,
            Path outbox) {
        boolean ok = host != null && name != null && user != null;
        String p = port == null ? "5432" : port;
        this.target = ok ? host + ":" + p + "/" + name : null;
        this.jdbcUrl = ok
                ? "jdbc:postgresql://" + host + ":" + p + "/" + name
                        + "?connectTimeout=8&socketTimeout=30&ApplicationName=MarioAI-Experiment"
                        + (sslmode == null ? "" : "&sslmode=" + sslmode)
                : null;
        this.user = user;
        this.password = password == null ? "" : password;
        this.outbox = outbox;
        this.failedDir = outbox.resolve("failed");
        this.worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "experiment-db-sync");
            t.setDaemon(true);
            return t;
        });
    }

    /** Crea el cliente leyendo la configuración y reintenta de inmediato lo pendiente. */
    public static ExperimentDB open(Path experimentDir) {
        Properties props = new Properties();
        Path file = experimentDir.resolve("database.properties");
        if (Files.exists(file)) {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException e) {
                System.err.println("[DB] No se pudo leer " + file + ": " + e.getMessage());
            }
        }
        ExperimentDB db = new ExperimentDB(
                firstNonBlank(System.getenv("DB_HOST"), props.getProperty("db_host")),
                firstNonBlank(System.getenv("DB_PORT"), props.getProperty("db_port")),
                firstNonBlank(System.getenv("DB_NAME"), props.getProperty("db_name")),
                firstNonBlank(System.getenv("DB_USER"), props.getProperty("db_user")),
                firstNonBlank(System.getenv("DB_PASSWORD"), props.getProperty("db_password")),
                firstNonBlank(System.getenv("DB_SSLMODE"), props.getProperty("db_sslmode")),
                experimentDir.resolve("db_pending"));
        if (!db.isConfigured()) {
            System.out.println("[DB] Base de datos no configurada: los registros se guardarán en "
                    + db.outbox.toAbsolutePath() + " hasta que se configure experiment/database.properties.");
        } else {
            System.out.println("[DB] PostgreSQL configurado: " + db.target);
        }
        db.flushAsync();
        return db;
    }

    public boolean isConfigured() {
        return jdbcUrl != null;
    }

    public String getLastStatus() {
        return lastStatus;
    }

    public int pendingCount() {
        return listPending().size();
    }

    /**
     * Guarda un registro: lo escribe en el outbox (sincrónico, a prueba de cierres
     * abruptos) y pide el envío en segundo plano. Nunca lanza excepciones.
     */
    public void insert(String table, Map<String, Object> row) {
        if (!TABLES.contains(table)) {
            throw new IllegalArgumentException("Tabla desconocida: " + table);
        }
        try {
            Files.createDirectories(outbox);
            String name = String.format("%015d_%04d_%s.json", System.currentTimeMillis(),
                    counter.incrementAndGet() % 10000, table);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("table", table);
            envelope.put("row", row);
            Path tmp = outbox.resolve(name + ".tmp");
            Files.writeString(tmp, toJson(envelope), StandardCharsets.UTF_8);
            Files.move(tmp, outbox.resolve(name), StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            System.err.println("[DB] ERROR al escribir el registro local de '" + table + "': " + e.getMessage());
            return;
        }
        flushAsync();
    }

    /** Envía en segundo plano todo lo pendiente, en orden. */
    public void flushAsync() {
        try {
            worker.submit(this::flushNow);
        } catch (Exception ignored) {
            // executor cerrado: los archivos quedan para la próxima sesión
        }
    }

    /** Espera (como máximo {@code seconds}) a que se envíe lo pendiente. Llamar antes de salir. */
    public void awaitFlush(long seconds) {
        try {
            worker.submit(this::flushNow).get(seconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[DB] Envío final incompleto (" + e.getClass().getSimpleName()
                    + "); quedan " + pendingCount() + " registros en db_pending/ para la próxima sesión.");
        }
    }

    private synchronized void flushNow() {
        if (!isConfigured()) {
            lastStatus = "sin configurar (" + pendingCount() + " pendientes)";
            return;
        }
        List<Path> pending = listPending();
        if (pending.isEmpty()) {
            return;
        }
        if (!driverAvailable()) {
            lastStatus = "falta el driver JDBC (" + pending.size() + " pendientes)";
            return;
        }
        DriverManager.setLoginTimeout(10);
        try (Connection conn = DriverManager.getConnection(jdbcUrl, user, password)) {
            conn.setAutoCommit(true);
            for (Path file : pending) {
                String table;
                String rowJson;
                try {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    table = extractTable(content);
                    rowJson = extractRow(content);
                    if (!TABLES.contains(table)) {
                        throw new IllegalStateException("tabla desconocida: " + table);
                    }
                } catch (IOException | RuntimeException e) {
                    System.err.println("[DB] Archivo pendiente ilegible " + file.getFileName() + ": " + e.getMessage());
                    moveToFailed(file);
                    continue;
                }
                Outcome outcome = insertRow(conn, table, rowJson);
                if (outcome == Outcome.STORED) {
                    Files.deleteIfExists(file);
                    lastStatus = "ok";
                } else if (outcome == Outcome.REJECTED) {
                    moveToFailed(file);
                    System.err.println("[DB] Registro rechazado, movido a db_pending/failed/: " + file.getFileName());
                } else {
                    lastStatus = "reintento pendiente (" + pendingCount() + " pendientes)";
                    return;
                }
            }
        } catch (SQLException e) {
            lastStatus = "sin conexión (" + pendingCount() + " pendientes)";
            System.err.println("[DB] No se pudo conectar a PostgreSQL en " + target + " (" + describe(e)
                    + "); se reintentará más tarde.");
        } catch (IOException e) {
            System.err.println("[DB] Error de archivos en db_pending/: " + e.getMessage());
        }
    }

    /**
     * Inserta una fila a partir de su JSON. PostgreSQL convierte cada campo al tipo de su
     * columna (texto, enteros, booleanos, text[], jsonb, timestamptz) con jsonb_populate_record.
     */
    private Outcome insertRow(Connection conn, String table, String rowJson) {
        try {
            List<String> columns = new ArrayList<>();
            try (PreparedStatement keys = conn.prepareStatement("select array(select jsonb_object_keys(?::jsonb))")) {
                keys.setString(1, rowJson);
                try (ResultSet rs = keys.executeQuery()) {
                    rs.next();
                    Array arr = rs.getArray(1);
                    for (Object k : (Object[]) arr.getArray()) {
                        String col = String.valueOf(k);
                        if (!col.matches(IDENTIFIER)) {
                            System.err.println("[DB] Columna inválida '" + col + "' en " + table);
                            return Outcome.REJECTED;
                        }
                        columns.add(col);
                    }
                }
            }
            if (columns.isEmpty()) {
                return Outcome.REJECTED;
            }
            String cols = String.join(", ", columns);
            String sql = "insert into public." + table + " (" + cols + ") select " + cols
                    + " from jsonb_populate_record(null::public." + table + ", ?::jsonb)";
            try (PreparedStatement ins = conn.prepareStatement(sql)) {
                ins.setString(1, rowJson);
                ins.executeUpdate();
            }
            return Outcome.STORED;
        } catch (SQLException e) {
            String state = e.getSQLState() == null ? "" : e.getSQLState();
            if (state.equals("23505")) {
                return Outcome.STORED; // clave duplicada: el registro ya estaba guardado (reenvío)
            }
            System.err.println("[DB] " + table + " -> " + describe(e));
            if (state.equals("23503")) {
                return Outcome.RETRY; // FK: el participante todavía no llegó; se reintenta en orden
            }
            if (state.equals("42501") || state.equals("42P01") || state.equals("3F000")) {
                return Outcome.RETRY; // sin permisos / falta el esquema: problema de configuración, no de datos
            }
            if (state.startsWith("22") || state.startsWith("23") || state.startsWith("42")) {
                return Outcome.REJECTED; // datos inválidos para el esquema
            }
            return Outcome.RETRY; // conexión caída, base reiniciándose, etc.
        }
    }

    private boolean driverAvailable() {
        try {
            Class.forName("org.postgresql.Driver");
            return true;
        } catch (ClassNotFoundException e) {
            if (!driverWarned) {
                driverWarned = true;
                System.err.println("[DB] No se encontró el driver JDBC de PostgreSQL en bin/. "
                        + "Recompila con 'make compile' (copia src/tools/lib/postgresql-*.jar).");
            }
            return false;
        }
    }

    private static String describe(SQLException e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().strip();
        return (e.getSQLState() == null ? "" : "SQLState " + e.getSQLState() + ": ") + msg;
    }

    private void moveToFailed(Path file) {
        try {
            Files.createDirectories(failedDir);
            Files.move(file, failedDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private List<Path> listPending() {
        if (!Files.isDirectory(outbox)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(outbox)) {
            return s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    // El sobre siempre es {"table":"...","row":{...}} escrito por toJson(), así que basta con cortar.
    private static String extractTable(String envelope) {
        int start = envelope.indexOf("\"table\":\"") + 9;
        int end = envelope.indexOf('"', start);
        if (start < 9 || end < 0) {
            throw new IllegalStateException("sobre sin 'table'");
        }
        return envelope.substring(start, end);
    }

    private static String extractRow(String envelope) {
        int start = envelope.indexOf("\"row\":");
        int end = envelope.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("sobre sin 'row'");
        }
        return envelope.substring(start + 6, end).strip();
    }

    // ------------------------------------------------------------------
    // Línea de comandos: make test-db / make sync-db
    // ------------------------------------------------------------------

    /**
     * {@code java -cp bin ExperimentDB --test}: envía un participante de prueba (id TEST_...)
     * con un nivel y confirma que llegó. {@code java -cp bin ExperimentDB}: solo reenvía
     * lo pendiente en db_pending/. Se ejecuta desde mario_ai_framework/, como el experimento.
     */
    public static void main(String[] args) {
        ExperimentDB db = open(Paths.get("../src/experiment"));
        if (!db.isConfigured()) {
            System.out.println("[DB] FALTA CONFIGURACIÓN: crea src/experiment/database.properties "
                    + "(copia database.properties.example) con db_host, db_name, db_user y db_password.");
            System.exit(2);
        }
        boolean test = args.length > 0 && "--test".equals(args[0]);
        if (test) {
            String id = "TEST_" + java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Map<String, Object> p = row();
            p.put("id", id);
            p.put("sequence_group", "TEST");
            p.put("machine", machineName());
            p.put("code_version", codeVersion(Paths.get("../src")));
            p.put("initial_survey", Map.of("nota", "registro de prueba de make test-db"));
            db.insert("participants", p);
            Map<String, Object> lp = row();
            lp.put("participant_id", id);
            lp.put("phase", "main");
            lp.put("play_order", 1);
            lp.put("level_type", "P");
            lp.put("level_content", "--------\nM-----g-\nXXXXXXXX");
            lp.put("telemetry", new RawJson("{\"status\":\"WIN\",\"completion\":1.0,\"jumps\":3}"));
            lp.put("responses", Map.of("disfrute", 5));
            lp.put("generation", Map.of("source", "generated", "prompt_source", "heuristic"));
            db.insert("level_plays", lp);
            System.out.println("[DB] Enviando participante de prueba " + id + " ...");
        }
        db.awaitFlush(30);
        int pending = db.pendingCount();
        if (pending == 0) {
            System.out.println(test
                    ? "[DB] OK: PostgreSQL recibió los registros de prueba (participants.id = TEST_...)."
                    : "[DB] OK: no quedan registros pendientes.");
            System.exit(0);
        }
        System.out.println("[DB] ATENCIÓN: quedan " + pending + " registros en src/experiment/db_pending/ "
                + "(estado: " + db.getLastStatus() + "). Revisa los mensajes [DB] de arriba.");
        System.exit(1);
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    /** Hostname de la máquina (para saber en qué computadora del laboratorio se jugó). */
    public static String machineName() {
        String env = firstNonBlank(System.getenv("COMPUTERNAME"), System.getenv("HOSTNAME"));
        if (env != null) {
            return env;
        }
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "desconocida";
        }
    }

    /** Commit corto de git del repo (null si git no está disponible). */
    public static String codeVersion(Path repoDir) {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .directory(repoDir.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && !out.isBlank()) {
                return out;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Lee un archivo JSON (p.ej. telemetría) como RawJson; null si no existe o está vacío. */
    public static RawJson readJsonFile(Path file) {
        try {
            if (file != null && Files.exists(file)) {
                String s = Files.readString(file, StandardCharsets.UTF_8).strip();
                if (s.startsWith("{") || s.startsWith("[")) {
                    return new RawJson(s);
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /** Convierte un texto a número si lo es (para que las escalas Likert queden numéricas). */
    public static Object numberOrText(String value) {
        if (value == null) {
            return null;
        }
        String v = value.strip();
        if (v.matches("-?\\d{1,9}")) {
            return Integer.parseInt(v);
        }
        if (v.matches("-?\\d+\\.\\d+")) {
            return Double.parseDouble(v);
        }
        return v;
    }

    public static Map<String, Object> row() {
        return new LinkedHashMap<>();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.strip();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Serializador JSON mínimo (el proyecto se compila solo con javac, sin librerías)
    // ------------------------------------------------------------------

    public static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, value);
        return sb.toString();
    }

    private static void writeJson(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof RawJson raw) {
            sb.append(raw.json());
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Character c) {
            writeString(sb, String.valueOf(c));
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            sb.append(value);
        } else if (value instanceof Number n) {
            double d = n.doubleValue();
            sb.append(Double.isFinite(d) ? String.valueOf(d) : "null");
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                writeJson(sb, e.getValue());
            }
            sb.append('}');
        } else if (value instanceof Collection<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object o : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeJson(sb, o);
            }
            sb.append(']');
        } else if (value instanceof Object[] arr) {
            writeJson(sb, java.util.Arrays.asList(arr));
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
