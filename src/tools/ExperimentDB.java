import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Cliente mínimo para guardar los datos del experimento en Supabase (PostgREST).
 *
 * <p>Garantía principal: ningún dato se pierde. Cada registro se escribe primero como
 * archivo en {@code experiment/db_pending/} y solo se borra cuando Supabase confirma
 * la inserción. Si no hay red, el proyecto está pausado o falta la configuración, los
 * archivos quedan ahí y se reenvían en orden al iniciar la siguiente sesión.</p>
 *
 * <p>Reenviar un registro que ya llegó no crea duplicados: las claves únicas de cada tabla lo
 * rechazan con el código 23505 y el cliente lo trata como "ya guardado". Así la llave del
 * laboratorio solo necesita permiso de inserción (ni lectura ni modificación).</p>
 *
 * <p>Configuración (en este orden de prioridad): variables de entorno
 * {@code SUPABASE_URL} / {@code SUPABASE_KEY}, o {@code experiment/database.properties}
 * con {@code supabase_url=} y {@code supabase_key=}.</p>
 */
public final class ExperimentDB {

    /** Marca un String que ya es JSON válido (p.ej. la telemetría) para insertarlo tal cual. */
    public record RawJson(String json) {
    }

    private static final java.util.Set<String> TABLES = java.util.Set.of(
            "participants", "level_plays", "closure", "free_play");

    /** Resultado de un envío. */
    private enum Outcome { STORED, REJECTED, RETRY }

    private final String baseUrl;
    private final String apiKey;
    private final Path outbox;
    private final Path failedDir;
    private final HttpClient http;
    private final ExecutorService worker;
    private final AtomicInteger counter = new AtomicInteger();
    private volatile String lastStatus = "sin actividad";

    private ExperimentDB(String baseUrl, String apiKey, Path outbox) {
        this.baseUrl = baseUrl == null ? null : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.outbox = outbox;
        this.failedDir = outbox.resolve("failed");
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
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
        String url = firstNonBlank(System.getenv("SUPABASE_URL"), props.getProperty("supabase_url"));
        String key = firstNonBlank(System.getenv("SUPABASE_KEY"), props.getProperty("supabase_key"));
        ExperimentDB db = new ExperimentDB(url, key, experimentDir.resolve("db_pending"));
        if (!db.isConfigured()) {
            System.out.println("[DB] Supabase no configurado: los registros se guardarán en "
                    + db.outbox.toAbsolutePath() + " hasta que se configure experiment/database.properties.");
        } else {
            System.out.println("[DB] Supabase configurado: " + db.baseUrl);
        }
        db.flushAsync();
        return db;
    }

    public boolean isConfigured() {
        return baseUrl != null && !baseUrl.isBlank() && apiKey != null && !apiKey.isBlank();
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
        for (Path file : listPending()) {
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                String table = extractTable(content);
                String rowJson = extractRow(content);
                Outcome outcome = post(table, rowJson);
                if (outcome == Outcome.STORED) {
                    Files.deleteIfExists(file);
                    lastStatus = "ok";
                } else if (outcome == Outcome.REJECTED) {
                    // Datos rechazados por el esquema: apartarlos para revisión sin bloquear el resto.
                    Files.createDirectories(failedDir);
                    Files.move(file, failedDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    System.err.println("[DB] Registro rechazado, movido a db_pending/failed/: " + file.getFileName());
                } else {
                    // Red caída, proyecto pausado, llave inválida, participante aún no guardado...: reintentar luego.
                    lastStatus = "reintento pendiente (" + pendingCount() + " pendientes)";
                    return;
                }
            } catch (IOException | InterruptedException e) {
                lastStatus = "sin conexión (" + pendingCount() + " pendientes)";
                System.err.println("[DB] Sin conexión con Supabase (" + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "); se reintentará más tarde.");
                return;
            } catch (RuntimeException e) {
                System.err.println("[DB] Archivo pendiente ilegible " + file.getFileName() + ": " + e.getMessage());
                try {
                    Files.createDirectories(failedDir);
                    Files.move(file, failedDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private Outcome post(String table, String rowJson) throws IOException, InterruptedException {
        String url = baseUrl + "/rest/v1/" + table;
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("apikey", apiKey)
                .header("Content-Type", "application/json")
                .header("Prefer", "return=minimal")
                .POST(HttpRequest.BodyPublishers.ofString(rowJson, StandardCharsets.UTF_8));
        // Las llaves "legacy" (JWT anon) también van como Bearer; las nuevas sb_publishable_ no.
        if (!apiKey.startsWith("sb_")) {
            req.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        int code = resp.statusCode();
        String body = resp.body() == null ? "" : resp.body();
        if (code >= 200 && code < 300) {
            return Outcome.STORED;
        }
        if (body.contains("\"23505\"")) {
            return Outcome.STORED; // clave duplicada: el registro ya estaba guardado (reenvío)
        }
        System.err.println("[DB] " + table + " -> HTTP " + code + ": "
                + (body.length() > 300 ? body.substring(0, 300) + "..." : body));
        if (body.contains("\"23503\"")) {
            return Outcome.RETRY; // FK: el participante todavía no llegó; se reintenta en orden
        }
        if (code == 400 || code == 422) {
            return Outcome.REJECTED; // datos inválidos para el esquema
        }
        return Outcome.RETRY; // 401/403 llave, 404 tabla, 5xx/503 proyecto pausado, etc.
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
        ExperimentDB db = open(java.nio.file.Paths.get("../src/experiment"));
        if (!db.isConfigured()) {
            System.out.println("[DB] FALTA CONFIGURACIÓN: crea src/experiment/database.properties "
                    + "(copia database.properties.example) con supabase_url y supabase_key.");
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
            p.put("code_version", codeVersion(java.nio.file.Paths.get("../src")));
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
                    ? "[DB] OK: Supabase recibió los registros de prueba. Revísalos en Table Editor -> participants (id TEST_...)."
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
