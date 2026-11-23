import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Waste Collection Manager - Java backend (no external libraries).
 * Serves index.html and a small JSON API using the JDK's built-in HTTP server.
 * Data is kept in memory (resets on restart).
 */
public class WasteCollectionManager {

    static final List<Map<String, String>> requests = new CopyOnWriteArrayList<>();
    static final List<Map<String, String>> vehicles = new CopyOnWriteArrayList<>();
    static final List<Map<String, String>> schedule = new ArrayList<>();
    static final AtomicInteger seq = new AtomicInteger(1000);

    static Map<String, String> row(String... kv) {
        Map<String, String> m = new ConcurrentHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    public static void main(String[] args) throws IOException {
        // Sample data
        vehicles.add(row("id", "TRK-01", "driver", "Ravi Kumar", "route", "Zone A", "status", "Available"));
        vehicles.add(row("id", "TRK-02", "driver", "Meena S", "route", "Zone B", "status", "Available"));
        vehicles.add(row("id", "TRK-03", "driver", "Arun P", "route", "Zone C", "status", "Available"));
        schedule.add(row("area", "Ward 1 - Market Street", "days", "Mon, Thu", "time", "6:00 AM"));
        schedule.add(row("area", "Ward 2 - Lake View", "days", "Tue, Fri", "time", "7:00 AM"));
        schedule.add(row("area", "Ward 3 - Gandhi Nagar", "days", "Wed, Sat", "time", "6:30 AM"));

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/", ex -> {
            if (!ex.getRequestURI().getPath().equals("/")) { send(ex, 404, "text/plain", "Not found"); return; }
            send(ex, 200, "text/html; charset=utf-8", Files.readString(Path.of("index.html")));
        });

        server.createContext("/api/data", ex -> send(ex, 200, "application/json",
                "{\"requests\":" + arr(requests) + ",\"vehicles\":" + arr(vehicles)
                        + ",\"schedule\":" + arr(schedule) + "}"));

        // Resident: report uncollected waste or submit a complaint
        server.createContext("/api/report", ex -> {
            if (!ex.getRequestMethod().equals("POST")) { send(ex, 405, "text/plain", "POST only"); return; }
            Map<String, String> f = form(ex);
            String type = f.getOrDefault("type", "waste");
            String name = clip(f.get("name")), area = clip(f.get("area")), details = clip(f.get("details"));
            if (name.isEmpty() || area.isEmpty() || details.isEmpty()
                    || !(type.equals("waste") || type.equals("complaint"))) {
                send(ex, 400, "application/json", "{\"error\":\"Please fill in all fields.\"}");
                return;
            }
            String id = "WC" + seq.incrementAndGet();
            requests.add(row("id", id, "type", type, "name", name, "area", area,
                    "details", details, "status", "Pending", "vehicle", "-"));
            send(ex, 200, "application/json", "{\"id\":\"" + id + "\"}");
        });

        // Admin: assign a vehicle to a request
        server.createContext("/api/assign", ex -> {
            Map<String, String> f = form(ex);
            Map<String, String> r = find(requests, f.get("id"));
            Map<String, String> v = find(vehicles, f.get("vehicle"));
            if (r == null || v == null) { send(ex, 404, "application/json", "{\"error\":\"Not found\"}"); return; }
            r.put("vehicle", v.get("id"));
            r.put("status", "Assigned");
            v.put("status", "On Duty");
            send(ex, 200, "application/json", "{\"ok\":true}");
        });

        // Admin: update request status
        server.createContext("/api/status", ex -> {
            Map<String, String> f = form(ex);
            Map<String, String> r = find(requests, f.get("id"));
            String s = f.getOrDefault("status", "");
            if (r == null || !List.of("Pending", "Assigned", "In Progress", "Collected").contains(s)) {
                send(ex, 400, "application/json", "{\"error\":\"Invalid\"}");
                return;
            }
            r.put("status", s);
            if (s.equals("Collected")) {
                Map<String, String> v = find(vehicles, r.get("vehicle"));
                if (v != null) v.put("status", "Available");
            }
            send(ex, 200, "application/json", "{\"ok\":true}");
        });

        server.start();
        System.out.println("Waste Collection Manager running at http://localhost:8080");
    }

    // ---------- helpers ----------
    static Map<String, String> find(List<Map<String, String>> list, String id) {
        if (id == null) return null;
        for (Map<String, String> m : list) if (id.equals(m.get("id"))) return m;
        return null;
    }

    static String clip(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    static Map<String, String> form(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> m = new HashMap<>();
        for (String p : body.split("&")) {
            if (p.isEmpty()) continue;
            String[] kv = p.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }

    static String js(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "") + "\"";
    }

    static String arr(Collection<Map<String, String>> c) {
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (Map<String, String> m : c) {
            StringJoiner o = new StringJoiner(",", "{", "}");
            m.forEach((k, v) -> o.add(js(k) + ":" + js(v)));
            j.add(o.toString());
        }
        return j.toString();
    }

    static void send(HttpExchange ex, int code, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }
}
