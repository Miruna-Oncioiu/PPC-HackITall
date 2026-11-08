package com.ppc.hackathon;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class DashboardController {

    private static final String JSON_PATH = "users.json";
    private static final String HIST_PATH = "../history.db";
    private static final String LIVE_PATH = "../ppc_energy.db";
    private final ObjectMapper mapper = new ObjectMapper();

    private static final Map<String, String> PARK_NAMES = Map.of(
        "WP37BE765B", "Parc Eolian Buzău",
        "WP76E33517", "Parc Eolian Vaslui",
        "WP98FA99B8", "Parc Eolian Casimcea",
        "WPBC408F47", "Parc Eolian Constanța"
    );

    private static final Map<String, Integer> NOMINAL_KW = Map.of(
        "VORTEX", 735, "HELIOS", 640, "NEXUS", 985
    );

    // =========================================================================
    // HELPER METHODS
    // =========================================================================
    private Map<String, Object> readJson() throws Exception {
        File file = new File(JSON_PATH);
        if (!file.exists()) {
            Map<String, Object> e = new HashMap<>(); e.put("companies", new ArrayList<>());
            mapper.writeValue(file, e);
        }
        return mapper.readValue(file, Map.class);
    }

    private void writeJson(Map<String, Object> data) throws Exception {
        mapper.writerWithDefaultPrettyPrinter().writeValue(new File(JSON_PATH), data);
    }

    private boolean tableExists(Connection conn, String table) {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, table, null)) {
            return rs.next();
        } catch (Exception e) { return false; }
    }

    private boolean colExists(Connection conn, String table, String col) {
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, table, col)) {
            return rs.next();
        } catch (Exception e) { return false; }
    }

    private String startTime(int days) {
        return LocalDateTime.now().minusDays(days)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private double toDouble(Object o) {
        if (o == null) return 0;
        try { return ((Number) o).doubleValue(); }
        catch (Exception e) { try { return Double.parseDouble(o.toString()); } catch (Exception e2) { return 0; } }
    }

    private double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    // =========================================================================
    // 1. HARTA TURBINE — cu coordonate GPS și status_color
    // GET /api/park/{parkId}/map-turbines
    // =========================================================================
    @GetMapping("/park/{parkId}/map-turbines")
    public ResponseEntity<Object> getMapTurbines(@PathVariable String parkId) {
        List<Map<String, Object>> turbines = new ArrayList<>();
        String[][] dbs = {{LIVE_PATH, "live_measurements"}, {HIST_PATH, "raw_history"}};

        for (String[] db : dbs) {
            if (!new File(db[0]).exists()) continue;
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db[0])) {
                boolean hasSC = colExists(conn, db[1], "status_color");
                boolean hasTR = colExists(conn, db[1], "thermal_risk");
                String scExpr = hasSC ? "status_color" : "'GREEN'";
                String trExpr = hasTR ? "thermal_risk" : "NULL";
                String sql = String.format("""
                    SELECT turbine_id, lat_y, long_x, act_pwt, wd_spd,
                           power_efficiency_pct, %s AS status_color,
                           %s AS thermal_risk, fk_wtg_model_id,
                           is_anomaly, anomaly_reason
                    FROM %s
                    WHERE park_id = ? AND lat_y IS NOT NULL AND long_x IS NOT NULL
                      AND timestamp = (
                        SELECT MAX(t2.timestamp) FROM %s t2
                        WHERE t2.turbine_id = %s.turbine_id AND t2.park_id = ?
                      )
                    ORDER BY turbine_id
                    """, scExpr, trExpr, db[1], db[1], db[1]);

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, parkId); ps.setString(2, parkId);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("turbineId",   rs.getString("turbine_id"));
                        t.put("lat",         rs.getDouble("lat_y"));
                        t.put("lng",         rs.getDouble("long_x"));
                        t.put("actPwt",      rs.getObject("act_pwt"));
                        t.put("windSpeed",   rs.getObject("wd_spd"));
                        t.put("efficiency",  rs.getObject("power_efficiency_pct"));
                        t.put("statusColor", resolveColor(rs));
                        t.put("thermalRisk", rs.getObject("thermal_risk"));
                        t.put("model",       rs.getString("fk_wtg_model_id"));
                        t.put("isAnomaly",   rs.getObject("is_anomaly"));
                        t.put("anomalyReason", rs.getString("anomaly_reason"));
                        turbines.add(t);
                    }
                }
                if (!turbines.isEmpty()) break;
            } catch (Exception e) { System.err.println("Map error: " + e.getMessage()); }
        }
        return ResponseEntity.ok(turbines);
    }

    private String resolveColor(ResultSet rs) throws SQLException {
        String stored = rs.getString("status_color");
        if (stored != null && !stored.isBlank() && !stored.equals("null")) return stored;
        Object ap = rs.getObject("act_pwt");
        if (ap == null) return "RED";
        double v = ((Number) ap).doubleValue();
        if (v < -50) return "RED";
        return v <= 0 ? "YELLOW" : "GREEN";
    }

    // =========================================================================
    // 2. DETALIU TURBINĂ — model, producție, temperaturi, risc termic, notificări
    // GET /api/turbine/{turbineId}?park={parkId}&days={N}
    // =========================================================================
    @GetMapping("/turbine/{turbineId}")
    public ResponseEntity<Object> getTurbineDetail(
            @PathVariable String turbineId,
            @RequestParam(value = "park", required = false) String parkId,
            @RequestParam(value = "days", defaultValue = "1") int days) {

        Map<String, Object> result = new LinkedHashMap<>();
        File dbFile = new File(HIST_PATH);
        if (!dbFile.exists()) return ResponseEntity.ok(result);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + HIST_PATH)) {
            String st = startTime(days);

            // ── Date curente turbină ───────────────────────────────────────────
            String sqlCurrent = """
                SELECT turbine_id, park_id, fk_wtg_model_id,
                       act_pwt, wd_spd, power_theoretical, power_efficiency_pct,
                       conv_t, gearbox_t, gen1_t, gen2_t, transformer_t, turbine_t,
                       thermal_risk, status_color, is_anomaly, anomaly_reason,
                       sensor_warnings, lat_y, long_x, timestamp
                FROM raw_history
                WHERE turbine_id = ?
                  AND timestamp = (SELECT MAX(timestamp) FROM raw_history WHERE turbine_id = ?)
                LIMIT 1
                """;
            try (PreparedStatement ps = conn.prepareStatement(sqlCurrent)) {
                ps.setString(1, turbineId); ps.setString(2, turbineId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    result.put("turbineId",      rs.getString("turbine_id"));
                    result.put("parkId",         rs.getString("park_id"));
                    result.put("parkName",       PARK_NAMES.getOrDefault(rs.getString("park_id"), rs.getString("park_id")));
                    result.put("model",          rs.getString("fk_wtg_model_id"));
                    result.put("nominalPowerKw", NOMINAL_KW.getOrDefault(rs.getString("fk_wtg_model_id"), 1000));
                    result.put("lat",            rs.getObject("lat_y"));
                    result.put("lng",            rs.getObject("long_x"));
                    result.put("lastTimestamp",  rs.getString("timestamp"));
                    result.put("statusColor",    rs.getString("status_color"));
                    result.put("isAnomaly",      rs.getObject("is_anomaly"));
                    result.put("anomalyReason",  rs.getString("anomaly_reason"));
                    result.put("sensorWarnings", rs.getString("sensor_warnings"));

                    // Live metrics
                    Map<String, Object> live = new LinkedHashMap<>();
                    live.put("actPwt",        rs.getObject("act_pwt"));
                    live.put("windSpeed",     rs.getObject("wd_spd"));
                    live.put("theoretical",   rs.getObject("power_theoretical"));
                    live.put("efficiencyPct", rs.getObject("power_efficiency_pct"));
                    result.put("live", live);

                    // Temperaturi + risc termic
                    Map<String, Object> temps = new LinkedHashMap<>();
                    temps.put("conv_t",        rs.getObject("conv_t"));
                    temps.put("gearbox_t",     rs.getObject("gearbox_t"));
                    temps.put("gen1_t",        rs.getObject("gen1_t"));
                    temps.put("gen2_t",        rs.getObject("gen2_t"));
                    temps.put("transformer_t", rs.getObject("transformer_t"));
                    temps.put("turbine_t",     rs.getObject("turbine_t"));
                    temps.put("thermalRisk",   rs.getObject("thermal_risk"));
                    result.put("temperatures", temps);
                }
            }

            // ── Statistici pe perioada selectată ──────────────────────────────
            String sqlStats = """
                SELECT
                  COUNT(*) AS total_min,
                  SUM(CASE WHEN act_pwt > 0 THEN act_pwt/1000.0/60.0 ELSE 0 END) AS productie_mwh,
                  MAX(CASE WHEN act_pwt > 0 THEN act_pwt ELSE 0 END) AS max_power_kw,
                  AVG(CASE WHEN act_pwt > 0 THEN act_pwt ELSE NULL END) AS avg_power_kw,
                  AVG(wd_spd) AS avg_wind,
                  AVG(power_efficiency_pct) AS avg_eff,
                  SUM(CASE WHEN status_color = 'RED' THEN 1 ELSE 0 END) AS min_red,
                  SUM(CASE WHEN status_color = 'YELLOW' THEN 1 ELSE 0 END) AS min_yellow,
                  SUM(CASE WHEN status_color = 'GREEN' THEN 1 ELSE 0 END) AS min_green,
                  SUM(CASE WHEN status_color = 'ORANGE' THEN 1 ELSE 0 END) AS min_orange
                FROM raw_history
                WHERE turbine_id = ? AND timestamp >= ?
                """;
            try (PreparedStatement ps = conn.prepareStatement(sqlStats)) {
                ps.setString(1, turbineId); ps.setString(2, st);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    int totalMin = rs.getInt("total_min");
                    Map<String, Object> stats = new LinkedHashMap<>();
                    stats.put("days",            days);
                    stats.put("productionMwh",   round2(rs.getDouble("productie_mwh")));
                    stats.put("maxPowerKw",       round2(rs.getDouble("max_power_kw")));
                    stats.put("avgPowerKw",       round2(rs.getDouble("avg_power_kw")));
                    stats.put("avgWindMs",        round2(rs.getDouble("avg_wind")));
                    stats.put("avgEfficiencyPct", round2(rs.getDouble("avg_eff")));
                    stats.put("availabilityPct",  totalMin > 0
                        ? round2((totalMin - rs.getInt("min_red")) * 100.0 / totalMin) : 100.0);
                    stats.put("minGreen",  rs.getInt("min_green"));
                    stats.put("minYellow", rs.getInt("min_yellow"));
                    stats.put("minOrange", rs.getInt("min_orange"));
                    stats.put("minRed",    rs.getInt("min_red"));
                    stats.put("totalMin",  totalMin);

                    // Factor de capacitate
                    String model = (String) result.get("model");
                    int nominal = NOMINAL_KW.getOrDefault(model, 1000);
                    double maxPossibleMwh = nominal / 1000.0 * totalMin / 60.0;
                    stats.put("capacityFactorPct", maxPossibleMwh > 0
                        ? round2(rs.getDouble("productie_mwh") / maxPossibleMwh * 100) : 0.0);
                    result.put("stats", stats);
                }
            }

            // ── Producție orară (pentru grafic time series) ───────────────────
            List<Map<String, Object>> timeSeries = new ArrayList<>();
            String sqlTs = """
                SELECT substr(timestamp,1,13) AS ora,
                       AVG(act_pwt)  AS avg_power,
                       AVG(wd_spd)   AS avg_wind,
                       AVG(power_efficiency_pct) AS avg_eff
                FROM raw_history
                WHERE turbine_id = ? AND timestamp >= ?
                GROUP BY substr(timestamp,1,13)
                ORDER BY ora
                """;
            try (PreparedStatement ps = conn.prepareStatement(sqlTs)) {
                ps.setString(1, turbineId); ps.setString(2, st);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Map<String, Object> pt = new LinkedHashMap<>();
                    pt.put("ora",      rs.getString("ora"));
                    pt.put("avgPower", round2(rs.getDouble("avg_power")));
                    pt.put("avgWind",  round2(rs.getDouble("avg_wind")));
                    pt.put("avgEff",   round2(rs.getDouble("avg_eff")));
                    timeSeries.add(pt);
                }
            }
            result.put("timeSeries", timeSeries);

            // ── Scatter wind vs power (pentru grafic dispersie) ───────────────
            List<Map<String, Object>> scatter = new ArrayList<>();
            String sqlSc = """
                SELECT ROUND(wd_spd,1) AS v, ROUND(act_pwt,0) AS p
                FROM raw_history
                WHERE turbine_id = ? AND timestamp >= ?
                  AND wd_spd IS NOT NULL AND act_pwt > 0 AND act_pwt < 5000
                  AND is_anomaly = 0
                ORDER BY RANDOM() LIMIT 200
                """;
            try (PreparedStatement ps = conn.prepareStatement(sqlSc)) {
                ps.setString(1, turbineId); ps.setString(2, st);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Map<String, Object> pt = new LinkedHashMap<>();
                    pt.put("v", rs.getDouble("v"));
                    pt.put("p", rs.getDouble("p"));
                    scatter.add(pt);
                }
            }
            result.put("scatter", scatter);

            // ── Istoricul temperaturii (risc termic pe timp) ─────────────────
            List<Map<String, Object>> tempHistory = new ArrayList<>();
            if (colExists(conn, "raw_history", "thermal_risk")) {
                String sqlTH = """
                    SELECT substr(timestamp,1,13) AS ora,
                           AVG(conv_t) AS conv, AVG(gearbox_t) AS gear,
                           AVG(gen1_t) AS gen1, AVG(transformer_t) AS trans,
                           AVG(thermal_risk) AS risk
                    FROM raw_history
                    WHERE turbine_id = ? AND timestamp >= ?
                    GROUP BY substr(timestamp,1,13)
                    ORDER BY ora
                    """;
                try (PreparedStatement ps = conn.prepareStatement(sqlTH)) {
                    ps.setString(1, turbineId); ps.setString(2, st);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        Map<String, Object> pt = new LinkedHashMap<>();
                        pt.put("ora",  rs.getString("ora"));
                        pt.put("conv", rs.getObject("conv"));
                        pt.put("gear", rs.getObject("gear"));
                        pt.put("gen1", rs.getObject("gen1"));
                        pt.put("trans",rs.getObject("trans"));
                        pt.put("risk", rs.getObject("risk"));
                        tempHistory.add(pt);
                    }
                }
            }
            result.put("tempHistory", tempHistory);

            // ── Notificări / anomalii (ultimele 50) ──────────────────────────
            List<Map<String, Object>> notifications = new ArrayList<>();
            String sqlN = """
                SELECT timestamp, status_color, anomaly_reason, sensor_warnings,
                       ROUND(act_pwt,1) AS power, ROUND(wd_spd,2) AS wind
                FROM raw_history
                WHERE turbine_id = ? AND timestamp >= ?
                  AND (is_anomaly = 1 OR (sensor_warnings IS NOT NULL AND sensor_warnings != ''))
                ORDER BY timestamp DESC LIMIT 50
                """;
            try (PreparedStatement ps = conn.prepareStatement(sqlN)) {
                ps.setString(1, turbineId); ps.setString(2, st);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    Map<String, Object> n = new LinkedHashMap<>();
                    n.put("timestamp",   rs.getString("timestamp"));
                    n.put("statusColor", rs.getString("status_color"));
                    n.put("reason",      rs.getString("anomaly_reason"));
                    n.put("warnings",    rs.getString("sensor_warnings"));
                    n.put("power",       rs.getObject("power"));
                    n.put("wind",        rs.getObject("wind"));
                    notifications.add(n);
                }
            }
            result.put("notifications", notifications);

        } catch (Exception e) {
            System.err.println("Turbine detail error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // 3. RAPORT OPERAȚIONAL PE ZILE
    // GET /api/report?days=N&parks=WP37BE765B,WP98FA99B8
    // =========================================================================
    @GetMapping("/report")
    public ResponseEntity<Object> getReport(
            @RequestParam(value = "days",  defaultValue = "1") int days,
            @RequestParam(value = "parks", defaultValue = "") String parksParam) {

        // Scrie trigger pentru Python
        try (FileWriter fw = new FileWriter("../report_trigger.txt")) { fw.write(String.valueOf(days)); }
        catch (Exception ignored) {}

        List<String> parkIds = parksParam.isBlank()
            ? new ArrayList<>(PARK_NAMES.keySet())
            : Arrays.asList(parksParam.split(","));

        List<Map<String, Object>> result = new ArrayList<>();
        if (!new File(HIST_PATH).exists()) return ResponseEntity.ok(result);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + HIST_PATH)) {
            String st = startTime(days);

            for (String pid : parkIds) {
                pid = pid.trim();
                Map<String, Object> parkReport = new LinkedHashMap<>();
                parkReport.put("parkId",   pid);
                parkReport.put("parkName", PARK_NAMES.getOrDefault(pid, pid));
                parkReport.put("days",     days);

                // ── Tabel turbine ─────────────────────────────────────────────
                List<Map<String, Object>> turbineRows = new ArrayList<>();
                if (tableExists(conn, "raport_zilnic_turbine")) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT * FROM raport_zilnic_turbine WHERE park_id = ? ORDER BY productie_total_mwh DESC")) {
                        ps.setString(1, pid);
                        ResultSet rs = ps.executeQuery();
                        ResultSetMetaData meta = rs.getMetaData();
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= meta.getColumnCount(); i++)
                                row.put(meta.getColumnName(i), rs.getObject(i));
                            turbineRows.add(row);
                        }
                    }
                }
                if (turbineRows.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT park_id, turbine_id,
                               SUM(CASE WHEN act_pwt>0 THEN act_pwt/1000.0/60.0 ELSE 0 END) AS productie_total_mwh,
                               MAX(CASE WHEN act_pwt>0 THEN act_pwt ELSE 0 END) AS max_power_kw,
                               AVG(CASE WHEN act_pwt>0 THEN act_pwt ELSE NULL END) AS avg_power_kw,
                               AVG(wd_spd) AS avg_wind_ms,
                               AVG(power_efficiency_pct) AS avg_efficiency_pct,
                               COUNT(*) AS total_min,
                               SUM(CASE WHEN status_color!='RED' THEN 1 ELSE 0 END) AS min_ok,
                               MAX(status_color) AS status_curent,
                               fk_wtg_model_id AS model
                        FROM raw_history WHERE park_id=? AND timestamp>=?
                        GROUP BY park_id, turbine_id ORDER BY productie_total_mwh DESC
                        """)) {
                        ps.setString(1, pid); ps.setString(2, st);
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            int tot = rs.getInt("total_min"), ok = rs.getInt("min_ok");
                            row.put("park_id",             rs.getString("park_id"));
                            row.put("turbine_id",          rs.getString("turbine_id"));
                            row.put("productie_total_mwh", round2(rs.getDouble("productie_total_mwh")));
                            row.put("max_power_kw",        round2(rs.getDouble("max_power_kw")));
                            row.put("avg_power_kw",        round2(rs.getDouble("avg_power_kw")));
                            row.put("avg_wind_ms",         round2(rs.getDouble("avg_wind_ms")));
                            row.put("avg_efficiency_pct",  round2(rs.getDouble("avg_efficiency_pct")));
                            row.put("availability_pct",    tot > 0 ? round2((double) ok / tot * 100) : 100.0);
                            row.put("status_curent",       rs.getString("status_curent"));
                            row.put("model",               rs.getString("model"));
                            turbineRows.add(row);
                        }
                    }
                }
                parkReport.put("turbines", turbineRows);

                // ── KPI sumar parc ────────────────────────────────────────────
                double totalMwh    = turbineRows.stream().mapToDouble(r -> toDouble(r.get("productie_total_mwh"))).sum();
                double avgAvail    = turbineRows.stream().mapToDouble(r -> toDouble(r.get("availability_pct"))).average().orElse(0);
                double maxPower    = turbineRows.stream().mapToDouble(r -> toDouble(r.get("max_power_kw"))).max().orElse(0);
                double avgWind     = turbineRows.stream().mapToDouble(r -> toDouble(r.get("avg_wind_ms"))).average().orElse(0);
                long   green = turbineRows.stream().filter(r -> "GREEN".equals(r.get("status_curent"))).count();
                long   yellow= turbineRows.stream().filter(r -> "YELLOW".equals(r.get("status_curent"))).count();
                long   orange= turbineRows.stream().filter(r -> "ORANGE".equals(r.get("status_curent"))).count();
                long   red   = turbineRows.stream().filter(r -> "RED".equals(r.get("status_curent"))).count();

                Map<String, Object> stats = new LinkedHashMap<>();
                stats.put("totalMwh",       round2(totalMwh));
                stats.put("avgAvailability",round2(avgAvail));
                stats.put("maxPowerKw",     round2(maxPower));
                stats.put("avgWindMs",      round2(avgWind));
                stats.put("greenCount",  green);  stats.put("yellowCount", yellow);
                stats.put("orangeCount", orange); stats.put("redCount",    red);
                stats.put("totalTurbines", turbineRows.size());
                parkReport.put("stats", stats);

                // ── Grafic wind orar ──────────────────────────────────────────
                List<Map<String, Object>> windChart = new ArrayList<>();
                if (tableExists(conn, "raport_wind_orar")) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT ora,avg_wind_ms,avg_power_kw,productie_mwh FROM raport_wind_orar WHERE park_id=? ORDER BY ora")) {
                        ps.setString(1, pid); ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            Map<String, Object> pt = new LinkedHashMap<>();
                            pt.put("ora",          rs.getString("ora"));
                            pt.put("avg_wind_ms",  round2(rs.getDouble("avg_wind_ms")));
                            pt.put("avg_power_kw", round2(rs.getDouble("avg_power_kw")));
                            pt.put("productie_mwh",round2(rs.getDouble("productie_mwh")));
                            windChart.add(pt);
                        }
                    }
                }
                if (windChart.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT substr(timestamp,1,13) AS ora,
                               AVG(wd_spd) AS avg_wind_ms,
                               AVG(CASE WHEN act_pwt>0 THEN act_pwt ELSE 0 END) AS avg_power_kw,
                               SUM(CASE WHEN act_pwt>0 THEN act_pwt/1000.0/60.0 ELSE 0 END) AS productie_mwh
                        FROM raw_history WHERE park_id=? AND timestamp>=? AND wd_spd IS NOT NULL
                        GROUP BY substr(timestamp,1,13) ORDER BY ora
                        """)) {
                        ps.setString(1, pid); ps.setString(2, st);
                        ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            Map<String, Object> pt = new LinkedHashMap<>();
                            pt.put("ora",          rs.getString("ora"));
                            pt.put("avg_wind_ms",  round2(rs.getDouble("avg_wind_ms")));
                            pt.put("avg_power_kw", round2(rs.getDouble("avg_power_kw")));
                            pt.put("productie_mwh",round2(rs.getDouble("productie_mwh")));
                            windChart.add(pt);
                        }
                    }
                }
                parkReport.put("windChart", windChart);

                // ── Curba de putere (real vs OEM) ────────────────────────────
                List<Map<String, Object>> powerCurve = new ArrayList<>();
                if (tableExists(conn, "raport_curba_putere")) {
                    try (PreparedStatement ps = conn.prepareStatement("""
                        SELECT wind_bin,avg_power_real,avg_power_theoretical,n
                        FROM raport_curba_putere WHERE fk_wtg_model_id=(
                          SELECT DISTINCT fk_wtg_model_id FROM raw_history WHERE park_id=? LIMIT 1
                        ) ORDER BY wind_bin
                        """)) {
                        ps.setString(1, pid); ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            Map<String, Object> pt = new LinkedHashMap<>();
                            pt.put("windBin",         round2(rs.getDouble("wind_bin")));
                            pt.put("realPower",       round2(rs.getDouble("avg_power_real")));
                            pt.put("theoreticalPower",round2(rs.getDouble("avg_power_theoretical")));
                            pt.put("n",               rs.getInt("n"));
                            powerCurve.add(pt);
                        }
                    }
                }
                parkReport.put("powerCurve", powerCurve);

                // ── Distribuție vânt ──────────────────────────────────────────
                List<Map<String, Object>> windDist = new ArrayList<>();
                if (tableExists(conn, "raport_distributie_vant")) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT wind_bin,wind_mid,frecventa,frecventa_pct FROM raport_distributie_vant WHERE park_id=? ORDER BY wind_mid")) {
                        ps.setString(1, pid); ResultSet rs = ps.executeQuery();
                        while (rs.next()) {
                            Map<String, Object> pt = new LinkedHashMap<>();
                            pt.put("bin",    rs.getString("wind_bin"));
                            pt.put("mid",    rs.getDouble("wind_mid"));
                            pt.put("count",  rs.getInt("frecventa"));
                            pt.put("pct",    round2(rs.getDouble("frecventa_pct")));
                            windDist.add(pt);
                        }
                    }
                }
                parkReport.put("windDist", windDist);

                // ── Raport modele ─────────────────────────────────────────────
                List<Map<String, Object>> models = new ArrayList<>();
                if (tableExists(conn, "raport_zilnic_modele")) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT * FROM raport_zilnic_modele")) {
                        ResultSet rs = ps.executeQuery();
                        ResultSetMetaData meta = rs.getMetaData();
                        while (rs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= meta.getColumnCount(); i++)
                                row.put(meta.getColumnName(i), rs.getObject(i));
                            models.add(row);
                        }
                    }
                }
                parkReport.put("models", models);

                result.add(parkReport);
            }
        } catch (Exception e) {
            System.err.println("Report error: " + e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(result);
    }

    // =========================================================================
    // 4. DASHBOARD (lista parcuri cu statistici)
    // GET /api/dashboard
    // =========================================================================
    @GetMapping("/dashboard")
    public ResponseEntity<Object> getDashboard() {
        List<Map<String, Object>> parks = new ArrayList<>();
        String[][] dbs = {{LIVE_PATH, "live_measurements"}, {HIST_PATH, "raw_history"}};
        for (String[] db : dbs) {
            if (!new File(db[0]).exists()) continue;
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db[0])) {
                String sql = "SELECT DISTINCT park_id, COUNT(DISTINCT turbine_id) AS cnt, fk_wtg_model_id FROM "
                    + db[1] + " GROUP BY park_id";
                try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String pid = rs.getString("park_id");
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("parkId",       pid);
                        p.put("parkName",     PARK_NAMES.getOrDefault(pid, pid));
                        p.put("totalTurbines",rs.getInt("cnt"));
                        p.put("model",        rs.getString("fk_wtg_model_id"));
                        parks.add(p);
                    }
                }
                if (!parks.isEmpty()) break;
            } catch (Exception ignored) {}
        }
        return ResponseEntity.ok(parks);
    }

    // =========================================================================
    // 5.5 NOTIFICĂRI LIVE — din ppc_energy.db (live_measurements)
    // GET /api/notifications
    // =========================================================================
    @GetMapping("/notifications")
    public ResponseEntity<Object> getNotifications(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {

        List<Map<String, Object>> notifications = new ArrayList<>();
        String[][] dbs = {{LIVE_PATH, "live_measurements"}, {HIST_PATH, "raw_history"}};

        for (String[] db : dbs) {
            if (!new File(db[0]).exists()) continue;
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + db[0])) {
                boolean hasSW = colExists(conn, db[1], "sensor_warnings");
                boolean hasIA = colExists(conn, db[1], "is_anomaly");
                boolean hasAR = colExists(conn, db[1], "anomaly_reason");
                boolean hasSC = colExists(conn, db[1], "status_color");
                boolean hasWS = colExists(conn, db[1], "wd_spd");

                String sw  = hasSW  ? "sensor_warnings"  : "''";
                String ia  = hasIA  ? "is_anomaly"        : "0";
                String ar  = hasAR  ? "anomaly_reason"    : "''";
                String sc  = hasSC  ? "status_color"      : "'RED'";
                String ws  = hasWS  ? "wd_spd"            : "NULL";

                // Return turbines with active anomalies or sensor warnings
                // One row per turbine (the most recent record with issue)
                String sql = String.format("""
                    SELECT park_id, turbine_id, %s AS status_color,
                           %s AS anomaly_reason, %s AS sensor_warnings,
                           act_pwt, %s AS wind_speed, timestamp
                    FROM %s
                    WHERE (%s = 1 OR (%s IS NOT NULL AND %s != ''))
                      AND timestamp = (
                        SELECT MAX(t2.timestamp) FROM %s t2
                        WHERE t2.turbine_id = %s.turbine_id
                          AND t2.park_id = %s.park_id
                          AND (%s = 1 OR (%s IS NOT NULL AND %s != ''))
                      )
                    ORDER BY timestamp DESC
                    LIMIT ?
                    """, sc, ar, sw, ws, db[1],
                    ia, sw, sw, db[1], db[1], db[1],
                    ia, sw, sw);

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setInt(1, limit);
                    ResultSet rs = ps.executeQuery();
                    while (rs.next()) {
                        Map<String, Object> n = new LinkedHashMap<>();
                        n.put("parkId",         rs.getString("park_id"));
                        n.put("turbineId",      rs.getString("turbine_id"));
                        n.put("statusColor",    rs.getString("status_color"));
                        n.put("anomalyReason",  rs.getString("anomaly_reason"));
                        n.put("sensorWarnings", rs.getString("sensor_warnings"));
                        n.put("actPwt",         rs.getObject("act_pwt"));
                        n.put("windSpeed",      rs.getObject("wind_speed"));
                        n.put("timestamp",      rs.getString("timestamp"));
                        notifications.add(n);
                    }
                }
                if (!notifications.isEmpty()) break;
            } catch (Exception e) {
                System.err.println("Notifications error: " + e.getMessage());
            }
        }
        return ResponseEntity.ok(notifications);
    }

    // =========================================================================
    // 6. AUTH ADMIN
    // =========================================================================
    @GetMapping("/auth")
    public Map<String, String> checkAuth(@RequestHeader(value = "X-API-KEY", required = false) String apiKey) {
        Map<String, String> r = new HashMap<>();
        if ("PPC_HACK_2026_TOKEN".equals(apiKey)) {
            r.put("status", "success"); r.put("message", "Authenticated"); r.put("role", "admin");
        } else { r.put("status", "error"); r.put("message", "Invalid API Key."); }
        return r;
    }

    // =========================================================================
    // 6. ADMIN — ALOCARE PARCURI
    // =========================================================================
    @PostMapping("/admin/assign-parks")
    public ResponseEntity<Map<String, String>> assignParks(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "X-API-KEY", required = false) String apiKey) {
        Map<String, String> r = new HashMap<>();
        if (!"PPC_HACK_2026_TOKEN".equals(apiKey)) {
            r.put("status","error"); r.put("message","Acces refuzat."); return ResponseEntity.status(403).body(r);
        }
        String company = (String) body.get("companyName");
        @SuppressWarnings("unchecked") List<String> parks = (List<String>) body.get("parks");
        if (company == null || parks == null) {
            r.put("status","error"); r.put("message","Câmpuri obligatorii lipsă."); return ResponseEntity.badRequest().body(r);
        }
        try {
            Map<String, Object> data = readJson();
            @SuppressWarnings("unchecked") List<Map<String, Object>> companies = (List<Map<String, Object>>) data.get("companies");
            boolean found = false;
            for (Map<String, Object> c : companies) {
                if (company.equals(c.get("companyName"))) { c.put("assignedParks", parks); found = true; break; }
            }
            if (!found) { r.put("status","error"); r.put("message","Compania nu există."); return ResponseEntity.badRequest().body(r); }
            writeJson(data);
            r.put("status","success"); r.put("message","Parcuri alocate.");
            return ResponseEntity.ok(r);
        } catch (Exception e) {
            r.put("status","error"); r.put("message","Eroare: "+e.getMessage());
            return ResponseEntity.internalServerError().body(r);
        }
    }

    // =========================================================================
    // 7. ADMIN — LISTA COMPANII
    // =========================================================================
    @GetMapping("/admin/companies")
    public ResponseEntity<Object> getAllCompanies(@RequestHeader(value = "X-API-KEY", required = false) String apiKey) {
        Map<String, String> err = new HashMap<>();
        if (!"PPC_HACK_2026_TOKEN".equals(apiKey)) {
            err.put("status","error"); err.put("message","Acces refuzat."); return ResponseEntity.status(403).body(err);
        }
        try {
            Map<String, Object> data = readJson();
            @SuppressWarnings("unchecked") List<Map<String, Object>> companies = (List<Map<String, Object>>) data.get("companies");
            List<Map<String, Object>> result = new ArrayList<>();
            for (Map<String, Object> c : companies) {
                Map<String, Object> safe = new LinkedHashMap<>();
                safe.put("companyName",  c.get("companyName"));
                safe.put("assignedParks",c.getOrDefault("assignedParks", new ArrayList<>()));
                result.add(safe);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            err.put("status","error"); err.put("message",e.getMessage());
            return ResponseEntity.internalServerError().body(err);
        }
    }
}