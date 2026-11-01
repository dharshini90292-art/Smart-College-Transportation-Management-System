import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lib.DatabaseConnection;

/**
 * RoutePlanner provides pure-Java attendance-based route evaluation, Dijkstra-based
 * shortest connecting path calculation, and SVG transit map generation.
 *
 * Strict Project Specifications:
 * - Pure Java, HTML, CSS, and MySQL only (Strictly Zero JavaScript).
 * - Preserves scheduled pickup order from Stop 1 to Stop 10, bypassing confirmed skipped stops
 *   where graph connections permit.
 * - Dijkstra's shortest-path algorithm calculates the shortest road path between consecutive
 *   required stops, intermediate road intersections, and the college.
 * - Exact Attendance Rule: A stop is SKIPPED ONLY when BOTH assigned students are confirmed ABSENT.
 * - Stops with missing/unconfirmed attendance, unauthorized students, or incomplete assignments
 *   are NEVER skipped based on missing data; they remain active with clear warnings.
 * - Simulated Route Map — Not Live GPS: Demonstration road network with no live GPS claims.
 */
final class RoutePlanner {

    static final String COLLEGE_DESTINATION_NAME = "Campus";
    static final String COLLEGE_KEY = "COLLEGE";
    static final int COLLEGE_NODE = 14;

    private final DijkstraRouteFinder dijkstraRouteFinder = new DijkstraRouteFinder();

    // Bus definitions
    private static final Map<String, BusNetworkConfig> BUS_CONFIGS = new LinkedHashMap<>();

    static {
        // Bus 1: Stops 1 to 10 (North Sector Corridor)
        // Nodes: 0=Depot, 1..10=Stops 1..10, 11=Junction A (Ring Bypass), 12=Junction B (Expressway), 13=Junction C (East Link), 14=College
        BUS_CONFIGS.put("BUS-01", new BusNetworkConfig(
                "BUS-01", "Bus 01", "Bus 1 Depot (North Terminal)", 1, 10,
                new String[]{
                        "Stop 1 (Hebbal Circle)",
                        "Stop 2 (Nagawara Junction)",
                        "Stop 3 (Hennur Cross)",
                        "Stop 4 (Kalyan Nagar)",
                        "Stop 5 (Kammanahalli)",
                        "Stop 6 (Ramamurthy Nagar)",
                        "Stop 7 (Kasturi Nagar)",
                        "Stop 8 (Tin Factory)",
                        "Stop 9 (KR Puram Bridge)",
                        "Stop 10 (Mahadevapura)"
                },
                new RoadEdge[]{
                        // Consecutive arterial road segments between adjacent stops
                        new RoadEdge(0, 1, 2.4),
                        new RoadEdge(1, 2, 1.8),
                        new RoadEdge(2, 3, 2.1),
                        new RoadEdge(3, 4, 1.9),
                        new RoadEdge(4, 5, 2.3),
                        new RoadEdge(5, 6, 2.0),
                        new RoadEdge(6, 7, 1.7),
                        new RoadEdge(7, 8, 2.2),
                        new RoadEdge(8, 9, 2.5),
                        new RoadEdge(9, 10, 1.6),
                        new RoadEdge(10, 14, 3.2),
                        // Direct bypass roads where connections permit skipping intermediate stops
                        new RoadEdge(1, 3, 3.5),
                        new RoadEdge(2, 4, 3.8),
                        new RoadEdge(3, 5, 3.7),
                        new RoadEdge(4, 6, 4.1),
                        new RoadEdge(5, 7, 3.9),
                        new RoadEdge(6, 8, 3.6),
                        new RoadEdge(7, 9, 4.3),
                        new RoadEdge(8, 10, 4.6),
                        new RoadEdge(9, 14, 4.5),
                        new RoadEdge(8, 14, 5.9),
                        // Intermediate Road Intersections (Connecting & Bypass Nodes)
                        // Junction A (Node 11): Ring Road Bypass
                        new RoadEdge(0, 11, 3.6),
                        new RoadEdge(1, 11, 2.0),
                        new RoadEdge(3, 11, 1.8),
                        new RoadEdge(4, 11, 2.2),
                        new RoadEdge(11, 12, 3.5),
                        // Junction B (Node 12): Outer Expressway
                        new RoadEdge(4, 12, 2.1),
                        new RoadEdge(6, 12, 1.9),
                        new RoadEdge(7, 12, 2.0),
                        new RoadEdge(12, 13, 3.8),
                        // Junction C (Node 13): East Corridor Link
                        new RoadEdge(7, 13, 2.3),
                        new RoadEdge(9, 13, 2.0),
                        new RoadEdge(10, 13, 1.5),
                        new RoadEdge(13, 14, 2.8)
                }
        ));

        // Bus 2: Stops 11 to 20 (South Sector Corridor)
        // Nodes: 0=Depot, 1..10=Stops 11..20, 11=Junction A-South, 12=Junction B-South, 13=Junction C-South, 14=College
        BUS_CONFIGS.put("BUS-02", new BusNetworkConfig(
                "BUS-02", "Bus 02", "Bus 2 Depot (South Terminal)", 11, 20,
                new String[]{
                        "Stop 11 (Silk Board Junction)",
                        "Stop 12 (BTM 2nd Stage)",
                        "Stop 13 (Jayadeva Circle)",
                        "Stop 14 (JP Nagar 4th Phase)",
                        "Stop 15 (Bannerghatta Road)",
                        "Stop 16 (Arekere Gate)",
                        "Stop 17 (Hulimavu Gate)",
                        "Stop 18 (Gottigere)",
                        "Stop 19 (Electronic City Toll)",
                        "Stop 20 (Velankani Drive)"
                },
                new RoadEdge[]{
                        // Consecutive arterial roads
                        new RoadEdge(0, 1, 2.8),
                        new RoadEdge(1, 2, 2.0),
                        new RoadEdge(2, 3, 2.2),
                        new RoadEdge(3, 4, 2.1),
                        new RoadEdge(4, 5, 1.9),
                        new RoadEdge(5, 6, 2.4),
                        new RoadEdge(6, 7, 2.0),
                        new RoadEdge(7, 8, 2.2),
                        new RoadEdge(8, 9, 2.6),
                        new RoadEdge(9, 10, 2.3),
                        new RoadEdge(10, 14, 3.5),
                        // Direct bypasses
                        new RoadEdge(1, 3, 3.9),
                        new RoadEdge(2, 4, 4.1),
                        new RoadEdge(3, 5, 3.8),
                        new RoadEdge(4, 6, 3.7),
                        new RoadEdge(5, 7, 4.3),
                        new RoadEdge(6, 8, 3.9),
                        new RoadEdge(7, 9, 4.4),
                        new RoadEdge(8, 10, 4.7),
                        new RoadEdge(9, 14, 5.2),
                        new RoadEdge(8, 14, 6.1),
                        // Intermediate intersections
                        new RoadEdge(0, 11, 3.8),
                        new RoadEdge(1, 11, 2.2),
                        new RoadEdge(3, 11, 1.9),
                        new RoadEdge(4, 11, 2.4),
                        new RoadEdge(11, 12, 3.6),
                        new RoadEdge(4, 12, 2.3),
                        new RoadEdge(6, 12, 2.1),
                        new RoadEdge(7, 12, 2.2),
                        new RoadEdge(12, 13, 3.9),
                        new RoadEdge(7, 13, 2.4),
                        new RoadEdge(9, 13, 2.1),
                        new RoadEdge(10, 13, 1.8),
                        new RoadEdge(13, 14, 3.0)
                }
        ));

        // Bus 3: Stops 21 to 30 (Central Corridor)
        // Nodes: 0=Depot, 1..10=Stops 21..30, 11=Junction A-Central, 12=Junction B-Central, 13=Junction C-Central, 14=College
        BUS_CONFIGS.put("BUS-03", new BusNetworkConfig(
                "BUS-03", "Bus 03", "Bus 3 Depot (Central Terminal)", 21, 30,
                new String[]{
                        "Stop 21 (Majestic Central)",
                        "Stop 22 (Corporation Circle)",
                        "Stop 23 (Lalbagh West Gate)",
                        "Stop 24 (South End Circle)",
                        "Stop 25 (Jayanagar 4th Block)",
                        "Stop 26 (Banashankari TTMC)",
                        "Stop 27 (Kumaraswamy Layout)",
                        "Stop 28 (ISRO Layout)",
                        "Stop 29 (Konanakunte Cross)",
                        "Stop 30 (Kanakapura Road)"
                },
                new RoadEdge[]{
                        // Consecutive arterial roads
                        new RoadEdge(0, 1, 2.2),
                        new RoadEdge(1, 2, 1.9),
                        new RoadEdge(2, 3, 2.0),
                        new RoadEdge(3, 4, 2.1),
                        new RoadEdge(4, 5, 1.8),
                        new RoadEdge(5, 6, 2.2),
                        new RoadEdge(6, 7, 2.0),
                        new RoadEdge(7, 8, 2.4),
                        new RoadEdge(8, 9, 2.1),
                        new RoadEdge(9, 10, 2.5),
                        new RoadEdge(10, 14, 3.4),
                        // Direct bypasses
                        new RoadEdge(1, 3, 3.6),
                        new RoadEdge(2, 4, 3.8),
                        new RoadEdge(3, 5, 4.0),
                        new RoadEdge(4, 6, 3.5),
                        new RoadEdge(5, 7, 4.1),
                        new RoadEdge(6, 8, 3.9),
                        new RoadEdge(7, 9, 4.5),
                        new RoadEdge(8, 10, 4.3),
                        new RoadEdge(9, 14, 5.0),
                        new RoadEdge(8, 14, 5.8),
                        // Intermediate intersections
                        new RoadEdge(0, 11, 3.4),
                        new RoadEdge(1, 11, 2.1),
                        new RoadEdge(3, 11, 1.9),
                        new RoadEdge(4, 11, 2.3),
                        new RoadEdge(11, 12, 3.4),
                        new RoadEdge(4, 12, 2.0),
                        new RoadEdge(6, 12, 1.8),
                        new RoadEdge(7, 12, 2.1),
                        new RoadEdge(12, 13, 3.6),
                        new RoadEdge(7, 13, 2.2),
                        new RoadEdge(9, 13, 1.9),
                        new RoadEdge(10, 13, 1.6),
                        new RoadEdge(13, 14, 2.9)
                }
        ));
    }

    void initialize() throws SQLException {
        try (Connection connection = DatabaseConnection.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS route_locations ("
                            + "location_type VARCHAR(20) NOT NULL, "
                            + "location_key VARCHAR(150) NOT NULL, "
                            + "bus_id VARCHAR(30) NULL, "
                            + "latitude DECIMAL(10,7) NOT NULL, "
                            + "longitude DECIMAL(10,7) NOT NULL, "
                            + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP "
                            + "ON UPDATE CURRENT_TIMESTAMP, "
                            + "PRIMARY KEY (location_type, location_key))");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS bus_route_cache ("
                            + "bus_id VARCHAR(30) NOT NULL PRIMARY KEY, "
                            + "input_signature CHAR(64) NOT NULL, "
                            + "route_sequence LONGTEXT NOT NULL, "
                            + "distance_meters DOUBLE NOT NULL, "
                            + "duration_seconds DOUBLE NOT NULL, "
                            + "updated_reason VARCHAR(250) NOT NULL, "
                            + "calculated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP "
                            + "ON UPDATE CURRENT_TIMESTAMP)");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS stops ("
                            + "stop_id INT AUTO_INCREMENT PRIMARY KEY, "
                            + "stop_code VARCHAR(50) NOT NULL UNIQUE, "
                            + "stop_name VARCHAR(150) NOT NULL, "
                            + "bus_id VARCHAR(30) NOT NULL DEFAULT 'BUS-01', "
                            + "latitude DECIMAL(10,7) NOT NULL, "
                            + "longitude DECIMAL(10,7) NOT NULL, "
                            + "stop_order INT NOT NULL DEFAULT 1, "
                            + "is_active_override TINYINT(1) NOT NULL DEFAULT 0, "
                            + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                            + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS route_date_cache ("
                            + "cache_id INT AUTO_INCREMENT PRIMARY KEY, "
                            + "bus_id VARCHAR(30) NOT NULL, "
                            + "route_date DATE NOT NULL, "
                            + "status VARCHAR(20) NOT NULL DEFAULT 'CALCULATED', "
                            + "distance_meters DOUBLE NOT NULL, "
                            + "duration_seconds DOUBLE NOT NULL, "
                            + "ordered_stop_codes TEXT NOT NULL, "
                            + "skipped_stop_codes TEXT NOT NULL, "
                            + "geojson_geometry LONGTEXT NULL, "
                            + "last_updated TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                            + "UNIQUE KEY uq_bus_date (bus_id, route_date))");

            // Ensure road_network_edges table exists in transport_db
            dijkstraRouteFinder.ensureTable(connection);

            // Seed stops (Stops 1 to 10) preserving existing records
            String[][] allTenStops = new String[][]{
                    {"Stop 1", "Coimbatore Railway Junction", "BUS-01", "11.0016", "76.9665", "1"},
                    {"Stop 2", "Ukkadam Bus Terminal", "BUS-01", "10.9897", "76.9602", "2"},
                    {"Stop 3", "Sundarapuram Junction", "BUS-01", "10.9482", "76.9714", "3"},
                    {"Stop 4", "Kuniyamuthur", "BUS-01", "10.9635", "76.9421", "4"},
                    {"Stop 5", "Kovaipudur Junction", "BUS-01", "10.9387", "76.9328", "5"},
                    {"Stop 6", "Perur Temple Junction", "BUS-01", "10.9708", "76.9205", "6"},
                    {"Stop 7", "Thondamuthur Main Junction", "BUS-01", "10.9984", "76.8372", "7"},
                    {"Stop 8", "Alandurai Junction", "BUS-01", "10.9572", "76.7865", "8"},
                    {"Stop 9", "Pooluvapatti", "BUS-01", "10.9610", "76.8020", "9"},
                    {"Stop 10", "Narasipuram", "BUS-01", "10.9680", "76.7750", "10"}
            };
            try (PreparedStatement stopPs = connection.prepareStatement(
                    "INSERT IGNORE INTO stops (stop_code, stop_name, bus_id, latitude, longitude, stop_order) "
                            + "VALUES (?, ?, ?, ?, ?, ?)")) {
                for (String[] st : allTenStops) {
                    stopPs.setString(1, st[0]);
                    stopPs.setString(2, st[1]);
                    stopPs.setString(3, st[2]);
                    stopPs.setDouble(4, Double.parseDouble(st[3]));
                    stopPs.setDouble(5, Double.parseDouble(st[4]));
                    stopPs.setInt(6, Integer.parseInt(st[5]));
                    stopPs.executeUpdate();
                }
            }

            // Ensure students exist for all 10 stops so attendance rerouting can evaluate every stop
            String[][] extraStudents = new String[][]{
                    {"25CSR015", "Karthik", "BUS-01", "Stop 7"},
                    {"25CSR016", "Sneha", "BUS-01", "Stop 8"},
                    {"25CSR017", "Dinesh", "BUS-01", "Stop 9"},
                    {"25CSR018", "Ananya", "BUS-01", "Stop 10"}
            };
            try (PreparedStatement stuPs = connection.prepareStatement(
                    "INSERT IGNORE INTO students (student_id, name, bus_id, stop_name, transport_authorized, password_hash, department, academic_year) "
                            + "VALUES (?, ?, ?, ?, 1, 'student123', 'Computer Science and Engineering (CSE)', '2nd Year')")) {
                for (String[] stu : extraStudents) {
                    stuPs.setString(1, stu[0]);
                    stuPs.setString(2, stu[1]);
                    stuPs.setString(3, stu[2]);
                    stuPs.setString(4, stu[3]);
                    stuPs.executeUpdate();
                }
            }

            // Ensure route_locations has real depot and campus coordinates
            statement.executeUpdate(
                    "INSERT INTO route_locations (location_type, location_key, bus_id, latitude, longitude) "
                            + "VALUES ('BUS_START', 'BUS-01', 'BUS-01', 11.0168, 76.9558) "
                            + "ON DUPLICATE KEY UPDATE latitude = VALUES(latitude), longitude = VALUES(longitude)");
            statement.executeUpdate(
                    "INSERT INTO route_locations (location_type, location_key, bus_id, latitude, longitude) "
                            + "VALUES ('COLLEGE', 'COLLEGE', NULL, 10.9632, 76.8142) "
                            + "ON DUPLICATE KEY UPDATE latitude = VALUES(latitude), longitude = VALUES(longitude)");
            statement.executeUpdate("UPDATE students SET stop_name = TRIM(TRAILING '\r' FROM TRIM(stop_name))");
        }
    }

    static String normalizeBusId(String input) {
        if (input == null) return "BUS-01";
        String trimmed = input.trim();
        if (trimmed.equalsIgnoreCase("BUS-01") || trimmed.equalsIgnoreCase("Bus 01")
                || trimmed.equalsIgnoreCase("Bus #01") || trimmed.equalsIgnoreCase("Bus 1")
                || trimmed.equals("1")) {
            return "BUS-01";
        }
        if (trimmed.equalsIgnoreCase("BUS-02") || trimmed.equalsIgnoreCase("Bus 02")
                || trimmed.equalsIgnoreCase("Bus #02") || trimmed.equalsIgnoreCase("Bus 2")
                || trimmed.equals("2")) {
            return "BUS-02";
        }
        if (trimmed.equalsIgnoreCase("BUS-03") || trimmed.equalsIgnoreCase("Bus 03")
                || trimmed.equalsIgnoreCase("Bus #03") || trimmed.equalsIgnoreCase("Bus 3")
                || trimmed.equals("3")) {
            return "BUS-03";
        }
        if (trimmed.matches("(?i)BUS-\\d+")) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        return "BUS-01";
    }

    static List<String> getManagedBusIds() {
        return Collections.unmodifiableList(Arrays.asList("BUS-01", "BUS-02", "BUS-03"));
    }

    static class ConfiguredStopInfo {
        final String stopCode;
        final String stopName;
        final String corridor;
        final String busId;

        ConfiguredStopInfo(String stopCode, String stopName, String corridor, String busId) {
            this.stopCode = stopCode;
            this.stopName = stopName;
            this.corridor = corridor;
            this.busId = busId;
        }
    }

    static List<ConfiguredStopInfo> getAllConfiguredStops() {
        List<ConfiguredStopInfo> list = new ArrayList<>();
        for (Map.Entry<String, BusNetworkConfig> entry : BUS_CONFIGS.entrySet()) {
            String busId = entry.getKey();
            BusNetworkConfig cfg = entry.getValue();
            String corridor = "BUS-01".equals(busId) ? "North Sector Corridor"
                    : ("BUS-02".equals(busId) ? "South Sector Corridor" : "East Sector Corridor");
            for (int i = 0; i < cfg.stopLabels.length; i++) {
                String full = cfg.stopLabels[i];
                String code = "Stop " + (cfg.startStopNum + i);
                String name = full;
                int parenOpen = full.indexOf('(');
                int parenClose = full.lastIndexOf(')');
                if (parenOpen != -1 && parenClose > parenOpen) {
                    name = full.substring(parenOpen + 1, parenClose).trim();
                }
                list.add(new ConfiguredStopInfo(code, name, corridor, busId));
            }
        }
        return list;
    }

    /**
     * Calculates the route for a specified bus using simulated road graph distances,
     * student attendance records from MySQL for the current date, and Dijkstra's algorithm.
     *
     * Exact Rules Applied:
     * 1. Preserves scheduled pickup order from Stop 1 to Stop 10.
     * 2. Bypasses confirmed skipped stops where graph connections permit.
     * 3. A stop is SKIPPED ONLY when BOTH assigned students are confirmed ABSENT.
     * 4. If attendance is missing or unconfirmed: DO NOT SKIP; mark ATTENDANCE NOT CONFIRMED.
     * 5. Unauthorized students (transport_authorized = 0) do NOT count toward pickup.
     * 6. Stops with no valid authorized students are NOT skipped; a warning is displayed.
     * 7. Dijkstra finds shortest connecting paths between consecutive active stops and college.
     */
    BusRoute calculate(String busIdInput, Map<String, String> attendanceByStudent) throws SQLException {
        String busId = normalizeBusId(busIdInput);
        BusNetworkConfig config = BUS_CONFIGS.get(busId);
        if (config == null) {
            config = BUS_CONFIGS.get("BUS-01");
            busId = "BUS-01";
        }

        LocalDate today = LocalDate.now();

        // 1. Fetch latest attendance from MySQL database for today
        Map<String, AttendanceRecord> todayAttendance = new HashMap<>();
        String attendanceSql = "SELECT student_id, status, absence_reason FROM attendance WHERE attendance_date = ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(attendanceSql)) {
            ps.setDate(1, Date.valueOf(today));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sid = rs.getString("student_id");
                    String stat = rs.getString("status");
                    String reason = rs.getString("absence_reason");
                    if (sid != null) {
                        todayAttendance.put(sid.trim(), new AttendanceRecord(stat, reason));
                    }
                }
            }
        } catch (SQLException ignore) {
            // Safe fallback if database table is empty or error occurs
        }

        // Overlay in-memory attendance statuses if supplied
        if (attendanceByStudent != null) {
            for (Map.Entry<String, String> entry : attendanceByStudent.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    AttendanceRecord existing = todayAttendance.get(entry.getKey());
                    String reason = existing != null ? existing.absenceReason : null;
                    todayAttendance.put(entry.getKey(), new AttendanceRecord(entry.getValue(), reason));
                }
            }
        }

        // 2. Fetch authorized students assigned to this bus
        Map<String, List<AssignedStudent>> studentsByStop = new LinkedHashMap<>();
        String studentSql = "SELECT student_id, name, stop_name, transport_authorized "
                + "FROM students WHERE (bus_id = ? OR bus_id = ? OR bus_id = ?) "
                + "ORDER BY stop_name, student_id";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement statement = con.prepareStatement(studentSql)) {
            statement.setString(1, busId);
            statement.setString(2, config.busLabel);
            statement.setString(3, "Bus #" + busId.substring(4));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String studentId = rs.getString("student_id");
                    String studentName = rs.getString("name");
                    String rawStop = rs.getString("stop_name");
                    boolean authorized = rs.getBoolean("transport_authorized");

                    // Rule: Unauthorized students must NOT be counted as requiring pickup
                    if (!authorized) {
                        continue;
                    }

                    if (rawStop != null && !rawStop.trim().isEmpty()) {
                        AttendanceRecord att = todayAttendance.get(studentId);
                        String status = att != null && att.status != null
                                ? att.status.toUpperCase(Locale.ROOT)
                                : "UNCONFIRMED";
                        String reason = att != null ? att.absenceReason : null;

                        AssignedStudent student = new AssignedStudent(
                                studentId, studentName != null ? studentName : studentId,
                                rawStop.trim(), status, reason, authorized);

                        studentsByStop.computeIfAbsent(rawStop.trim(), k -> new ArrayList<>()).add(student);
                    }
                }
            }
        }

        // 3. Build the 10 stops for this bus
        List<Stop> allStops = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int stopNumber = config.startStopNum + i;
            String stopName = "Stop " + stopNumber;
            String label = config.stopLabels[i];
            int graphNodeIndex = i + 1; // 1 to 10
            Stop stop = new Stop(stopName, label, stopNumber, graphNodeIndex);
            allStops.add(stop);
        }

        // Map authorized students to the stops
        for (Map.Entry<String, List<AssignedStudent>> entry : studentsByStop.entrySet()) {
            Stop matchedStop = findMatchingStop(entry.getKey(), allStops, config);
            if (matchedStop != null) {
                matchedStop.assignedStudents.addAll(entry.getValue());
            }
        }

        // 4. Apply the exact attendance rule for each stop
        List<Stop> requiredStops = new ArrayList<>();
        List<Stop> skippedStops = new ArrayList<>();
        List<Stop> unconfirmedStops = new ArrayList<>();
        List<Stop> warningStops = new ArrayList<>();

        for (Stop stop : allStops) {
            evaluateStopAttendance(stop);

            if (stop.status == StopStatus.SKIPPED) {
                skippedStops.add(stop);
            } else {
                requiredStops.add(stop);
                if (stop.status == StopStatus.UNCONFIRMED) {
                    unconfirmedStops.add(stop);
                }
                if (stop.status == StopStatus.WARNING) {
                    warningStops.add(stop);
                }
            }
        }

        // 5. Construct the weighted road graph and run Dijkstra's algorithm
        // 15 vertices: 0=Depot, 1..10=Stops, 11=Junction A, 12=Junction B, 13=Junction C, 14=College
        SimulatedGraph graph = new SimulatedGraph(15, config.edges);

        // Sequence of waypoints in scheduled pickup order (Depot -> Stop 1..10 active -> College)
        List<Integer> routeWaypoints = new ArrayList<>();
        routeWaypoints.add(0); // Depot
        for (Stop req : requiredStops) {
            routeWaypoints.add(req.graphNodeIndex);
        }
        routeWaypoints.add(COLLEGE_NODE); // College

        // Compute shortest connecting paths using Dijkstra
        double totalDistanceKm = 0.0;
        boolean allLegsReachable = true;
        List<String> unreachableMessages = new ArrayList<>();
        List<Integer> fullPathNodes = new ArrayList<>();
        List<DijkstraLeg> legs = new ArrayList<>();

        for (int i = 0; i < routeWaypoints.size() - 1; i++) {
            int fromNode = routeWaypoints.get(i);
            int toNode = routeWaypoints.get(i + 1);

            DijkstraResult result = graph.dijkstraShortestPath(fromNode, toNode);
            legs.add(new DijkstraLeg(fromNode, toNode, result));

            if (!result.reachable) {
                allLegsReachable = false;
                String toName = getNodeDisplayName(toNode, config);
                unreachableMessages.add("Cannot reach destination or stop: " + toName);
            } else {
                totalDistanceKm += result.distance;
                if (fullPathNodes.isEmpty()) {
                    fullPathNodes.addAll(result.path);
                } else {
                    for (int p = 1; p < result.path.size(); p++) {
                        fullPathNodes.add(result.path.get(p));
                    }
                }
            }
        }

        double distanceMeters = totalDistanceKm * 1000.0;
        // Average transit speed: 32 km/h
        double durationSeconds = (totalDistanceKm / 32.0) * 3600.0;

        Location startLoc = new Location(12.9716, 77.5946);
        Location collegeLoc = new Location(13.0827, 80.2707);

        String signature = inputSignature(busId, startLoc, collegeLoc, allStops);
        String updateReason;
        if (!skippedStops.isEmpty()) {
            StringBuilder sb = new StringBuilder("Route updated: ");
            for (int s = 0; s < skippedStops.size(); s++) {
                if (s > 0) sb.append(", ");
                sb.append(skippedStops.get(s).name);
            }
            sb.append(" bypassed (both assigned students confirmed absent). Shortest connecting path computed via Dijkstra.");
            updateReason = sb.toString();
        } else {
            updateReason = "Full scheduled route active. All stops required.";
        }

        String warningMessage = null;
        if (!allLegsReachable) {
            warningMessage = "Network Warning: One or more route segments are unreachable. "
                    + String.join("; ", unreachableMessages);
        } else if (!warningStops.isEmpty()) {
            warningMessage = "Assignment Notice: " + warningStops.size()
                    + " stop(s) have incomplete/missing student records. Stops kept active for safety.";
        }

        BusRoute route = new BusRoute(
                busId, config.busLabel, config.depotName, startLoc, collegeLoc,
                requiredStops, skippedStops, unconfirmedStops, warningStops, allStops,
                distanceMeters, durationSeconds, signature, updateReason,
                allLegsReachable, warningMessage, config, fullPathNodes, legs
        );

        saveRoute(route);
        return route;
    }

    /**
     * Exact Attendance Rule for evaluating stop status:
     * - Skip a stop ONLY when BOTH students assigned to that stop are confirmed ABSENT for the current date.
     * - Student A PRESENT and Student B PRESENT: REQUIRED stop.
     * - Student A PRESENT and Student B ABSENT: REQUIRED stop.
     * - Student A ABSENT and Student B PRESENT: REQUIRED stop.
     * - Both students confirmed ABSENT: SKIPPED stop.
     * - One or both attendance records missing or unknown: DO NOT skip; mark ATTENDANCE NOT CONFIRMED.
     * - Unauthorized students must not be counted as requiring pickup.
     * - If no valid authorized student assignments exist: show a warning rather than treating missing data as absence.
     */
    static void evaluateStopAttendance(Stop stop) {
        List<AssignedStudent> assigned = stop.assignedStudents;

        if (assigned == null || assigned.isEmpty()) {
            stop.status = StopStatus.WARNING;
            stop.required = true;
            stop.reason = "Configuration Warning: No authorized students assigned (requires 2 students; stop kept active).";
            stop.warning = "No authorized students assigned in database (requires 2 students; stop kept active).";
            return;
        }

        if (assigned.size() == 1) {
            AssignedStudent s1 = assigned.get(0);
            if ("PRESENT".equalsIgnoreCase(s1.status)) {
                stop.status = StopStatus.REQUIRED;
                stop.required = true;
                stop.reason = "Pickup Required: Student " + s1.name + " (" + s1.studentId + ") is Present (Assignment Notice: 2nd student missing).";
                stop.warning = "Incomplete assignment: Only 1 authorized student assigned to this stop (expected 2).";
            } else {
                stop.status = StopStatus.UNCONFIRMED;
                stop.required = true;
                stop.reason = "Attendance Not Confirmed: Cannot confirm stop absence without 2nd assigned student ("
                        + s1.name + " is " + s1.status + "). Stop kept active for passenger safety.";
                stop.warning = "Incomplete assignment: 2nd student missing; cannot confirm full absence.";
            }
            return;
        }

        // Two or more assigned authorized students: take Student A and Student B
        AssignedStudent sA = assigned.get(0);
        AssignedStudent sB = assigned.get(1);

        boolean aPresent = "PRESENT".equalsIgnoreCase(sA.status);
        boolean aAbsent = "ABSENT".equalsIgnoreCase(sA.status);
        boolean bPresent = "PRESENT".equalsIgnoreCase(sB.status);
        boolean bAbsent = "ABSENT".equalsIgnoreCase(sB.status);

        if (aPresent && bPresent) {
            stop.status = StopStatus.REQUIRED;
            stop.required = true;
            stop.reason = "Required: Both assigned students confirmed Present (" + sA.name + ", " + sB.name + ").";
        } else if (aPresent && bAbsent) {
            stop.status = StopStatus.REQUIRED;
            stop.required = true;
            stop.reason = "Required: Student " + sA.name + " (" + sA.studentId + ") is Present (" + sB.studentId + ": Absent).";
        } else if (aAbsent && bPresent) {
            stop.status = StopStatus.REQUIRED;
            stop.required = true;
            stop.reason = "Required: Student " + sB.name + " (" + sB.studentId + ") is Present (" + sA.studentId + ": Absent).";
        } else if (aAbsent && bAbsent) {
            stop.status = StopStatus.SKIPPED;
            stop.required = false;
            stop.skipReason = "Both assigned students confirmed Absent (" + sA.name + " [" + sA.studentId + "]: Absent, "
                    + sB.name + " [" + sB.studentId + "]: Absent). Stop bypassed.";
            stop.reason = stop.skipReason;
        } else if (aPresent) {
            stop.status = StopStatus.REQUIRED;
            stop.required = true;
            stop.reason = "Required: Confirmed present student requires pickup (" + sA.name + " [" + sA.studentId + "]: Present; "
                    + sB.studentId + ": attendance unconfirmed).";
        } else if (bPresent) {
            stop.status = StopStatus.REQUIRED;
            stop.required = true;
            stop.reason = "Required: Confirmed present student requires pickup (" + sB.name + " [" + sB.studentId + "]: Present; "
                    + sA.studentId + ": attendance unconfirmed).";
        } else {
            // Neither is Present, and one or both are unconfirmed -> DO NOT SKIP
            stop.status = StopStatus.UNCONFIRMED;
            stop.required = true;
            String missingWhom = (!aAbsent && !bAbsent)
                    ? "Both student records unrecorded"
                    : (!aAbsent ? sA.studentId : sB.studentId) + " attendance unrecorded";
            stop.reason = "Attendance Not Confirmed: " + missingWhom
                    + " for current date. Stop kept active as safety precaution.";
            stop.warning = "Attendance unconfirmed: Stop kept active. Do not bypass without confirmation.";
        }
    }

    private static Stop findMatchingStop(String rawName, List<Stop> stops, BusNetworkConfig config) {
        if (rawName == null) return null;
        String normalized = rawName.trim();
        for (Stop s : stops) {
            if (s.name.equalsIgnoreCase(normalized) || s.label.equalsIgnoreCase(normalized)) {
                return s;
            }
        }
        // Match by "Stop X" number
        Matcher m = Pattern.compile("(?i)\\bStop\\s*#?\\s*0?(\\d+)\\b").matcher(normalized);
        if (m.find()) {
            int num = Integer.parseInt(m.group(1));
            for (Stop s : stops) {
                if (s.stopNumber == num) {
                    return s;
                }
            }
            // If num is 1..10 and bus start stop is e.g. 11 or 21 (offset mapping)
            if (num >= 1 && num <= 10 && config != null) {
                int offsetNum = config.startStopNum + num - 1;
                for (Stop s : stops) {
                    if (s.stopNumber == offsetNum) {
                        return s;
                    }
                }
            }
        }
        // Substring match
        for (Stop s : stops) {
            if (normalized.toLowerCase(Locale.ROOT).contains(s.name.toLowerCase(Locale.ROOT))) {
                return s;
            }
        }
        return null;
    }

    private static String getNodeDisplayName(int nodeIndex, BusNetworkConfig config) {
        if (nodeIndex == 0) return config.depotName;
        if (nodeIndex >= 1 && nodeIndex <= 10) return config.stopLabels[nodeIndex - 1];
        if (nodeIndex == 11) return "Junction Alpha (Ring Road Bypass)";
        if (nodeIndex == 12) return "Junction Beta (Outer Expressway)";
        if (nodeIndex == 13) return "Junction Gamma (Campus Link)";
        if (nodeIndex == COLLEGE_NODE) return COLLEGE_DESTINATION_NAME;
        return "Node " + nodeIndex;
    }

    static final class MapPoint {
        final int x;
        final int y;
        final String labelPos;

        MapPoint(int x, int y, String labelPos) {
            this.x = x;
            this.y = y;
            this.labelPos = labelPos;
        }
    }

    private static final Map<Integer, MapPoint> STOP_COORDINATES = new HashMap<>();
    static {
        // Bus 1 (Stops 1 to 10 - North Corridor)
        STOP_COORDINATES.put(1, new MapPoint(125, 100, "top"));
        STOP_COORDINATES.put(2, new MapPoint(195, 85, "top"));
        STOP_COORDINATES.put(3, new MapPoint(270, 105, "bottom"));
        STOP_COORDINATES.put(4, new MapPoint(350, 90, "top"));
        STOP_COORDINATES.put(5, new MapPoint(425, 115, "bottom"));
        STOP_COORDINATES.put(6, new MapPoint(500, 145, "top"));
        STOP_COORDINATES.put(7, new MapPoint(575, 185, "bottom"));
        STOP_COORDINATES.put(8, new MapPoint(650, 225, "top"));
        STOP_COORDINATES.put(9, new MapPoint(720, 265, "bottom"));
        STOP_COORDINATES.put(10, new MapPoint(785, 295, "top"));

        // Bus 2 (Stops 11 to 20 - South Corridor)
        STOP_COORDINATES.put(11, new MapPoint(125, 560, "bottom"));
        STOP_COORDINATES.put(12, new MapPoint(195, 570, "bottom"));
        STOP_COORDINATES.put(13, new MapPoint(265, 550, "top"));
        STOP_COORDINATES.put(14, new MapPoint(345, 560, "bottom"));
        STOP_COORDINATES.put(15, new MapPoint(425, 535, "top"));
        STOP_COORDINATES.put(16, new MapPoint(495, 500, "bottom"));
        STOP_COORDINATES.put(17, new MapPoint(570, 465, "top"));
        STOP_COORDINATES.put(18, new MapPoint(645, 425, "bottom"));
        STOP_COORDINATES.put(19, new MapPoint(715, 385, "top"));
        STOP_COORDINATES.put(20, new MapPoint(780, 355, "bottom"));

        // Bus 3 (Stops 21 to 30 - Central Corridor)
        STOP_COORDINATES.put(21, new MapPoint(125, 330, "top"));
        STOP_COORDINATES.put(22, new MapPoint(190, 315, "bottom"));
        STOP_COORDINATES.put(23, new MapPoint(260, 345, "top"));
        STOP_COORDINATES.put(24, new MapPoint(340, 325, "bottom"));
        STOP_COORDINATES.put(25, new MapPoint(420, 335, "top"));
        STOP_COORDINATES.put(26, new MapPoint(490, 315, "bottom"));
        STOP_COORDINATES.put(27, new MapPoint(560, 340, "top"));
        STOP_COORDINATES.put(28, new MapPoint(635, 320, "bottom"));
        STOP_COORDINATES.put(29, new MapPoint(705, 335, "top"));
        STOP_COORDINATES.put(30, new MapPoint(775, 325, "bottom"));
    }

    private static final Map<String, Map<Integer, MapPoint>> INTERMEDIATE_JUNCTIONS = new HashMap<>();
    static {
        Map<Integer, MapPoint> b1J = new HashMap<>();
        b1J.put(11, new MapPoint(270, 180, "bottom"));
        b1J.put(12, new MapPoint(490, 210, "top"));
        b1J.put(13, new MapPoint(710, 210, "top"));
        INTERMEDIATE_JUNCTIONS.put("BUS-01", b1J);

        Map<Integer, MapPoint> b2J = new HashMap<>();
        b2J.put(11, new MapPoint(265, 470, "top"));
        b2J.put(12, new MapPoint(495, 430, "bottom"));
        b2J.put(13, new MapPoint(715, 440, "bottom"));
        INTERMEDIATE_JUNCTIONS.put("BUS-02", b2J);

        Map<Integer, MapPoint> b3J = new HashMap<>();
        b3J.put(11, new MapPoint(260, 260, "top"));
        b3J.put(12, new MapPoint(490, 260, "bottom"));
        b3J.put(13, new MapPoint(705, 270, "top"));
        INTERMEDIATE_JUNCTIONS.put("BUS-03", b3J);
    }

    private static final Map<String, MapPoint> DEPOT_COORDINATES = new HashMap<>();
    static {
        DEPOT_COORDINATES.put("BUS-01", new MapPoint(60, 100, "left"));
        DEPOT_COORDINATES.put("BUS-02", new MapPoint(60, 560, "left"));
        DEPOT_COORDINATES.put("BUS-03", new MapPoint(60, 330, "left"));
    }

    static final MapPoint COLLEGE_POINT = new MapPoint(865, 330, "right");

    /**
     * Single-route delegate for backwards compatibility.
     */
    String renderVisualRouteMap(BusRoute route, String viewerRole, String highlightStopName) {
        if (route == null) {
            return "<div class=\"simulated-map-container\"><p class=\"registration-empty\">Route data unavailable.</p></div>";
        }
        return renderComprehensiveVisualMap(Collections.singletonList(route), viewerRole, highlightStopName);
    }

    /**
     * Generates a 2D SIMULATED ROAD MAP with roads, connecting paths, Stop 1-10,
     * bus icon showing route direction, college destination, colored route lines,
     * required stops, skipped stops, and a complete legend.
     * Strictly Pure HTML and CSS - No JavaScript.
     */
    String renderComprehensiveVisualMap(List<BusRoute> routes, String viewerRole, String highlightStopName) {
        if (routes == null || routes.isEmpty()) {
            return "<div class=\"simulated-map-container\"><p class=\"registration-empty\">Route data unavailable.</p></div>";
        }

        boolean isMultiRoute = routes.size() > 1;
        BusRoute primaryRoute = routes.get(0);

        StringBuilder html = new StringBuilder();
        html.append("<div class=\"simulated-map-container\">\n");

        // Heading: Mandatory "Simulated Route Map — Not Live GPS"
        html.append("  <div class=\"simulated-map-header\">\n")
                .append("    <div class=\"simulated-map-badge\">\n")
                .append("      <span class=\"map-badge-icon\">&#128506;</span>\n")
                .append("      <h2 class=\"simulated-map-main-heading\">Simulated Route Map — Not Live GPS</h2>\n")
                .append("    </div>\n")
                .append("    <span class=\"map-badge-tag\">Pure Java, HTML &amp; CSS &bull; JavaScript-Free</span>\n")
                .append("  </div>\n");

        // Notice & Algorithm Documentation
        html.append("  <div class=\"simulated-map-notice\">\n")
                .append("    <strong>Demonstration Road Network &bull; Simulated Route Map:</strong> ")
                .append("This simulated road map diagram illustrates transit corridors, connecting road paths, intermediate intersections, and the college destination. ")
                .append("Connecting paths between consecutive required stops are calculated deterministically via Dijkstra's shortest-path algorithm while preserving the scheduled pickup order (Stop 1 to Stop 10). ")
                .append("This implementation does not claim globally optimal stop reordering or live GPS telemetry. ")
                .append("Stops are bypassed only when both assigned authorized students are confirmed absent.\n")
                .append("  </div>\n");

        // Interactive Map Legend
        html.append("  <div class=\"demo-map-legend\">\n")
                .append("    <div class=\"legend-item\"><span class=\"legend-swatch\" style=\"background: #0284c7;\"></span> <strong>Bus 01:</strong> Stops 1&ndash;10 (North Corridor)</div>\n")
                .append("    <div class=\"legend-item\"><span class=\"legend-swatch\" style=\"background: #059669;\"></span> <strong>Bus 02:</strong> Stops 11&ndash;20 (South Corridor)</div>\n")
                .append("    <div class=\"legend-item\"><span class=\"legend-swatch\" style=\"background: #d97706;\"></span> <strong>Bus 03:</strong> Stops 21&ndash;30 (Central Corridor)</div>\n")
                .append("    <div class=\"legend-item\"><span class=\"legend-circle\" style=\"border: 2px solid #ffffff; background: #0284c7; color: #ffffff;\">1</span> Required Pickup Stop</div>\n")
                .append("    <div class=\"legend-item\"><span class=\"legend-circle\" style=\"border: 2px dashed #ef4444; background: #1e293b; color: #ef4444;\">&#10005;</span> Skipped Stop (Both Absent)</div>\n")
                .append("    <div class=\"legend-item\"><span class=\"legend-circle\" style=\"border: 2px dashed #f59e0b; background: #1e293b; color: #f59e0b;\">?</span> Unconfirmed Attendance (Active)</div>\n")
                .append("    <div class=\"legend-item\"><span class=\"legend-diamond\" style=\"background: #475569;\"></span> Intermediate Road Junction</div>\n")
                .append("    <div class=\"legend-item\"><span class=\"legend-swatch\" style=\"background: #10b981;\"></span> <strong>College Destination:</strong> Campus</div>\n")
                .append("  </div>\n");

        // 2D Vector Map Canvas (SVG)
        html.append("  <svg viewBox=\"0 0 1000 680\" class=\"demo-transit-map-svg\" role=\"img\" aria-label=\"Simulated Route Map — Not Live GPS\">\n")
                .append("    <defs>\n")
                .append("      <filter id=\"glow\" x=\"-20%\" y=\"-20%\" width=\"140%\" height=\"140%\">\n")
                .append("        <feGaussianBlur stdDeviation=\"3\" result=\"blur\" />\n")
                .append("        <feComposite in=\"SourceGraphic\" in2=\"blur\" operator=\"over\" />\n")
                .append("      </filter>\n")
                .append("    </defs>\n");

        // Background Simulated Grid & Roads
        html.append("    <!-- Background Road Network & Connecting Paths -->\n")
                .append("    <g class=\"simulated-roads\">\n")
                .append("      <!-- Secondary Arterial Grid -->\n")
                .append("      <line x1=\"50\" y1=\"200\" x2=\"800\" y2=\"200\" stroke=\"#131c2e\" stroke-width=\"4\" />\n")
                .append("      <line x1=\"50\" y1=\"440\" x2=\"800\" y2=\"440\" stroke=\"#131c2e\" stroke-width=\"4\" />\n")
                .append("      <line x1=\"270\" y1=\"40\" x2=\"270\" y2=\"640\" stroke=\"#131c2e\" stroke-width=\"4\" />\n")
                .append("      <line x1=\"490\" y1=\"40\" x2=\"490\" y2=\"640\" stroke=\"#131c2e\" stroke-width=\"4\" />\n")
                .append("      <line x1=\"710\" y1=\"40\" x2=\"710\" y2=\"640\" stroke=\"#131c2e\" stroke-width=\"4\" />\n")
                // Connecting Ring Roads & Cross Overpasses
                .append("      <path d=\"M 350,90 Q 360,200 340,325 Q 330,440 345,560\" fill=\"none\" stroke=\"#1e293b\" stroke-width=\"8\" stroke-linecap=\"round\" />\n")
                .append("      <path d=\"M 650,225 Q 640,280 635,320 Q 640,370 645,425\" fill=\"none\" stroke=\"#1e293b\" stroke-width=\"8\" stroke-linecap=\"round\" />\n")
                // Bypass roads to Intermediate Junctions
                .append("      <line x1=\"125\" y1=\"100\" x2=\"270\" y2=\"180\" stroke=\"#1e293b\" stroke-width=\"4\" stroke-dasharray=\"4,4\" />\n")
                .append("      <line x1=\"270\" y1=\"105\" x2=\"270\" y2=\"180\" stroke=\"#1e293b\" stroke-width=\"4\" stroke-dasharray=\"4,4\" />\n")
                .append("      <line x1=\"350\" y1=\"90\" x2=\"270\" y2=\"180\" stroke=\"#1e293b\" stroke-width=\"4\" stroke-dasharray=\"4,4\" />\n")
                .append("      <line x1=\"270\" y1=\"180\" x2=\"490\" y2=\"210\" stroke=\"#1e293b\" stroke-width=\"5\" />\n")
                .append("      <line x1=\"490\" y1=\"210\" x2=\"710\" y2=\"210\" stroke=\"#1e293b\" stroke-width=\"5\" />\n")
                .append("      <line x1=\"710\" y1=\"210\" x2=\"865\" y2=\"330\" stroke=\"#1e293b\" stroke-width=\"5\" />\n")
                // North Arterial Highway Base
                .append("      <path d=\"M 40,100 L 125,100 L 195,85 L 270,105 L 350,90 L 425,115 L 500,145 L 575,185 L 650,225 L 720,265 L 785,295 L 865,330\" fill=\"none\" stroke=\"#1e293b\" stroke-width=\"16\" stroke-linecap=\"round\" stroke-linejoin=\"round\" />\n")
                .append("      <path d=\"M 40,100 L 125,100 L 195,85 L 270,105 L 350,90 L 425,115 L 500,145 L 575,185 L 650,225 L 720,265 L 785,295 L 865,330\" fill=\"none\" stroke=\"#2a384e\" stroke-width=\"12\" stroke-linecap=\"round\" stroke-linejoin=\"round\" />\n")
                .append("      <path d=\"M 40,100 L 125,100 L 195,85 L 270,105 L 350,90 L 425,115 L 500,145 L 575,185 L 650,225 L 720,265 L 785,295 L 865,330\" fill=\"none\" stroke=\"#334155\" stroke-width=\"2\" stroke-dasharray=\"6,6\" />\n")
                // Central Expressway Base
                .append("      <path d=\"M 40,330 L 125,330 L 190,315 L 260,345 L 340,325 L 420,335 L 490,315 L 560,340 L 635,320 L 705,335 L 775,325 L 865,330\" fill=\"none\" stroke=\"#1e293b\" stroke-width=\"16\" stroke-linecap=\"round\" stroke-linejoin=\"round\" />\n")
                .append("      <path d=\"M 40,330 L 125,330 L 190,315 L 260,345 L 340,325 L 420,335 L 490,315 L 560,340 L 635,320 L 705,335 L 775,325 L 865,330\" fill=\"none\" stroke=\"#2a384e\" stroke-width=\"12\" stroke-linecap=\"round\" stroke-linejoin=\"round\" />\n")
                .append("      <path d=\"M 40,330 L 125,330 L 190,315 L 260,345 L 340,325 L 420,335 L 490,315 L 560,340 L 635,320 L 705,335 L 775,325 L 865,330\" fill=\"none\" stroke=\"#334155\" stroke-width=\"2\" stroke-dasharray=\"6,6\" />\n")
                // South Arterial Highway Base
                .append("      <path d=\"M 40,560 L 125,560 L 195,570 L 265,550 L 345,560 L 425,535 L 495,500 L 570,465 L 645,425 L 715,385 L 780,355 L 865,330\" fill=\"none\" stroke=\"#1e293b\" stroke-width=\"16\" stroke-linecap=\"round\" stroke-linejoin=\"round\" />\n")
                .append("      <path d=\"M 40,560 L 125,560 L 195,570 L 265,550 L 345,560 L 425,535 L 495,500 L 570,465 L 645,425 L 715,385 L 780,355 L 865,330\" fill=\"none\" stroke=\"#2a384e\" stroke-width=\"12\" stroke-linecap=\"round\" stroke-linejoin=\"round\" />\n")
                .append("      <path d=\"M 40,560 L 125,560 L 195,570 L 265,550 L 345,560 L 425,535 L 495,500 L 570,465 L 645,425 L 715,385 L 780,355 L 865,330\" fill=\"none\" stroke=\"#334155\" stroke-width=\"2\" stroke-dasharray=\"6,6\" />\n")
                // Highway Street Labels
                .append("      <text x=\"210\" y=\"65\" fill=\"#475569\" font-size=\"8.5\" font-weight=\"bold\" letter-spacing=\"1\">NORTH SECTOR CORRIDOR ROAD</text>\n")
                .append("      <text x=\"210\" y=\"295\" fill=\"#475569\" font-size=\"8.5\" font-weight=\"bold\" letter-spacing=\"1\">CENTRAL TRANSIT EXPRESSWAY</text>\n")
                .append("      <text x=\"210\" y=\"595\" fill=\"#475569\" font-size=\"8.5\" font-weight=\"bold\" letter-spacing=\"1\">SOUTH SECTOR ARTERIAL ROAD</text>\n")
                .append("    </g>\n");

        // Compass Rose at top right
        html.append("    <!-- Compass Rose -->\n")
                .append("    <g transform=\"translate(945, 55)\">\n")
                .append("      <circle cx=\"0\" cy=\"0\" r=\"20\" fill=\"#0b1324\" stroke=\"#1e293b\" stroke-width=\"1.5\" />\n")
                .append("      <path d=\"M 0,-14 L 4,-3 L 0,-6 L -4,-3 Z\" fill=\"#38bdf8\" />\n")
                .append("      <path d=\"M 0,14 L 4,3 L 0,6 L -4,3 Z\" fill=\"#64748b\" />\n")
                .append("      <text x=\"0\" y=\"-16\" fill=\"#f8fafc\" font-size=\"9\" font-weight=\"bold\" text-anchor=\"middle\">N</text>\n")
                .append("    </g>\n");

        // Render each requested route (All 3 routes for Management; 1 route for Driver/Student)
        for (BusRoute r : routes) {
            String routeColor;
            String arrowColor;
            if ("BUS-01".equals(r.busId) || (r.busLabel != null && r.busLabel.contains("01"))) {
                routeColor = "#0284c7";
                arrowColor = "#38bdf8";
            } else if ("BUS-02".equals(r.busId) || (r.busLabel != null && r.busLabel.contains("02"))) {
                routeColor = "#059669";
                arrowColor = "#34d399";
            } else {
                routeColor = "#d97706";
                arrowColor = "#fbbf24";
            }

            MapPoint depotPt = DEPOT_COORDINATES.getOrDefault(r.busId, new MapPoint(60, 100, "left"));
            Map<Integer, MapPoint> junctions = INTERMEDIATE_JUNCTIONS.getOrDefault(r.busId, Collections.emptyMap());

            // 1. Draw Active Colored Route Line Path based on Dijkstra graph nodes
            StringBuilder pathD = new StringBuilder();
            pathD.append("M ").append(depotPt.x).append(",").append(depotPt.y);

            // Construct points along Dijkstra full path
            List<MapPoint> activePathPoints = new ArrayList<>();
            activePathPoints.add(depotPt);

            if (r.fullPathNodes != null && !r.fullPathNodes.isEmpty()) {
                for (int nodeIdx : r.fullPathNodes) {
                    MapPoint pt = null;
                    if (nodeIdx == 0) {
                        pt = depotPt;
                    } else if (nodeIdx >= 1 && nodeIdx <= 10) {
                        int stopNum = r.config.startStopNum + nodeIdx - 1;
                        pt = STOP_COORDINATES.get(stopNum);
                    } else if (nodeIdx >= 11 && nodeIdx <= 13) {
                        pt = junctions.get(nodeIdx);
                    } else if (nodeIdx == COLLEGE_NODE) {
                        pt = COLLEGE_POINT;
                    }
                    if (pt != null) {
                        pathD.append(" L ").append(pt.x).append(",").append(pt.y);
                        activePathPoints.add(pt);
                    }
                }
            } else {
                // Fallback to active stops if fullPathNodes empty
                for (Stop s : r.orderedStops) {
                    MapPoint pt = STOP_COORDINATES.get(s.stopNumber);
                    if (pt != null) {
                        pathD.append(" L ").append(pt.x).append(",").append(pt.y);
                        activePathPoints.add(pt);
                    }
                }
                pathD.append(" L ").append(COLLEGE_POINT.x).append(",").append(COLLEGE_POINT.y);
                activePathPoints.add(COLLEGE_POINT);
            }

            html.append("    <!-- Route Path: ").append(escapeHtml(r.busLabel)).append(" -->\n")
                    .append("    <path d=\"").append(pathD).append("\" fill=\"none\" stroke=\"").append(routeColor)
                    .append("\" stroke-width=\"4.5\" stroke-linecap=\"round\" stroke-linejoin=\"round\" filter=\"url(#glow)\" />\n");

            // Draw dashed detour lines around any skipped stops
            if (!r.skippedStops.isEmpty()) {
                for (Stop skipped : r.skippedStops) {
                    MapPoint pt = STOP_COORDINATES.get(skipped.stopNumber);
                    if (pt != null) {
                        html.append("    <circle cx=\"").append(pt.x).append("\" cy=\"").append(pt.y)
                                .append("\" r=\"18\" fill=\"none\" stroke=\"#ef4444\" stroke-width=\"1.8\" stroke-dasharray=\"3,3\" />\n");
                    }
                }
            }

            // 2. Depot Marker
            html.append("    <!-- Depot & Bus Origin: ").append(escapeHtml(r.busLabel)).append(" -->\n")
                    .append("    <g transform=\"translate(").append(depotPt.x - 45).append(",").append(depotPt.y - 14).append(")\">\n")
                    .append("      <rect x=\"0\" y=\"0\" width=\"86\" height=\"28\" rx=\"6\" fill=\"").append(routeColor).append("\" stroke=\"#ffffff\" stroke-width=\"1.5\" />\n")
                    .append("      <text x=\"43\" y=\"18\" fill=\"#ffffff\" font-size=\"11\" font-weight=\"bold\" text-anchor=\"middle\">&#128652; ").append(escapeHtml(r.busLabel)).append("</text>\n")
                    .append("    </g>\n");

            // 3. Directional Bus Marker along Route Line (Bus Icon showing route direction)
            if (activePathPoints.size() >= 3) {
                // Place bus icon between 1st and 2nd path points
                MapPoint p1 = activePathPoints.get(1);
                MapPoint p2 = activePathPoints.get(2);
                int busX = (p1.x + p2.x) / 2;
                int busY = (p1.y + p2.y) / 2 - 14;

                html.append("    <!-- Bus Icon Showing Route Direction -->\n")
                        .append("    <g transform=\"translate(").append(busX).append(",").append(busY).append(")\">\n")
                        .append("      <rect x=\"-24\" y=\"-12\" width=\"48\" height=\"24\" rx=\"6\" fill=\"#070d18\" stroke=\"").append(arrowColor).append("\" stroke-width=\"1.5\" />\n")
                        .append("      <text x=\"-4\" y=\"4\" font-size=\"13\" text-anchor=\"middle\">&#128652;</text>\n")
                        .append("      <text x=\"13\" y=\"4\" font-size=\"11\" font-weight=\"900\" fill=\"").append(arrowColor).append("\" text-anchor=\"middle\">&#10148;</text>\n")
                        .append("    </g>\n");
            }

            // 4. Intermediate Road Junction Markers
            for (Map.Entry<Integer, MapPoint> juncEntry : junctions.entrySet()) {
                MapPoint jPt = juncEntry.getValue();
                html.append("    <!-- Intermediate Road Junction #").append(juncEntry.getKey()).append(" -->\n")
                        .append("    <g class=\"road-junction-marker\">\n")
                        .append("      <polygon points=\"").append(jPt.x).append(",").append(jPt.y - 8).append(" ")
                        .append(jPt.x + 8).append(",").append(jPt.y).append(" ")
                        .append(jPt.x).append(",").append(jPt.y + 8).append(" ")
                        .append(jPt.x - 8).append(",").append(jPt.y).append("\" fill=\"#1e293b\" stroke=\"#64748b\" stroke-width=\"1.5\" />\n")
                        .append("      <text x=\"").append(jPt.x).append("\" y=\"").append(jPt.y + 18)
                        .append("\" fill=\"#64748b\" font-size=\"7.5\" font-weight=\"bold\" text-anchor=\"middle\">JCT-").append(juncEntry.getKey()).append("</text>\n")
                        .append("    </g>\n");
            }

            // 5. Stop Markers: Required, Skipped, and Unconfirmed
            for (Stop s : r.allScheduledStops) {
                MapPoint pt = STOP_COORDINATES.get(s.stopNumber);
                if (pt == null) continue;

                boolean isHighlighted = highlightStopName != null
                        && (highlightStopName.equalsIgnoreCase(s.name)
                        || highlightStopName.equalsIgnoreCase(s.label)
                        || highlightStopName.contains(s.name));

                if (s.status == StopStatus.SKIPPED) {
                    // Skipped Stop Marker
                    html.append("    <!-- Skipped Stop #").append(s.stopNumber).append(" -->\n")
                            .append("    <g class=\"stop-marker-skipped\">\n")
                            .append("      <circle cx=\"").append(pt.x).append("\" cy=\"").append(pt.y)
                            .append("\" r=\"13\" fill=\"#1e293b\" stroke=\"#ef4444\" stroke-width=\"2\" stroke-dasharray=\"3,3\" />\n")
                            .append("      <text x=\"").append(pt.x).append("\" y=\"").append(pt.y + 4)
                            .append("\" fill=\"#ef4444\" font-size=\"11\" font-weight=\"900\" text-anchor=\"middle\">&#10005;</text>\n");

                    int labelY = "top".equals(pt.labelPos) ? pt.y - 18 : pt.y + 24;
                    html.append("      <text x=\"").append(pt.x).append("\" y=\"").append(labelY)
                            .append("\" fill=\"#f87171\" font-size=\"8\" font-weight=\"bold\" text-anchor=\"middle\">")
                            .append(escapeHtml(s.name)).append(" [SKIPPED]</text>\n")
                            .append("    </g>\n");

                } else if (s.status == StopStatus.UNCONFIRMED) {
                    // Unconfirmed Attendance Marker
                    html.append("    <!-- Unconfirmed Attendance Stop #").append(s.stopNumber).append(" -->\n")
                            .append("    <g class=\"stop-marker-unconfirmed\">\n");

                    if (isHighlighted) {
                        html.append("      <circle cx=\"").append(pt.x).append("\" cy=\"").append(pt.y)
                                .append("\" r=\"24\" fill=\"rgba(212, 175, 55, 0.2)\" stroke=\"#ffd700\" stroke-width=\"2.5\" stroke-dasharray=\"4,4\" />\n")
                                .append("      <rect x=\"").append(pt.x - 55).append("\" y=\"").append(pt.y - 36).append("\" width=\"110\" height=\"16\" rx=\"4\" fill=\"#ca8a04\" />\n")
                                .append("      <text x=\"").append(pt.x).append("\" y=\"").append(pt.y - 24).append("\" fill=\"#ffffff\" font-size=\"8\" font-weight=\"bold\" text-anchor=\"middle\">&#9733; YOUR PICKUP</text>\n");
                    }

                    html.append("      <circle cx=\"").append(pt.x).append("\" cy=\"").append(pt.y)
                            .append("\" r=\"13\" fill=\"#1f1910\" stroke=\"#f59e0b\" stroke-width=\"2\" stroke-dasharray=\"3,3\" />\n")
                            .append("      <text x=\"").append(pt.x).append("\" y=\"").append(pt.y + 4)
                            .append("\" fill=\"#f59e0b\" font-size=\"10\" font-weight=\"900\" text-anchor=\"middle\">?</text>\n");

                    int labelY = "top".equals(pt.labelPos) ? pt.y - 18 : pt.y + 24;
                    html.append("      <text x=\"").append(pt.x).append("\" y=\"").append(labelY)
                            .append("\" fill=\"#fbbf24\" font-size=\"8\" font-weight=\"bold\" text-anchor=\"middle\">")
                            .append(escapeHtml(s.name)).append(" [UNCONFIRMED]</text>\n")
                            .append("    </g>\n");

                } else {
                    // Required / Active Stop Marker
                    html.append("    <!-- Required Stop #").append(s.stopNumber).append(" -->\n")
                            .append("    <g class=\"stop-marker-required\">\n");

                    if (isHighlighted) {
                        html.append("      <circle cx=\"").append(pt.x).append("\" cy=\"").append(pt.y)
                                .append("\" r=\"24\" fill=\"rgba(212, 175, 55, 0.25)\" stroke=\"#ffd700\" stroke-width=\"2.5\" stroke-dasharray=\"4,4\" />\n")
                                .append("      <rect x=\"").append(pt.x - 55).append("\" y=\"").append(pt.y - 36).append("\" width=\"110\" height=\"16\" rx=\"4\" fill=\"#ca8a04\" />\n")
                                .append("      <text x=\"").append(pt.x).append("\" y=\"").append(pt.y - 24).append("\" fill=\"#ffffff\" font-size=\"8\" font-weight=\"bold\" text-anchor=\"middle\">&#9733; YOUR PICKUP</text>\n");
                    }

                    html.append("      <circle cx=\"").append(pt.x).append("\" cy=\"").append(pt.y)
                            .append("\" r=\"13\" fill=\"").append(routeColor).append("\" stroke=\"#ffffff\" stroke-width=\"2\" />\n")
                            .append("      <text x=\"").append(pt.x).append("\" y=\"").append(pt.y + 4)
                            .append("\" fill=\"#ffffff\" font-size=\"10\" font-weight=\"900\" text-anchor=\"middle\">").append(s.stopNumber).append("</text>\n");

                    int labelY = "top".equals(pt.labelPos) ? pt.y - 18 : pt.y + 24;
                    html.append("      <text x=\"").append(pt.x).append("\" y=\"").append(labelY)
                            .append("\" fill=\"#cbd5e1\" font-size=\"8.5\" font-weight=\"600\" text-anchor=\"middle\">")
                            .append(escapeHtml(s.name)).append("</text>\n")
                            .append("    </g>\n");
                }
            }
        }

        // 6. College Destination Terminus (Shared Campus Hub)
        html.append("    <!-- Final Destination: CIET Central Campus -->\n")
                .append("    <g class=\"destination-marker\">\n")
                .append("      <rect x=\"800\" y=\"270\" width=\"180\" height=\"120\" rx=\"10\" fill=\"#042f2e\" stroke=\"#10b981\" stroke-width=\"2\" stroke-dasharray=\"6,4\" />\n")
                .append("      <text x=\"890\" y=\"288\" fill=\"#34d399\" font-size=\"8.5\" font-weight=\"bold\" letter-spacing=\"1\" text-anchor=\"middle\">CAMPUS BOUNDARY</text>\n")
                .append("      <circle cx=\"").append(COLLEGE_POINT.x).append("\" cy=\"").append(COLLEGE_POINT.y).append("\" r=\"30\" fill=\"none\" stroke=\"#10b981\" stroke-width=\"1.5\" stroke-opacity=\"0.4\" />\n")
                .append("      <circle cx=\"").append(COLLEGE_POINT.x).append("\" cy=\"").append(COLLEGE_POINT.y).append("\" r=\"24\" fill=\"#065f46\" stroke=\"#10b981\" stroke-width=\"3\" />\n")
                .append("      <text x=\"").append(COLLEGE_POINT.x).append("\" y=\"").append(COLLEGE_POINT.y + 7).append("\" font-size=\"18\" text-anchor=\"middle\">&#127891;</text>\n")
                .append("      <rect x=\"805\" y=\"365\" width=\"130\" height=\"20\" rx=\"4\" fill=\"#10b981\" />\n")
                .append("      <text x=\"870\" y=\"379\" fill=\"#022c22\" font-size=\"9\" font-weight=\"bold\" text-anchor=\"middle\">CIET Central Campus</text>\n")
                .append("    </g>\n");

        // Scale bar at bottom left
        html.append("    <!-- Scale Bar -->\n")
                .append("    <g transform=\"translate(60, 645)\">\n")
                .append("      <line x1=\"0\" y1=\"0\" x2=\"120\" y2=\"0\" stroke=\"#64748b\" stroke-width=\"2\" />\n")
                .append("      <line x1=\"0\" y1=\"-4\" x2=\"0\" y2=\"4\" stroke=\"#64748b\" stroke-width=\"2\" />\n")
                .append("      <line x1=\"120\" y1=\"-4\" x2=\"120\" y2=\"4\" stroke=\"#64748b\" stroke-width=\"2\" />\n")
                .append("      <text x=\"60\" y=\"14\" fill=\"#64748b\" font-size=\"8.5\" text-anchor=\"middle\">Simulated Scale: 10 km</text>\n")
                .append("    </g>\n");

        html.append("  </svg>\n");

        // Metrics Summary Bar & Recalculate Button
        if (!isMultiRoute) {
            String distanceStr = primaryRoute.distanceValid
                    ? String.format(Locale.ROOT, "%.1f km", primaryRoute.distanceMeters / 1000.0)
                    : "Unavailable (Disconnected Node)";
            long minutes = Math.round(primaryRoute.durationSeconds / 60.0);
            int activeCount = primaryRoute.orderedStops.size();

            html.append("  <div class=\"route-metrics-bar\">\n")
                    .append("    <div class=\"metric-item\"><span class=\"metric-label\">Assigned Vehicle</span><span class=\"metric-val\">")
                    .append(escapeHtml(primaryRoute.busLabel)).append("</span></div>\n")
                    .append("    <div class=\"metric-item\"><span class=\"metric-label\">Simulated Distance</span><span class=\"metric-val\">")
                    .append(escapeHtml(distanceStr)).append("</span></div>\n")
                    .append("    <div class=\"metric-item\"><span class=\"metric-label\">Transit Time</span><span class=\"metric-val\">")
                    .append(minutes).append(" mins</span></div>\n")
                    .append("    <div class=\"metric-item\"><span class=\"metric-label\">Active Stops</span><span class=\"metric-val\">")
                    .append(activeCount).append(" / 10</span></div>\n")
                    .append("    <div class=\"metric-item\"><span class=\"metric-label\">Shortest Path Engine</span><span class=\"metric-val\">Dijkstra Road Network</span></div>\n")
                    .append("  </div>\n");

            // Recalculate Route Button Form (No JavaScript, standard HTML form)
            html.append("  <div class=\"route-recalculate-box\" style=\"margin-bottom: 20px;\">\n")
                    .append("    <form method=\"POST\" action=\"/recalculate-route\" style=\"display: inline-flex; align-items: center; gap: 14px; flex-wrap: wrap;\">\n")
                    .append("      <input type=\"hidden\" name=\"busId\" value=\"").append(escapeHtml(primaryRoute.busId)).append("\">\n")
                    .append("      <button type=\"submit\" class=\"recalculate-btn\" id=\"btn-recalculate-route\">\n")
                    .append("        &#8635; Recalculate Route\n")
                    .append("      </button>\n")
                    .append("      <span style=\"font-size: 0.85rem; color: #94a3b8;\">Re-reads latest database attendance records &amp; computes Dijkstra shortest road paths without JavaScript.</span>\n")
                    .append("    </form>\n")
                    .append("  </div>\n");

            if (primaryRoute.warningMessage != null) {
                html.append("  <div class=\"route-change-alert alert-warning-styled\">\n")
                        .append("    <span class=\"alert-indicator\">&#9888;</span>\n")
                        .append("    <span><strong>Notice:</strong> ").append(escapeHtml(primaryRoute.warningMessage)).append("</span>\n")
                        .append("  </div>\n");
            }
        } else {
            html.append("  <div class=\"route-metrics-bar\">\n")
                    .append("    <div class=\"metric-item\"><span class=\"metric-label\">Total Fleet</span><span class=\"metric-val\">3 Buses Active</span></div>\n")
                    .append("    <div class=\"metric-item\"><span class=\"metric-label\">Transit Network</span><span class=\"metric-val\">30 Numbered Stops</span></div>\n")
                    .append("    <div class=\"metric-item\"><span class=\"metric-label\">Campus Terminus</span><span class=\"metric-val\">Campus</span></div>\n")
                    .append("    <div class=\"metric-item\"><span class=\"metric-label\">Algorithm</span><span class=\"metric-val\">Dijkstra Shortest Connecting Paths</span></div>\n")
                    .append("  </div>\n");

            html.append("  <div class=\"route-recalculate-box\" style=\"margin-bottom: 20px;\">\n")
                    .append("    <form method=\"POST\" action=\"/recalculate-route\" style=\"display: inline-flex; align-items: center; gap: 14px; flex-wrap: wrap;\">\n")
                    .append("      <input type=\"hidden\" name=\"busId\" value=\"ALL\">\n")
                    .append("      <button type=\"submit\" class=\"recalculate-btn\" id=\"btn-recalculate-all-routes\">\n")
                    .append("        &#8635; Recalculate Fleet Routes\n")
                    .append("      </button>\n")
                    .append("      <span style=\"font-size: 0.85rem; color: #94a3b8;\">Recalculates all bus lines using current MySQL attendance without JavaScript.</span>\n")
                    .append("    </form>\n")
                    .append("  </div>\n");
        }

        html.append("</div>\n");
        return html.toString();
    }

    private void saveRoute(BusRoute route) throws SQLException {
        String sql = "INSERT INTO bus_route_cache "
                + "(bus_id, input_signature, route_sequence, distance_meters, duration_seconds, updated_reason) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                + "input_signature = VALUES(input_signature), route_sequence = VALUES(route_sequence), "
                + "distance_meters = VALUES(distance_meters), duration_seconds = VALUES(duration_seconds), "
                + "updated_reason = VALUES(updated_reason), calculated_at = CURRENT_TIMESTAMP";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, route.busId);
            statement.setString(2, route.signature);
            statement.setString(3, encodeSequence(route.orderedStops));
            statement.setDouble(4, route.distanceMeters);
            statement.setDouble(5, route.durationSeconds);
            statement.setString(6, route.updateReason);
            statement.executeUpdate();
        } catch (SQLException ignore) {
            // Non-critical cache write
        }
    }

    private String encodeSequence(List<Stop> stops) {
        StringJoiner encoded = new StringJoiner("\n");
        for (Stop stop : stops) {
            encoded.add(Base64.getEncoder().encodeToString(
                    stop.name.getBytes(StandardCharsets.UTF_8)));
        }
        return encoded.toString();
    }

    private String inputSignature(String busId, Location start, Location college, Iterable<Stop> stops) {
        StringBuilder input = new StringBuilder(busId).append('|');
        if (start != null) input.append(start.latitude).append(',').append(start.longitude);
        input.append('|');
        if (college != null) input.append(college.latitude).append(',').append(college.longitude);
        List<Stop> sorted = new ArrayList<>();
        for (Stop stop : stops) sorted.add(stop);
        sorted.sort(Comparator.comparing(stop -> stop.name));
        for (Stop stop : sorted) {
            input.append('|').append(stop.name).append(':').append(stop.status);
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    Location findLocation(String type, String key) throws SQLException {
        String sql = "SELECT latitude, longitude FROM route_locations "
                + "WHERE location_type = ? AND location_key = ?";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, type);
            statement.setString(2, key);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? new Location(result.getDouble("latitude"), result.getDouble("longitude"))
                        : null;
            }
        }
    }

    void saveLocation(String type, String key, String busId, double latitude, double longitude)
            throws SQLException {
        if (!isValidCoordinate(latitude, longitude)) {
            throw new IllegalArgumentException("Enter valid coordinates");
        }
        String sql = "INSERT INTO route_locations "
                + "(location_type, location_key, bus_id, latitude, longitude) "
                + "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                + "bus_id = VALUES(bus_id), latitude = VALUES(latitude), longitude = VALUES(longitude)";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, type);
            statement.setString(2, key);
            if (busId == null || busId.trim().isEmpty()) {
                statement.setNull(3, java.sql.Types.VARCHAR);
            } else {
                statement.setString(3, busId);
            }
            statement.setDouble(4, latitude);
            statement.setDouble(5, longitude);
            statement.executeUpdate();
        }
    }

    static boolean isValidCoordinate(double latitude, double longitude) {
        return Double.isFinite(latitude) && Double.isFinite(longitude)
                && latitude >= -90 && latitude <= 90
                && longitude >= -180 && longitude <= 180;
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // =========================================================================
    // Inner Classes & Data Structures
    // =========================================================================

    enum StopStatus {
        REQUIRED,
        SKIPPED,
        EXCLUDED,
        UNCONFIRMED,
        WARNING
    }

    static final class Location {
        final double latitude;
        final double longitude;

        Location(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    static final class AssignedStudent {
        final String studentId;
        final String name;
        final String stopName;
        final String status;
        final String absenceReason;
        final boolean transportAuthorized;

        AssignedStudent(String studentId, String name, String stopName, String status,
                        String absenceReason, boolean transportAuthorized) {
            this.studentId = studentId;
            this.name = name;
            this.stopName = stopName;
            this.status = status;
            this.absenceReason = absenceReason;
            this.transportAuthorized = transportAuthorized;
        }
    }

    private static final class AttendanceRecord {
        final String status;
        final String absenceReason;

        AttendanceRecord(String status, String absenceReason) {
            this.status = status;
            this.absenceReason = absenceReason;
        }
    }

    static final class Stop {
        final String name;
        final String label;
        final int stopNumber;
        final int graphNodeIndex;
        Location location;

        StopStatus status = StopStatus.REQUIRED;
        boolean required = true;
        String reason = "";
        String skipReason = "";
        String warning = "";
        final List<AssignedStudent> assignedStudents = new ArrayList<>();

        int passengerCount = 0;
        int requiredPassengerCount = 0;
        int absentCount = 0;

        Stop(String name, String label, int stopNumber, int graphNodeIndex) {
            this.name = name;
            this.label = label;
            this.stopNumber = stopNumber;
            this.graphNodeIndex = graphNodeIndex;
        }

        Stop(String name, String label, int stopNumber) {
            this(name, label, stopNumber, stopNumber % 10 == 0 ? 10 : stopNumber % 10);
        }

        Stop(String name) {
            this(name, name, 0, 0);
        }
    }

    static final class BusRoute {
        final String busId;
        final String busLabel;
        final String depotName;
        final Location start;
        final Location college;

        final List<Stop> orderedStops; // Required active stops in scheduled order
        final List<Stop> skippedStops; // Bypassed stops (both absent)
        final List<Stop> unconfirmedStops; // Stops with unconfirmed attendance
        final List<Stop> warningStops; // Stops with assignment/configuration warnings
        final List<Stop> allScheduledStops; // All 10 scheduled stops

        final double distanceMeters;
        final double durationSeconds;
        final String signature;
        final String updateReason;
        final String mapUrl = null;
        final boolean available;
        final boolean distanceValid;
        final boolean approximate = false;
        final String message;
        final String warningMessage;
        final BusNetworkConfig config;
        final List<Integer> fullPathNodes;
        final List<DijkstraLeg> legs;

        BusRoute(String busId, String busLabel, String depotName, Location start, Location college,
                 List<Stop> orderedStops, List<Stop> skippedStops, List<Stop> unconfirmedStops,
                 List<Stop> warningStops, List<Stop> allScheduledStops, double distanceMeters,
                 double durationSeconds, String signature, String updateReason,
                 boolean distanceValid, String warningMessage, BusNetworkConfig config,
                 List<Integer> fullPathNodes, List<DijkstraLeg> legs) {
            this.busId = busId;
            this.busLabel = busLabel;
            this.depotName = depotName;
            this.start = start;
            this.college = college;
            this.orderedStops = Collections.unmodifiableList(new ArrayList<>(orderedStops));
            this.skippedStops = Collections.unmodifiableList(new ArrayList<>(skippedStops));
            this.unconfirmedStops = Collections.unmodifiableList(new ArrayList<>(unconfirmedStops));
            this.warningStops = Collections.unmodifiableList(new ArrayList<>(warningStops));
            this.allScheduledStops = Collections.unmodifiableList(new ArrayList<>(allScheduledStops));
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
            this.signature = signature;
            this.updateReason = updateReason;
            this.available = true;
            this.distanceValid = distanceValid;
            this.message = warningMessage;
            this.warningMessage = warningMessage;
            this.config = config;
            this.fullPathNodes = fullPathNodes != null
                    ? Collections.unmodifiableList(new ArrayList<>(fullPathNodes))
                    : Collections.emptyList();
            this.legs = legs != null
                    ? Collections.unmodifiableList(new ArrayList<>(legs))
                    : Collections.emptyList();
        }
    }

    static final class DijkstraLeg {
        final int fromNode;
        final int toNode;
        final DijkstraResult result;

        DijkstraLeg(int fromNode, int toNode, DijkstraResult result) {
            this.fromNode = fromNode;
            this.toNode = toNode;
            this.result = result;
        }
    }

    static final class RoadEdge {
        final int from;
        final int to;
        final double weight;

        RoadEdge(int from, int to, double weight) {
            this.from = from;
            this.to = to;
            this.weight = weight;
        }
    }

    static final class BusNetworkConfig {
        final String busId;
        final String busLabel;
        final String depotName;
        final int startStopNum;
        final int endStopNum;
        final String[] stopLabels;
        final RoadEdge[] edges;

        BusNetworkConfig(String busId, String busLabel, String depotName, int startStopNum,
                         int endStopNum, String[] stopLabels, RoadEdge[] edges) {
            this.busId = busId;
            this.busLabel = busLabel;
            this.depotName = depotName;
            this.startStopNum = startStopNum;
            this.endStopNum = endStopNum;
            this.stopLabels = stopLabels;
            this.edges = edges;
        }
    }

    static final class DijkstraResult {
        final boolean reachable;
        final double distance;
        final List<Integer> path;

        DijkstraResult(boolean reachable, double distance, List<Integer> path) {
            this.reachable = reachable;
            this.distance = distance;
            this.path = path != null ? path : Collections.emptyList();
        }
    }

    /**
     * SimulatedGraph models the weighted road network for a bus and runs Dijkstra's algorithm.
     */
    static final class SimulatedGraph {
        private final int numVertices;
        private final List<List<GraphEdge>> adj;

        static final class GraphEdge {
            final int to;
            final double weight;

            GraphEdge(int to, double weight) {
                this.to = to;
                this.weight = weight;
            }
        }

        static final class DijkstraNode {
            final int vertex;
            final double dist;

            DijkstraNode(int vertex, double dist) {
                this.vertex = vertex;
                this.dist = dist;
            }
        }

        SimulatedGraph(int numVertices, RoadEdge[] edges) {
            this.numVertices = numVertices;
            this.adj = new ArrayList<>();
            for (int i = 0; i < numVertices; i++) {
                adj.add(new ArrayList<>());
            }
            if (edges != null) {
                for (RoadEdge edge : edges) {
                    if (edge.from >= 0 && edge.from < numVertices && edge.to >= 0 && edge.to < numVertices) {
                        adj.get(edge.from).add(new GraphEdge(edge.to, edge.weight));
                        adj.get(edge.to).add(new GraphEdge(edge.from, edge.weight));
                    }
                }
            }
        }

        /**
         * Calculates the shortest road path from source to target using Dijkstra's algorithm.
         */
        DijkstraResult dijkstraShortestPath(int source, int target) {
            if (source < 0 || source >= numVertices || target < 0 || target >= numVertices) {
                return new DijkstraResult(false, Double.POSITIVE_INFINITY, Collections.emptyList());
            }
            if (source == target) {
                return new DijkstraResult(true, 0.0, Collections.singletonList(source));
            }

            double[] dist = new double[numVertices];
            int[] parent = new int[numVertices];
            Arrays.fill(dist, Double.POSITIVE_INFINITY);
            Arrays.fill(parent, -1);
            dist[source] = 0.0;

            PriorityQueue<DijkstraNode> pq = new PriorityQueue<>(Comparator.comparingDouble(n -> n.dist));
            pq.add(new DijkstraNode(source, 0.0));

            while (!pq.isEmpty()) {
                DijkstraNode current = pq.poll();
                if (current.dist > dist[current.vertex]) continue;
                if (current.vertex == target) break;

                for (GraphEdge edge : adj.get(current.vertex)) {
                    if (edge.weight < 0) continue; // Safe handling: skip negative weights
                    double candidate = dist[current.vertex] + edge.weight;
                    if (candidate < dist[edge.to]) {
                        dist[edge.to] = candidate;
                        parent[edge.to] = current.vertex;
                        pq.add(new DijkstraNode(edge.to, candidate));
                    }
                }
            }

            if (!Double.isFinite(dist[target]) || dist[target] == Double.POSITIVE_INFINITY) {
                return new DijkstraResult(false, Double.POSITIVE_INFINITY, Collections.emptyList());
            }

            List<Integer> path = new ArrayList<>();
            int curr = target;
            while (curr != -1) {
                path.add(curr);
                if (curr == source) break;
                curr = parent[curr];
            }
            Collections.reverse(path);
            return new DijkstraResult(true, dist[target], path);
        }

        double[] dijkstra(int source) {
            double[] dist = new double[numVertices];
            Arrays.fill(dist, Double.POSITIVE_INFINITY);
            dist[source] = 0;

            PriorityQueue<DijkstraNode> pq = new PriorityQueue<>(Comparator.comparingDouble(n -> n.dist));
            pq.add(new DijkstraNode(source, 0));

            while (!pq.isEmpty()) {
                DijkstraNode current = pq.poll();
                if (current.dist > dist[current.vertex]) continue;

                for (GraphEdge edge : adj.get(current.vertex)) {
                    if (edge.weight < 0) continue;
                    if (dist[current.vertex] + edge.weight < dist[edge.to]) {
                        dist[edge.to] = dist[current.vertex] + edge.weight;
                        pq.add(new DijkstraNode(edge.to, dist[edge.to]));
                    }
                }
            }
            return dist;
        }

        double[][] computeAllPairsShortestPaths() {
            double[][] matrix = new double[numVertices][numVertices];
            for (int i = 0; i < numVertices; i++) {
                matrix[i] = dijkstra(i);
            }
            return matrix;
        }
    }

    // =========================================================================
    // SECTION: REAL ROAD-BASED ROUTE MANAGEMENT & LEAFLET OPENSTREETMAP ENGINE
    // =========================================================================

    public static class ManagedStop {
        public final int stopId;
        public final String stopCode;
        public final String stopName;
        public final String busId;
        public final double latitude;
        public final double longitude;
        public final int stopOrder;
        public final boolean isActiveOverride;
        public final List<String> assignedStudentIds = new ArrayList<>();
        public final List<String> assignedStudentNames = new ArrayList<>();

        public StopStatus status = StopStatus.REQUIRED;
        public String statusReason = "";
        public int presentCount = 0;
        public int absentCount = 0;
        public int unconfirmedCount = 0;

        public ManagedStop(int stopId, String stopCode, String stopName, String busId,
                           double latitude, double longitude, int stopOrder, boolean isActiveOverride) {
            this.stopId = stopId;
            this.stopCode = stopCode;
            this.stopName = stopName;
            this.busId = busId;
            this.latitude = latitude;
            this.longitude = longitude;
            this.stopOrder = stopOrder;
            this.isActiveOverride = isActiveOverride;
        }
    }

    public static class RealRouteResult {
        public final String busId;
        public final LocalDate date;
        public final boolean serviceAvailable;
        public final String serviceNotice;
        public final double distanceMeters;
        public final double durationSeconds;
        public final List<ManagedStop> allStops;
        public final List<ManagedStop> activeStopsInVisitingOrder;
        public final List<ManagedStop> orderedStops;
        public final List<ManagedStop> skippedStops;
        public final List<ManagedStop> excludedStops;
        public final List<ManagedStop> unconfirmedStops;
        public final String geojsonCoordinates;
        public final Timestamp lastUpdated;
        public final Timestamp lastCalculatedAt;

        public RealRouteResult(String busId, LocalDate date, boolean serviceAvailable, String serviceNotice,
                               double distanceMeters, double durationSeconds,
                               List<ManagedStop> allStops, List<ManagedStop> activeStopsInVisitingOrder,
                               List<ManagedStop> skippedStops, List<ManagedStop> excludedStops,
                               String geojsonCoordinates, Timestamp lastUpdated) {
            this.busId = busId;
            this.date = date;
            this.serviceAvailable = serviceAvailable;
            this.serviceNotice = serviceNotice;
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
            this.allStops = allStops;
            this.activeStopsInVisitingOrder = activeStopsInVisitingOrder;
            this.orderedStops = activeStopsInVisitingOrder;
            this.skippedStops = skippedStops;
            this.excludedStops = excludedStops;
            List<ManagedStop> unconf = new ArrayList<>();
            if (allStops != null) {
                for (ManagedStop s : allStops) {
                    if (s.unconfirmedCount > 0) {
                        unconf.add(s);
                    }
                }
            }
            this.unconfirmedStops = Collections.unmodifiableList(unconf);
            this.geojsonCoordinates = geojsonCoordinates;
            this.lastUpdated = lastUpdated;
            this.lastCalculatedAt = lastUpdated;
        }
    }

    public static String normalizeStopCode(String input) {
        if (input == null) return "";
        String s = input.trim();
        String cleaned = s.replaceAll("[-_]+", " ").replaceAll("\\s+", " ").trim();
        Pattern p = Pattern.compile("(?i)^stop\\s*(\\d+)$");
        Matcher m = p.matcher(cleaned);
        if (m.find()) {
            return "Stop " + Integer.parseInt(m.group(1));
        }
        if (cleaned.matches("^\\d+$")) {
            return "Stop " + Integer.parseInt(cleaned);
        }
        return s;
    }

    public List<ManagedStop> getManagedStops(String busId) throws SQLException {
        List<ManagedStop> stops = new ArrayList<>();
        String sql = "SELECT stop_id, stop_code, stop_name, bus_id, latitude, longitude, stop_order, is_active_override "
                + "FROM stops WHERE bus_id = ? OR ? = 'ALL' ORDER BY stop_order, stop_code";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, busId);
            ps.setString(2, busId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    stops.add(new ManagedStop(
                            rs.getInt("stop_id"),
                            rs.getString("stop_code"),
                            rs.getString("stop_name"),
                            rs.getString("bus_id"),
                            rs.getDouble("latitude"),
                            rs.getDouble("longitude"),
                            rs.getInt("stop_order"),
                            rs.getInt("is_active_override") == 1
                    ));
                }
            }
        }
        return stops;
    }

    public ManagedStop getManagedStop(String stopCode) throws SQLException {
        String sql = "SELECT stop_id, stop_code, stop_name, bus_id, latitude, longitude, stop_order, is_active_override "
                + "FROM stops WHERE stop_code = ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, stopCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ManagedStop(
                            rs.getInt("stop_id"),
                            rs.getString("stop_code"),
                            rs.getString("stop_name"),
                            rs.getString("bus_id"),
                            rs.getDouble("latitude"),
                            rs.getDouble("longitude"),
                            rs.getInt("stop_order"),
                            rs.getInt("is_active_override") == 1
                    );
                }
            }
        }
        return null;
    }

    public void addManagedStop(String stopCode, String stopName, String busId,
                               double lat, double lon, int order) throws SQLException {
        if (!isValidCoordinate(lat, lon)) {
            throw new IllegalArgumentException("Latitude must be -90 to 90, Longitude must be -180 to 180.");
        }
        if (stopCode == null || stopCode.trim().isEmpty() || stopName == null || stopName.trim().isEmpty()) {
            throw new IllegalArgumentException("Stop code and location name are required.");
        }
        String sql = "INSERT INTO stops (stop_code, stop_name, bus_id, latitude, longitude, stop_order) "
                + "VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                + "stop_name = VALUES(stop_name), bus_id = VALUES(bus_id), latitude = VALUES(latitude), "
                + "longitude = VALUES(longitude), stop_order = VALUES(stop_order)";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, normalizeStopCode(stopCode));
            ps.setString(2, stopName.trim());
            ps.setString(3, busId != null ? busId.trim() : "BUS-01");
            ps.setDouble(4, lat);
            ps.setDouble(5, lon);
            ps.setInt(6, order > 0 ? order : 1);
            ps.executeUpdate();
        }
    }

    public void updateManagedStop(String stopCode, String stopName, String busId,
                                  double lat, double lon, int order) throws SQLException {
        if (!isValidCoordinate(lat, lon)) {
            throw new IllegalArgumentException("Latitude must be -90 to 90, Longitude must be -180 to 180.");
        }
        String sql = "UPDATE stops SET stop_name = ?, bus_id = ?, latitude = ?, longitude = ?, stop_order = ? "
                + "WHERE stop_code = ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, stopName.trim());
            ps.setString(2, busId != null ? busId.trim() : "BUS-01");
            ps.setDouble(3, lat);
            ps.setDouble(4, lon);
            ps.setInt(5, order > 0 ? order : 1);
            ps.setString(6, stopCode);
            ps.executeUpdate();
        }
    }

    public boolean stopHasAssignedStudents(String stopCode) throws SQLException {
        String norm = normalizeStopCode(stopCode);
        String sql = "SELECT COUNT(*) FROM students WHERE stop_name = ? OR "
                + "LOWER(REPLACE(stop_name, '-', ' ')) = LOWER(REPLACE(?, '-', ' '))";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, stopCode);
            ps.setString(2, norm);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        }
        return false;
    }

    public List<String> getAssignedStudentNames(String stopCode) throws SQLException {
        List<String> names = new ArrayList<>();
        String norm = normalizeStopCode(stopCode);
        String sql = "SELECT student_id, name FROM students WHERE stop_name = ? OR "
                + "LOWER(REPLACE(stop_name, '-', ' ')) = LOWER(REPLACE(?, '-', ' ')) "
                + "ORDER BY name";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, stopCode);
            ps.setString(2, norm);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString("name") + " (" + rs.getString("student_id") + ")");
                }
            }
        }
        return names;
    }

    public void deleteManagedStop(String stopCode) throws SQLException {
        if (stopHasAssignedStudents(stopCode)) {
            throw new IllegalStateException("Cannot delete stop with active students. Reassign students first.");
        }
        String sql = "DELETE FROM stops WHERE stop_code = ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, stopCode);
            ps.executeUpdate();
        }
    }

    public void reassignStudentsAndDelete(String oldStopCode, String newStopCode) throws SQLException {
        String normOld = normalizeStopCode(oldStopCode);
        String normNew = normalizeStopCode(newStopCode);
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                String reassignSql = "UPDATE students SET stop_name = ? WHERE stop_name = ? OR "
                        + "LOWER(REPLACE(stop_name, '-', ' ')) = LOWER(REPLACE(?, '-', ' '))";
                try (PreparedStatement ps = con.prepareStatement(reassignSql)) {
                    ps.setString(1, normNew);
                    ps.setString(2, oldStopCode);
                    ps.setString(3, normOld);
                    ps.executeUpdate();
                }
                String deleteSql = "DELETE FROM stops WHERE stop_code = ?";
                try (PreparedStatement ps = con.prepareStatement(deleteSql)) {
                    ps.setString(1, oldStopCode);
                    ps.executeUpdate();
                }
                con.commit();
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        }
    }

    public void toggleStopOverride(String stopCode) throws SQLException {
        String sql = "UPDATE stops SET is_active_override = 1 - is_active_override WHERE stop_code = ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, stopCode);
            ps.executeUpdate();
        }
    }

    public void preloadStops3To10() throws SQLException {
        String[][] defaults = new String[][]{
                {"Stop 3", "Sundarapuram Junction", "BUS-01", "10.9482", "76.9714", "3"},
                {"Stop 4", "Kuniyamuthur", "BUS-01", "10.9635", "76.9421", "4"},
                {"Stop 5", "Kovaipudur Junction", "BUS-01", "10.9387", "76.9328", "5"},
                {"Stop 6", "Perur Temple Junction", "BUS-01", "10.9708", "76.9205", "6"},
                {"Stop 7", "Thondamuthur Main Junction", "BUS-01", "10.9984", "76.8372", "7"},
                {"Stop 8", "Alandurai Junction", "BUS-01", "10.9572", "76.7865", "8"},
                {"Stop 9", "Pooluvapatti", "BUS-01", "10.9610", "76.8020", "9"},
                {"Stop 10", "Narasipuram", "BUS-01", "10.9680", "76.7750", "10"}
        };
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "INSERT INTO stops (stop_code, stop_name, bus_id, latitude, longitude, stop_order) "
                             + "VALUES (?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE stop_name = VALUES(stop_name), "
                             + "latitude = VALUES(latitude), longitude = VALUES(longitude), stop_order = VALUES(stop_order)")) {
            for (String[] def : defaults) {
                ps.setString(1, def[0]);
                ps.setString(2, def[1]);
                ps.setString(3, def[2]);
                ps.setDouble(4, Double.parseDouble(def[3]));
                ps.setDouble(5, Double.parseDouble(def[4]));
                ps.setInt(6, Integer.parseInt(def[5]));
                ps.executeUpdate();
            }
        }
    }

    private static String callOsrm(String url) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .header("User-Agent", "CIET-SmartTransport/1.0")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            return response.body();
        }
        throw new IOException("OSRM returned HTTP " + response.statusCode());
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(input.hashCode());
        }
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return 6371.0 * c;
    }

    private static String generateFallbackRoadPolyline(double depotLat, double depotLon, List<ManagedStop> stops, double collegeLat, double collegeLon) {
        StringBuilder sb = new StringBuilder("[");
        sb.append(String.format(Locale.ROOT, "[%.6f,%.6f]", depotLon, depotLat));
        for (ManagedStop st : stops) {
            sb.append(String.format(Locale.ROOT, ",[%.6f,%.6f]", st.longitude, st.latitude));
        }
        sb.append(String.format(Locale.ROOT, ",[%.6f,%.6f]", collegeLon, collegeLat));
        sb.append("]");
        return sb.toString();
    }

    public RealRouteResult getOrCalculateSharedRoute(String busId, LocalDate date, boolean forceRecalculate, String highlightStopCode)
            throws SQLException {
        return calculateRealRoadRouteInternal(busId, date, forceRecalculate, highlightStopCode);
    }

    public RealRouteResult recalculateAndSaveSharedRoute(String busId, LocalDate date) throws SQLException {
        return calculateRealRoadRouteInternal(busId, date, true, null);
    }

    public RealRouteResult calculateRealRoadRoute(String busId, LocalDate date, String highlightStopCode)
            throws SQLException {
        return calculateRealRoadRouteInternal(busId, date, true, highlightStopCode);
    }

    public RealRouteResult calculateRealRoadRouteInternal(String busId, LocalDate date, boolean forceRecalculate, String highlightStopCode)
            throws SQLException {
        String normBus = normalizeBusId(busId);
        if (date == null) {
            date = LocalDate.now();
        }

        // 1. Fetch stops for this bus
        List<ManagedStop> stops = getManagedStops(normBus);
        if (stops.isEmpty()) {
            stops = getManagedStops("BUS-01");
        }
        // Ensure strictly sorted by numeric stop order: Stop 1 to Stop 10
        stops.sort(Comparator.comparingInt(s -> {
            String num = s.stopCode.replaceAll("[^0-9]", "");
            return num.isEmpty() ? s.stopOrder : Integer.parseInt(num);
        }));

        // 2. Fetch students assigned to this bus and attach to stops
        String studentSql = "SELECT student_id, name, stop_name, transport_authorized FROM students WHERE bus_id = ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(studentSql)) {
            ps.setString(1, normBus);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String rawStop = rs.getString("stop_name");
                    String normStop = normalizeStopCode(rawStop);
                    String sId = rs.getString("student_id");
                    String sName = rs.getString("name");

                    for (ManagedStop ms : stops) {
                        if (ms.stopCode.equalsIgnoreCase(normStop)
                                || ms.stopName.equalsIgnoreCase(normStop)
                                || normalizeStopCode(ms.stopCode).equalsIgnoreCase(normStop)) {
                            ms.assignedStudentIds.add(sId);
                            ms.assignedStudentNames.add(sName);
                            break;
                        }
                    }
                }
            }
        }

        // 3. Fetch attendance for date from MySQL
        Map<String, String> attendanceMap = new HashMap<>();
        String attSql = "SELECT student_id, status FROM attendance WHERE attendance_date = ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(attSql)) {
            ps.setDate(1, Date.valueOf(date));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    attendanceMap.put(rs.getString("student_id"), rs.getString("status").toUpperCase(Locale.ROOT));
                }
            }
        }

        // 4. Compute deterministic attendance signature for this bus corridor and date
        StringBuilder sigBuilder = new StringBuilder();
        for (ManagedStop ms : stops) {
            sigBuilder.append(ms.stopCode).append(":");
            for (String sid : ms.assignedStudentIds) {
                sigBuilder.append(sid).append("=").append(attendanceMap.getOrDefault(sid, "UNMARKED")).append(",");
            }
            sigBuilder.append(";");
        }
        String computedSignature = sha256Hex(sigBuilder.toString());

        // 5. Apply attendance rules to determine active and skipped stops
        List<ManagedStop> activeStops = new ArrayList<>();
        List<ManagedStop> skippedStops = new ArrayList<>();
        List<ManagedStop> excludedStops = new ArrayList<>();

        for (ManagedStop stop : stops) {
            int totalAssigned = stop.assignedStudentIds.size();
            if (totalAssigned == 0) {
                // Rule 4: If a stop has no assigned students, exclude it unless Management manually activates it.
                if (stop.isActiveOverride) {
                    stop.status = StopStatus.REQUIRED;
                    stop.statusReason = "Manually activated by Management (no students assigned).";
                    activeStops.add(stop);
                } else {
                    stop.status = StopStatus.EXCLUDED;
                    stop.statusReason = "Excluded: No students currently assigned (Management can manually activate).";
                    excludedStops.add(stop);
                }
                continue;
            }

            int pCount = 0;
            int aCount = 0;
            int mCount = 0;

            for (String sid : stop.assignedStudentIds) {
                String st = attendanceMap.get(sid);
                if ("PRESENT".equalsIgnoreCase(st)) {
                    pCount++;
                } else if ("ABSENT".equalsIgnoreCase(st)) {
                    aCount++;
                } else {
                    mCount++;
                }
            }
            stop.presentCount = pCount;
            stop.absentCount = aCount;
            stop.unconfirmedCount = mCount;

            // Rule 1: Skipped only when ALL assigned students confirmed ABSENT
            if (aCount == totalAssigned) {
                stop.status = StopStatus.SKIPPED;
                stop.statusReason = "All " + totalAssigned + " assigned student" + (totalAssigned > 1 ? "s are" : " is") + " absent.";
                skippedStops.add(stop);
            }
            // Rule 2: If at least one Present -> retain
            else if (pCount >= 1) {
                stop.status = StopStatus.REQUIRED;
                stop.statusReason = "Active (" + pCount + " present" + (aCount > 0 ? ", " + aCount + " absent" : "")
                        + (mCount > 0 ? ", " + mCount + " unconfirmed" : "") + ").";
                activeStops.add(stop);
            }
            // Rule 3: Missing/unknown attendance -> do NOT skip
            else {
                stop.status = StopStatus.REQUIRED;
                stop.statusReason = "Active (" + mCount + " student" + (mCount > 1 ? "s have" : " has")
                        + " unconfirmed attendance — preserved for safety).";
                activeStops.add(stop);
            }
        }

        // Maintain strict sequence: Stop 1 -> Stop 2 -> ... -> Stop 10
        List<ManagedStop> orderedActiveStops = new ArrayList<>(activeStops);
        orderedActiveStops.sort(Comparator.comparingInt(s -> {
            String num = s.stopCode.replaceAll("[^0-9]", "");
            return num.isEmpty() ? s.stopOrder : Integer.parseInt(num);
        }));

        String serviceNotice = "Continuous route calculated via pure Java Dijkstra shortest-path algorithm over the verified road network. Connects Starting Point, active stops in fixed sequence (Stop 1 to 10), and Campus.";

        // 6. Check MySQL route_date_cache for existing shared route
        if (!forceRecalculate) {
            String readCache = "SELECT distance_meters, duration_seconds, geojson_geometry, last_updated, input_signature "
                    + "FROM route_date_cache WHERE bus_id = ? AND route_date = ? LIMIT 1";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(readCache)) {
                ps.setString(1, normBus);
                ps.setDate(2, Date.valueOf(date));
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String cachedSig = rs.getString("input_signature");
                        String cachedGeo = rs.getString("geojson_geometry");
                        double distanceMeters = rs.getDouble("distance_meters");
                        if (cachedSig != null && cachedSig.equals(computedSignature) && cachedGeo != null && !cachedGeo.trim().isEmpty() && !cachedGeo.equals("[]") && (distanceMeters > 3000.0 || orderedActiveStops.isEmpty())) {
                            double durationSeconds = rs.getDouble("duration_seconds");
                            Timestamp lastUpdated = rs.getTimestamp("last_updated");
                            return new RealRouteResult(
                                    normBus, date, true, serviceNotice, distanceMeters, durationSeconds,
                                    stops, orderedActiveStops, skippedStops, excludedStops, cachedGeo, lastUpdated
                            );
                        }
                    }
                }
            } catch (SQLException ignore) {}
        }

        // 7. Calculate new route using Java Dijkstra shortest path on real road network graph
        double depotLat = 11.0168;
        double depotLon = 76.9558;
        double collegeLat = 10.9632;
        double collegeLon = 76.8142;

        Location dLoc = findLocation("BUS_START", normBus);
        if (dLoc != null) {
            depotLat = dLoc.latitude;
            depotLon = dLoc.longitude;
        }
        Location cLoc = findLocation("COLLEGE", "COLLEGE");
        if (cLoc != null) {
            collegeLat = cLoc.latitude;
            collegeLon = cLoc.longitude;
        }

        boolean dijkstraSuccess = false;
        double distanceMeters = 0.0;
        double durationSeconds = 0.0;
        String geojsonCoords = null;
        Timestamp now = new Timestamp(System.currentTimeMillis());

        try (Connection con = DatabaseConnection.getConnection()) {
            Map<String, double[]> nodeCoords = new LinkedHashMap<>();
            nodeCoords.put("DEPOT", new double[]{depotLat, depotLon});
            for (ManagedStop st : stops) {
                nodeCoords.put(st.stopCode, new double[]{st.latitude, st.longitude});
            }
            nodeCoords.put("COLLEGE", new double[]{collegeLat, collegeLon});
            nodeCoords.put("JCT_ATHUPALAM", new double[]{10.9812, 76.9520});
            nodeCoords.put("JCT_SELVAPURAM", new double[]{10.9830, 76.9350});
            nodeCoords.put("JCT_SIRUVANI", new double[]{10.9650, 76.8150});
            nodeCoords.put("JCT_WEST_BYPASS", new double[]{11.0050, 76.9100});

            DijkstraRouteFinder.RoadGraph graph = dijkstraRouteFinder.loadOrBuildRoadGraph(con, normBus, nodeCoords, null);

            List<String> activeLocationKeys = new ArrayList<>();
            activeLocationKeys.add("DEPOT");
            for (ManagedStop st : orderedActiveStops) {
                activeLocationKeys.add(st.stopCode);
            }
            activeLocationKeys.add("COLLEGE");

            List<String> skippedKeys = new ArrayList<>();
            for (ManagedStop st : skippedStops) {
                skippedKeys.add(st.stopCode);
            }

            DijkstraRouteFinder.CompleteRouteResult dResult = dijkstraRouteFinder.calculateContinuousRoute(
                    graph, normBus, date, activeLocationKeys, skippedKeys
            );

            distanceMeters = dResult.totalDistanceMeters;
            durationSeconds = dResult.totalDurationSeconds;
            geojsonCoords = dResult.fullGeoJsonCoordinates;
            dijkstraSuccess = true;
            serviceNotice = "Continuous route calculated via pure Java Dijkstra shortest-path algorithm over the verified road network. Connects Starting Point, active stops in fixed sequence (Stop 1 to 10), and Campus.";

        } catch (Exception e) {
            System.err.println("[WARN] Dijkstra routing calculation error: " + e.getMessage());
            dijkstraSuccess = false;
            serviceNotice = "Routing data unavailable: " + e.getMessage();
        }

        if (!dijkstraSuccess || geojsonCoords == null) {
            serviceNotice = "Road-network data is unavailable. Please ensure routing service or cached road data is accessible. Fake route generation is disabled.";
            distanceMeters = 0.0;
            durationSeconds = 0.0;
            geojsonCoords = "[]";
        }

        // Save / update shared route in MySQL route_date_cache
        String seq = String.join(",", orderedActiveStops.stream().map(s -> s.stopCode).toArray(String[]::new));
        String skippedSeq = String.join(",", skippedStops.stream().map(s -> s.stopCode).toArray(String[]::new));
        String upsertCache = "INSERT INTO route_date_cache (bus_id, route_date, status, distance_meters, duration_seconds, "
                + "ordered_stop_codes, skipped_stop_codes, geojson_geometry, input_signature, last_updated) "
                + "VALUES (?, ?, 'CALCULATED', ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE "
                + "status = 'CALCULATED', distance_meters = VALUES(distance_meters), duration_seconds = VALUES(duration_seconds), "
                + "ordered_stop_codes = VALUES(ordered_stop_codes), skipped_stop_codes = VALUES(skipped_stop_codes), "
                + "geojson_geometry = VALUES(geojson_geometry), input_signature = VALUES(input_signature), last_updated = VALUES(last_updated)";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(upsertCache)) {
            ps.setString(1, normBus);
            ps.setDate(2, Date.valueOf(date));
            ps.setDouble(3, distanceMeters);
            ps.setDouble(4, durationSeconds);
            ps.setString(5, seq);
            ps.setString(6, skippedSeq);
            ps.setString(7, geojsonCoords);
            ps.setString(8, computedSignature);
            ps.setTimestamp(9, now);
            ps.executeUpdate();
        }

        return new RealRouteResult(
                normBus, date, dijkstraSuccess, serviceNotice, distanceMeters, durationSeconds,
                stops, orderedActiveStops, skippedStops, excludedStops, geojsonCoords, now
        );
    }

    private static double extractJsonDouble(String json, String key) {
        Pattern pat = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*([0-9.]+)");
        Matcher mat = pat.matcher(json);
        return mat.find() ? Double.parseDouble(mat.group(1)) : 0.0;
    }

    private static String extractGeojsonCoords(String json) {
        int start = json.indexOf("\"coordinates\":[");
        if (start == -1) return null;
        int depth = 0;
        int end = -1;
        for (int i = start + 14; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) {
                    end = i + 1;
                    break;
                }
            }
        }
        if (end != -1) {
            return json.substring(start + 14, end);
        }
        return null;
    }

    public static class TerminalLocations {
        public final String depotName;
        public final String busId;
        public final double depotLatitude;
        public final double depotLongitude;
        public final String collegeName;
        public final double collegeLatitude;
        public final double collegeLongitude;

        public TerminalLocations(String depotName, String busId, double depotLatitude, double depotLongitude,
                                 String collegeName, double collegeLatitude, double collegeLongitude) {
            this.depotName = depotName;
            this.busId = busId;
            this.depotLatitude = depotLatitude;
            this.depotLongitude = depotLongitude;
            this.collegeName = collegeName;
            this.collegeLatitude = collegeLatitude;
            this.collegeLongitude = collegeLongitude;
        }
    }

    public TerminalLocations getTerminalLocations(String busId) throws SQLException {
        String normBus = normalizeBusId(busId);
        Location dLoc = findLocation("BUS_START", normBus);
        Location cLoc = findLocation("COLLEGE", "COLLEGE");
        double dLat = dLoc != null ? dLoc.latitude : 11.0168;
        double dLon = dLoc != null ? dLoc.longitude : 76.9558;
        double cLat = cLoc != null ? cLoc.latitude : 10.9632;
        double cLon = cLoc != null ? cLoc.longitude : 76.8142;
        return new TerminalLocations("Gandhipuram Central Terminal (Depot)", normBus, dLat, dLon,
                COLLEGE_DESTINATION_NAME, cLat, cLon);
    }

    public void updateTerminalLocations(String busId, double depotLat, double depotLon,
                                        double collegeLat, double collegeLon) throws SQLException {
        if (!isValidCoordinate(depotLat, depotLon) || !isValidCoordinate(collegeLat, collegeLon)) {
            throw new IllegalArgumentException("Enter valid coordinates (-90 to 90, -180 to 180)");
        }
        String normBus = normalizeBusId(busId);
        saveLocation("BUS_START", normBus, normBus, depotLat, depotLon);
        saveLocation("COLLEGE", "COLLEGE", null, collegeLat, collegeLon);
    }

    public void reassignStudent(String studentId, String newStopName) throws SQLException {
        if (studentId == null || studentId.trim().isEmpty() || newStopName == null || newStopName.trim().isEmpty()) {
            throw new IllegalArgumentException("Student ID and pickup stop are required.");
        }
        String normStop = normalizeStopCode(newStopName);
        String sql = "UPDATE students SET stop_name = ? WHERE student_id = ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, normStop);
            ps.setString(2, studentId.trim());
            int updated = ps.executeUpdate();
            if (updated == 0) {
                throw new SQLException("Student ID not found: " + studentId);
            }
        }
    }

    public String renderLeafletMapHtml(RealRouteResult route, String role, String highlightStopCode) {
        StringBuilder sb = new StringBuilder();

        // 1. Notice banner (offline alert only)
        if (!route.serviceAvailable) {
            sb.append("<div class=\"alert-callout alert-warning\" style=\"border-left-color: var(--red-danger); margin-bottom: 14px;\">\n")
                    .append("  <div class=\"alert-icon\" style=\"color: var(--red-danger);\">&#9888;</div>\n")
                    .append("  <div><div class=\"alert-title\" style=\"color: var(--red-danger);\">Routing Service Offline Notice</div>")
                    .append("<div class=\"alert-body\">").append(escapeHtml(route.serviceNotice)).append("</div></div>\n")
                    .append("</div>\n");
        }

        // 2. Metrics Bar
        String distStr = route.distanceMeters > 0
                ? String.format(Locale.ROOT, "%.2f km", route.distanceMeters / 1000.0)
                : "Unavailable";
        String timeStr = route.durationSeconds > 0
                ? String.valueOf(Math.round(route.durationSeconds / 60.0)) + " mins"
                : "Unavailable";
        String lastUpdatedStr = route.lastUpdated != null
                ? route.lastUpdated.toString().substring(0, 19).replace("T", " ")
                : String.valueOf(route.date);

        // Sequence flow preview
        StringBuilder seqFlow = new StringBuilder("Starting Point");
        for (ManagedStop st : route.orderedStops) {
            String num = st.stopCode.replaceAll("[^0-9]", "");
            seqFlow.append(" &rarr; Stop ").append(num.isEmpty() ? String.valueOf(st.stopOrder) : num);
        }
        seqFlow.append(" &rarr; Campus");
        if (!route.skippedStops.isEmpty()) {
            seqFlow.append(" <span style=\"color:#ef4444;font-size:0.85rem;\">(Bypassed: ");
            for (int si = 0; si < route.skippedStops.size(); si++) {
                if (si > 0) seqFlow.append(", ");
                seqFlow.append(route.skippedStops.get(si).stopCode);
            }
            seqFlow.append(")</span>");
        }

        // 2. Interactive Leaflet Map (Immediately displayed below heading/notice)
        double depotLat = 11.0168, depotLon = 76.9558;
        double collegeLat = 10.9632, collegeLon = 76.8142;
        try {
            Location dLoc = findLocation("BUS_START", route.busId);
            if (dLoc != null) { depotLat = dLoc.latitude; depotLon = dLoc.longitude; }
            Location cLoc = findLocation("COLLEGE", "COLLEGE");
            if (cLoc != null) { collegeLat = cLoc.latitude; collegeLon = cLoc.longitude; }
        } catch (SQLException ignored) {}

        String normHighlight = highlightStopCode != null ? normalizeStopCode(highlightStopCode) : "";

        // Parse coordinates
        List<double[]> pathPoints = new ArrayList<>();
        if (route.geojsonCoordinates != null && route.geojsonCoordinates.length() > 5) {
            Pattern coordPat = Pattern.compile("\\[\\s*(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)\\s*\\]");
            Matcher mat = coordPat.matcher(route.geojsonCoordinates);
            while (mat.find()) {
                double lon = Double.parseDouble(mat.group(1));
                double lat = Double.parseDouble(mat.group(2));
                pathPoints.add(new double[]{lat, lon});
            }
        }

        sb.append("<link rel=\"stylesheet\" href=\"leaflet.css\" />\n");
        sb.append("<div id=\"leaflet-map-wrapper\" style=\"position:relative;width:100%;margin-bottom:18px;\">\n")
                .append("  <div id=\"map\" style=\"width:100%;height:540px;min-height:540px;border-radius:12px;border:1px solid var(--border-light);box-shadow:var(--shadow-sm);background:#0f172a;position:relative;z-index:1;\"></div>\n")
                .append("  <div id=\"map-error-banner\" class=\"alert-callout alert-warning\" style=\"display:none;margin-top:12px;border-left-color:var(--red-danger);\">\n")
                .append("    <div class=\"alert-icon\" style=\"color:var(--red-danger);\">&#9888;</div>\n")
                .append("    <div><div class=\"alert-title\" style=\"color:var(--red-danger);\">Map Service Alert</div>\n")
                .append("    <div class=\"alert-body\" id=\"map-error-text\">Map tiles or road data could not be displayed.</div></div>\n")
                .append("  </div>\n")
                .append("</div>\n");

        sb.append("<script src=\"leaflet.js\"></script>\n");
        sb.append("<script>\n")
                .append("(function() {\n")
                .append("  if (typeof L === 'undefined') {\n")
                .append("    var b = document.getElementById('map-error-banner');\n")
                .append("    if (b) { b.style.display = 'flex'; document.getElementById('map-error-text').textContent = 'Leaflet library failed to load.'; }\n")
                .append("    return;\n")
                .append("  }\n")
                .append("  try {\n")
                .append("    var map = L.map('map', { center: [10.985, 76.89], zoom: 12, scrollWheelZoom: false });\n")
                .append("    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {\n")
                .append("      maxZoom: 19,\n")
                .append("      attribution: '&copy; <a href=\"https://www.openstreetmap.org/copyright\" target=\"_blank\">OpenStreetMap</a> contributors'\n")
                .append("    }).on('tileerror', function() {\n")
                .append("      var b = document.getElementById('map-error-banner');\n")
                .append("      if (b) { b.style.display = 'flex'; document.getElementById('map-error-text').textContent = 'OpenStreetMap tile server is unreachable. Offline mode active.'; }\n")
                .append("    }).addTo(map);\n\n");

        // Add Starting Point marker
        sb.append("    var depotIcon = L.divIcon({\n")
                .append("      className: 'custom-leaflet-marker',\n")
                .append("      html: '<div style=\"background:#2563eb;color:#fff;width:34px;height:34px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-weight:900;font-size:10px;border:2.5px solid #fff;box-shadow:0 3px 8px rgba(0,0,0,0.35);\">DEPOT</div>',\n")
                .append("      iconSize: [34, 34],\n")
                .append("      iconAnchor: [17, 17]\n")
                .append("    });\n")
                .append(String.format(Locale.ROOT, "    L.marker([%.6f, %.6f], { icon: depotIcon }).addTo(map).bindPopup('<b>Starting Point (Depot)</b><br>Gandhipuram Central Terminal<br>Coordinates: %.4f, %.4f');\n\n",
                        depotLat, depotLon, depotLat, depotLon));

        // Add College marker
        sb.append("    var collegeIcon = L.divIcon({\n")
                .append("      className: 'custom-leaflet-marker',\n")
                .append("      html: '<div style=\"background:#10b981;color:#fff;width:auto;min-width:34px;height:34px;border-radius:17px;padding:0 8px;display:flex;align-items:center;justify-content:center;font-weight:900;font-size:10px;border:2.5px solid #fff;box-shadow:0 3px 8px rgba(0,0,0,0.35);\">Campus</div>',\n")
                .append("      iconSize: [44, 34],\n")
                .append("      iconAnchor: [22, 17]\n")
                .append("    });\n")
                .append(String.format(Locale.ROOT, "    L.marker([%.6f, %.6f], { icon: collegeIcon }).addTo(map).bindPopup('<b>Final Destination</b><br>Campus<br>Coordinates: %.4f, %.4f');\n\n",
                        collegeLat, collegeLon, collegeLat, collegeLon));

        // Add Numbered Markers for Stop 1 to Stop 10
        for (ManagedStop st : route.allStops) {
            String num = st.stopCode.replaceAll("[^0-9]", "");
            if (num.isEmpty()) num = String.valueOf(st.stopOrder);

            boolean isHighlighted = !normHighlight.isEmpty() && normHighlight.equalsIgnoreCase(st.stopCode);
            boolean isActive = st.status == StopStatus.REQUIRED;
            boolean isSkipped = st.status == StopStatus.SKIPPED;

            String iconHtml;
            int size = 30;
            if (isHighlighted) {
                size = 38;
                String bg = isSkipped ? "#ef4444" : "#f59e0b";
                String txt = isSkipped ? "#ffffff" : "#000000";
                iconHtml = "<div style=\\\"background:" + bg + ";color:" + txt + ";width:38px;height:38px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-weight:900;font-size:12px;border:3px solid #fff;box-shadow:0 0 16px rgba(245,158,11,0.95);\\\">★ " + num + "</div>";
            } else if (isActive) {
                size = 30;
                iconHtml = "<div style=\\\"background:#0f172a;color:#10b981;width:30px;height:30px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-weight:800;font-size:12px;border:2.5px solid #10b981;box-shadow:0 2px 6px rgba(0,0,0,0.3);\\\">" + num + "</div>";
            } else if (isSkipped) {
                size = 28;
                iconHtml = "<div style=\\\"background:#fee2e2;color:#dc2626;width:28px;height:28px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-weight:800;font-size:11px;border:2px solid #dc2626;text-decoration:line-through;box-shadow:0 2px 4px rgba(0,0,0,0.2);\\\">" + num + "</div>";
            } else {
                size = 26;
                iconHtml = "<div style=\\\"background:#1e293b;color:#94a3b8;width:26px;height:26px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-weight:700;font-size:10px;border:1.5px solid #64748b;\\\">" + num + "</div>";
            }

            StringBuilder popupText = new StringBuilder();
            if (isHighlighted) {
                popupText.append("<div style=\\\"color:#d97706;font-weight:800;font-size:1.05rem;\\\">&#9733; YOUR DESIGNATED PICKUP STOP</div>");
            }
            popupText.append("<b>").append(escapeHtml(st.stopCode)).append(" &bull; ").append(escapeHtml(st.stopName)).append("</b><br>");
            popupText.append("Coordinates: ").append(String.format(Locale.ROOT, "%.4f, %.4f", st.latitude, st.longitude)).append("<br>");
            if (isActive) {
                popupText.append("Status: <span style=\\\"color:#10b981;font-weight:bold;\\\">ACTIVE ON ROUTE</span><br>");
            } else if (isSkipped) {
                popupText.append("Status: <span style=\\\"color:#dc2626;font-weight:bold;\\\">SKIPPED</span><br>");
                popupText.append("Reason: <span style=\\\"color:#b91c1c;\\\">").append(escapeHtml(st.statusReason)).append("</span><br>");
            } else {
                popupText.append("Status: <span style=\\\"color:#64748b;\\\">EXCLUDED (0 STUDENTS)</span><br>");
            }
            if (!st.assignedStudentNames.isEmpty()) {
                popupText.append("Assigned: ").append(escapeHtml(String.join(", ", st.assignedStudentNames)));
            }

            sb.append(String.format(Locale.ROOT,
                    "    var sIcon_%s = L.divIcon({ className: 'custom-leaflet-marker', html: '%s', iconSize: [%d, %d], iconAnchor: [%d, %d] });\n",
                    num, iconHtml, size, size, size / 2, size / 2));
            sb.append(String.format(Locale.ROOT,
                    "    var marker_%s = L.marker([%.6f, %.6f], { icon: sIcon_%s }).addTo(map).bindPopup('%s');\n",
                    num, st.latitude, st.longitude, num, popupText.toString()));
            if (isHighlighted) {
                sb.append(String.format(Locale.ROOT, "    marker_%s.openPopup();\n", num));
            }
            sb.append("\n");
        }

        // Add Dijkstra Polyline
        if (!pathPoints.isEmpty()) {
            sb.append("    var routeCoordinates = [\n");
            for (int i = 0; i < pathPoints.size(); i++) {
                double[] pt = pathPoints.get(i);
                sb.append(String.format(Locale.ROOT, "      [%.6f, %.6f]%s\n", pt[0], pt[1], (i < pathPoints.size() - 1 ? "," : "")));
            }
            sb.append("    ];\n");
            sb.append("    var polyline = L.polyline(routeCoordinates, {\n")
                    .append("      color: '#2563eb',\n")
                    .append("      weight: 5,\n")
                    .append("      opacity: 0.9,\n")
                    .append("      smoothFactor: 1\n")
                    .append("    }).addTo(map);\n")
                    .append("    map.fitBounds(polyline.getBounds(), { padding: [35, 35] });\n");
        }

        sb.append("    setTimeout(function() { map.invalidateSize(); }, 250);\n")
                .append("  } catch(err) {\n")
                .append("    console.error('Leaflet script error:', err);\n")
                .append("    var b = document.getElementById('map-error-banner');\n")
                .append("    if (b) { b.style.display = 'flex'; document.getElementById('map-error-text').textContent = 'Map initialization error: ' + err.message; }\n")
                .append("  }\n")
                .append("})();\n")
                .append("</script>\n");

        // Fallback for non-JS
        sb.append("<noscript>\n");
        sb.append(renderSvgRoadMapHtml(route, role, highlightStopCode));
        sb.append("</noscript>\n");

        // 3. Route Information Bar (Directly BELOW the map)
        sb.append("<div class=\"route-metrics-bar\" style=\"display:flex;flex-wrap:wrap;gap:12px;margin:18px 0 16px;\">\n")
                .append("  <div class=\"metric-pill\" style=\"background:var(--bg-card);border:1px solid var(--border-light);padding:10px 16px;border-radius:var(--radius-sm);\">\n")
                .append("    <span style=\"font-size:0.75rem;color:var(--text-muted);display:block;text-transform:uppercase;\">Total Route Distance</span>\n")
                .append("    <strong style=\"font-size:1.2rem;color:var(--navy-primary);\">").append(distStr).append("</strong>\n")
                .append("  </div>\n")
                .append("  <div class=\"metric-pill\" style=\"background:var(--bg-card);border:1px solid var(--border-light);padding:10px 16px;border-radius:var(--radius-sm);\">\n")
                .append("    <span style=\"font-size:0.75rem;color:var(--text-muted);display:block;text-transform:uppercase;\">Estimated Travel Time</span>\n")
                .append("    <strong style=\"font-size:1.2rem;color:var(--navy-primary);\">").append(timeStr).append("</strong>\n")
                .append("  </div>\n")
                .append("  <div class=\"metric-pill\" style=\"background:var(--bg-card);border:1px solid var(--border-light);padding:10px 16px;border-radius:var(--radius-sm);\">\n")
                .append("    <span style=\"font-size:0.75rem;color:var(--text-muted);display:block;text-transform:uppercase;\">Active Stops</span>\n")
                .append("    <strong style=\"font-size:1.2rem;color:var(--green-success);\">").append(route.activeStopsInVisitingOrder.size()).append(" Active</strong>\n")
                .append("  </div>\n")
                .append("  <div class=\"metric-pill\" style=\"background:var(--bg-card);border:1px solid var(--border-light);padding:10px 16px;border-radius:var(--radius-sm);\">\n")
                .append("    <span style=\"font-size:0.75rem;color:var(--text-muted);display:block;text-transform:uppercase;\">Skipped Stops</span>\n")
                .append("    <strong style=\"font-size:1.2rem;color:").append(route.skippedStops.isEmpty() ? "var(--text-muted)" : "var(--red-danger)").append(";\">")
                .append(route.skippedStops.size()).append(" Skipped</strong>\n")
                .append("  </div>\n")
                .append("  <div class=\"metric-pill\" style=\"background:var(--bg-card);border:1px solid var(--border-light);padding:10px 16px;border-radius:var(--radius-sm);\">\n")
                .append("    <span style=\"font-size:0.75rem;color:var(--text-muted);display:block;text-transform:uppercase;\">Selected Date</span>\n")
                .append("    <strong style=\"font-size:1.1rem;color:var(--text-body);\">").append(route.date).append("</strong>\n")
                .append("  </div>\n")
                .append("  <div class=\"metric-pill\" style=\"background:var(--bg-card);border:1px solid var(--border-light);padding:10px 16px;border-radius:var(--radius-sm);\">\n")
                .append("    <span style=\"font-size:0.75rem;color:var(--text-muted);display:block;text-transform:uppercase;\">Last Updated Time</span>\n")
                .append("    <strong style=\"font-size:0.95rem;color:var(--text-body);\">").append(lastUpdatedStr).append("</strong>\n")
                .append("  </div>\n")
                .append("  <div class=\"metric-pill\" style=\"background:var(--bg-card);border:1px solid var(--border-light);padding:10px 16px;border-radius:var(--radius-sm);display:flex;align-items:center;\">\n")
                .append("    <form method=\"POST\" action=\"/recalculate-route\" style=\"margin:0;display:flex;align-items:center;\">\n")
                .append("      <input type=\"hidden\" name=\"busId\" value=\"").append(escapeHtml(route.busId)).append("\">\n")
                .append("      <input type=\"hidden\" name=\"date\" value=\"").append(route.date).append("\">\n")
                .append("      <button type=\"submit\" class=\"btn btn-primary\" style=\"padding:7px 16px;font-weight:700;font-size:0.85rem;cursor:pointer;\">&#8635; Recalculate Route</button>\n")
                .append("    </form>\n")
                .append("  </div>\n")
                .append("  <div class=\"metric-pill\" style=\"background:var(--bg-card);border:1px solid var(--border-light);padding:10px 16px;border-radius:var(--radius-sm);flex:1 1 100%;\">\n")
                .append("    <span style=\"font-size:0.75rem;color:var(--text-muted);display:block;text-transform:uppercase;\">Fixed Stop Sequence (Continuous Route)</span>\n")
                .append("    <span style=\"font-size:0.95rem;color:var(--navy-primary);font-weight:600;\">").append(seqFlow.toString()).append("</span>\n")
                .append("  </div>\n")
                .append("</div>\n");


        return sb.toString();
    }

    public String renderSvgRoadMapHtml(RealRouteResult route, String role, String highlightStopCode) {
        StringBuilder sb = new StringBuilder();

        // 1. Notice banner (offline alert only)
        if (!route.serviceAvailable) {
            sb.append("<div class=\"alert-callout alert-warning\" style=\"border-left-color: var(--red-danger); margin-bottom: 14px;\">\n")
                    .append("  <div class=\"alert-icon\" style=\"color: var(--red-danger);\">&#9888;</div>\n")
                    .append("  <div><div class=\"alert-title\" style=\"color: var(--red-danger);\">Routing Service Offline Notice</div>")
                    .append("<div class=\"alert-body\">").append(escapeHtml(route.serviceNotice)).append("</div></div>\n")
                    .append("</div>\n");
        }

        // 2. Metrics Bar
        String distStr = route.distanceMeters > 0
                ? String.format(Locale.ROOT, "%.2f km", route.distanceMeters / 1000.0)
                : "Unavailable";
        String timeStr = route.durationSeconds > 0
                ? String.valueOf(Math.round(route.durationSeconds / 60.0)) + " mins"
                : "Unavailable";
        String lastUpdatedStr = route.lastUpdated != null
                ? route.lastUpdated.toString().substring(0, 19).replace("T", " ")
                : String.valueOf(route.date);

        // Sequence flow preview
        StringBuilder seqFlow = new StringBuilder("Starting Point");
        for (ManagedStop st : route.orderedStops) {
            String num = st.stopCode.replaceAll("[^0-9]", "");
            seqFlow.append(" &rarr; Stop ").append(num.isEmpty() ? String.valueOf(st.stopOrder) : num);
        }
        seqFlow.append(" &rarr; Campus");
        if (!route.skippedStops.isEmpty()) {
            seqFlow.append(" <span style=\"color:#ef4444;font-size:0.85rem;\">(Bypassed: ");
            for (int si = 0; si < route.skippedStops.size(); si++) {
                if (si > 0) seqFlow.append(", ");
                seqFlow.append(route.skippedStops.get(si).stopCode);
            }
            seqFlow.append(")</span>");
        }

        sb.append("<div class=\"route-metrics-bar\" style=\"display:flex;flex-wrap:wrap;gap:12px;margin-bottom:16px;\">\n")
                .append("  <div class=\"metric-pill\" style=\"background:var(--bg-card);border:1px solid var(--border-light);padding:10px 16px;border-radius:var(--radius-sm);\">\n")
                .append("    <span style=\"font-size:0.75rem;color:var(--text-muted);display:block;text-transform:uppercase;\">Total Road Distance</span>\n")
                .append("    <strong style=\"font-size:1.2rem;color:var(--navy-primary);\">").append(distStr).append("</strong>\n")
                .append("  </div>\n")
                .append("  <div class=\"metric-pill\" style=\"background:var(--bg-card);border:1px solid var(--border-light);padding:10px 16px;border-radius:var(--radius-sm);\">\n")
                .append("    <span style=\"font-size:0.75rem;color:var(--text-muted);display:block;text-transform:uppercase;\">Est. Travel Time</span>\n")
                .append("    <strong style=\"font-size:1.2rem;color:var(--navy-primary);\">").append(timeStr).append("</strong>\n")
                .append("  </div>\n")
                .append("  <div class=\"metric-pill\" style=\"background:var(--bg-card);border:1px solid var(--border-light);padding:10px 16px;border-radius:var(--radius-sm);\">\n")
                .append("    <span style=\"font-size:0.75rem;color:var(--text-muted);display:block;text-transform:uppercase;\">Pickup Stops Status</span>\n")
                .append("    <strong style=\"font-size:1.2rem;color:var(--green-success);\">").append(route.activeStopsInVisitingOrder.size()).append(" Active</strong>")
                .append("<span style=\"color:var(--text-muted);font-size:0.9rem;\"> / ").append(route.skippedStops.size()).append(" Skipped</span>\n")
                .append("  </div>\n")
                .append("  <div class=\"metric-pill\" style=\"background:var(--bg-card);border:1px solid var(--border-light);padding:10px 16px;border-radius:var(--radius-sm);\">\n")
                .append("    <span style=\"font-size:0.75rem;color:var(--text-muted);display:block;text-transform:uppercase;\">Route Calculation Date</span>\n")
                .append("    <strong style=\"font-size:1.1rem;color:var(--text-body);\">").append(route.date).append("</strong>\n")
                .append("    <small style=\"display:block;font-size:0.75rem;color:var(--text-muted);margin-top:2px;\">Updated: ").append(lastUpdatedStr).append("</small>\n")
                .append("  </div>\n")
                .append("  <div class=\"metric-pill\" style=\"background:var(--bg-card);border:1px solid var(--border-light);padding:10px 16px;border-radius:var(--radius-sm);flex:1 1 100%;\">\n")
                .append("    <span style=\"font-size:0.75rem;color:var(--text-muted);display:block;text-transform:uppercase;\">Fixed Stop Sequence (Continuous Route)</span>\n")
                .append("    <span style=\"font-size:0.95rem;color:var(--navy-primary);font-weight:600;\">").append(seqFlow.toString()).append("</span>\n")
                .append("  </div>\n")
                .append("</div>\n");

        // 3. Real Road Map Vector Canvas (Pure SVG, Zero JavaScript)
        String mapId = "interactive-map-" + Math.abs(route.busId.hashCode());

        // Parse GPS Coordinates from OSRM GeoJSON geometry
        List<double[]> pathPoints = new ArrayList<>();
        if (route.geojsonCoordinates != null && route.geojsonCoordinates.length() > 5) {
            Pattern coordPat = Pattern.compile("\\[\\s*(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)\\s*\\]");
            Matcher mat = coordPat.matcher(route.geojsonCoordinates);
            while (mat.find()) {
                double lon = Double.parseDouble(mat.group(1));
                double lat = Double.parseDouble(mat.group(2));
                pathPoints.add(new double[]{lat, lon});
            }
        }

        // Collect coordinates to calculate projection bounds
        double depotLat = 11.0168, depotLon = 76.9558;
        double collegeLat = 10.9632, collegeLon = 76.8142;
        try {
            Location dLoc = findLocation("BUS_START", route.busId);
            if (dLoc != null) { depotLat = dLoc.latitude; depotLon = dLoc.longitude; }
            Location cLoc = findLocation("COLLEGE", "COLLEGE");
            if (cLoc != null) { collegeLat = cLoc.latitude; collegeLon = cLoc.longitude; }
        } catch (SQLException ignored) {}

        double minLat = Math.min(depotLat, collegeLat);
        double maxLat = Math.max(depotLat, collegeLat);
        double minLon = Math.min(depotLon, collegeLon);
        double maxLon = Math.max(depotLon, collegeLon);

        for (ManagedStop st : route.allStops) {
            if (st.latitude < minLat) minLat = st.latitude;
            if (st.latitude > maxLat) maxLat = st.latitude;
            if (st.longitude < minLon) minLon = st.longitude;
            if (st.longitude > maxLon) maxLon = st.longitude;
        }

        for (double[] pt : pathPoints) {
            if (pt[0] < minLat) minLat = pt[0];
            if (pt[0] > maxLat) maxLat = pt[0];
            if (pt[1] < minLon) minLon = pt[1];
            if (pt[1] > maxLon) maxLon = pt[1];
        }

        double dLat = Math.max((maxLat - minLat) * 0.14, 0.02);
        double dLon = Math.max((maxLon - minLon) * 0.14, 0.02);
        minLat -= dLat; maxLat += dLat;
        minLon -= dLon; maxLon += dLon;

        double svgW = 1000.0, svgH = 580.0;
        double padX = 70.0, padY = 70.0;

        sb.append("<div id=\"").append(mapId).append("\" class=\"interactive-map-container\" style=\"width:100%;margin-bottom:12px;\">\n")
                .append("  <svg viewBox=\"0 0 1000 580\" class=\"demo-transit-map-svg\" role=\"img\" aria-label=\"Attendance-Based Automatic Route Road Map\" style=\"width:100%;height:auto;max-height:580px;border-radius:14px;border:1px solid var(--border-light);background:#0b1120;box-shadow:var(--shadow-md);display:block;\">\n")
                .append("    <defs>\n")
                .append("      <filter id=\"pin-shadow\" x=\"-30%\" y=\"-30%\" width=\"160%\" height=\"160%\">\n")
                .append("        <feDropShadow dx=\"0\" dy=\"3\" stdDeviation=\"3\" flood-color=\"#000000\" flood-opacity=\"0.7\" />\n")
                .append("      </filter>\n")
                .append("      <filter id=\"gold-glow\" x=\"-30%\" y=\"-30%\" width=\"160%\" height=\"160%\">\n")
                .append("        <feGaussianBlur stdDeviation=\"4\" result=\"blur\" />\n")
                .append("        <feComposite in=\"SourceGraphic\" in2=\"blur\" operator=\"over\" />\n")
                .append("      </filter>\n")
                .append("    </defs>\n");

        // Background subtle grid and coordinate marks
        sb.append("    <!-- Background Grid -->\n")
                .append("    <g class=\"map-grid\" stroke=\"#15203b\" stroke-width=\"1\" opacity=\"0.6\">\n");
        for (int gx = 100; gx < 1000; gx += 100) {
            sb.append("      <line x1=\"").append(gx).append("\" y1=\"0\" x2=\"").append(gx).append("\" y2=\"580\" />\n");
        }
        for (int gy = 60; gy < 580; gy += 60) {
            sb.append("      <line x1=\"0\" y1=\"").append(gy).append("\" x2=\"1000\" y2=\"").append(gy).append("\" />\n");
        }
        sb.append("    </g>\n");

        // Map Title Watermark
        sb.append("    <!-- Map Brand Watermark -->\n")
                .append("    <text x=\"24\" y=\"32\" fill=\"#334155\" font-size=\"12\" font-weight=\"800\" letter-spacing=\"1.5\">SMART TRANSIT NETWORK &bull; REAL ROAD GEOMETRY (OSRM)</text>\n")
                .append("    <text x=\"976\" y=\"32\" fill=\"#334155\" font-size=\"11\" font-weight=\"700\" text-anchor=\"end\">BUS CORRIDOR ").append(escapeHtml(route.busId)).append("</text>\n");

        // Draw Actual Road Polyline (Geometry from OSRM)
        if (pathPoints.size() > 1) {
            StringBuilder polyD = new StringBuilder();
            for (int i = 0; i < pathPoints.size(); i++) {
                double[] pt = pathPoints.get(i);
                double px = padX + (pt[1] - minLon) / (maxLon - minLon) * (svgW - 2 * padX);
                double py = svgH - padY - (pt[0] - minLat) / (maxLat - minLat) * (svgH - 2 * padY);
                if (i == 0) {
                    polyD.append(String.format(Locale.ROOT, "M %.1f,%.1f", px, py));
                } else {
                    polyD.append(String.format(Locale.ROOT, " L %.1f,%.1f", px, py));
                }
            }

            sb.append("    <!-- Real Road Polyline along Street Network -->\n")
                    .append("    <g class=\"osm-road-layer\">\n")
                    // Casing stroke
                    .append("      <path d=\"").append(polyD).append("\" fill=\"none\" stroke=\"#1e293b\" stroke-width=\"8\" stroke-linecap=\"round\" stroke-linejoin=\"round\" />\n")
                    // Main route stroke
                    .append("      <path d=\"").append(polyD).append("\" fill=\"none\" stroke=\"#d4af37\" stroke-width=\"4\" stroke-linecap=\"round\" stroke-linejoin=\"round\" opacity=\"0.95\" />\n")
                    // Subtle centerline dash
                    .append("      <path d=\"").append(polyD).append("\" fill=\"none\" stroke=\"#fef08a\" stroke-width=\"1\" stroke-dasharray=\"4,8\" opacity=\"0.6\" />\n")
                    .append("    </g>\n");
        } else if (!route.serviceAvailable) {
            // Notice inside map when routing is offline and no cached road line exists
            sb.append("    <text x=\"500\" y=\"290\" fill=\"#ef4444\" font-size=\"14\" font-weight=\"700\" text-anchor=\"middle\">ROUTING SERVICE OFFLINE &mdash; NO FABRICATED OR STRAIGHT-LINE DISTANCES</text>\n");
        }

        // Draw Skipped Stops Bypass Indicators
        for (ManagedStop st : route.skippedStops) {
            double sx = padX + (st.longitude - minLon) / (maxLon - minLon) * (svgW - 2 * padX);
            double sy = svgH - padY - (st.latitude - minLat) / (maxLat - minLat) * (svgH - 2 * padY);
            sb.append("    <!-- Skipped Stop Bypass Indicator -->\n")
                    .append("    <circle cx=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" cy=\"")
                    .append(String.format(Locale.ROOT, "%.1f", sy)).append("\" r=\"20\" fill=\"none\" stroke=\"#ef4444\" stroke-width=\"1.5\" stroke-dasharray=\"3,3\" opacity=\"0.6\" />\n");
        }

        // Draw Bus Origin / Depot Pin (Starting Point)
        double depotX = padX + (depotLon - minLon) / (maxLon - minLon) * (svgW - 2 * padX);
        double depotY = svgH - padY - (depotLat - minLat) / (maxLat - minLat) * (svgH - 2 * padY);
        sb.append("    <!-- Starting Point: Bus Origin / Depot -->\n")
                .append("    <g class=\"map-terminal-pin\" style=\"cursor:pointer;\">\n")
                .append("      <title>Bus Origin / Depot (Starting Point)&#10;Gandhipuram Central Terminal&#10;Coordinates: ").append(String.format(Locale.ROOT, "%.4f, %.4f", depotLat, depotLon)).append("</title>\n")
                .append("      <circle cx=\"").append(String.format(Locale.ROOT, "%.1f", depotX)).append("\" cy=\"").append(String.format(Locale.ROOT, "%.1f", depotY)).append("\" r=\"20\" fill=\"rgba(59, 130, 246, 0.2)\" />\n")
                .append("      <circle cx=\"").append(String.format(Locale.ROOT, "%.1f", depotX)).append("\" cy=\"").append(String.format(Locale.ROOT, "%.1f", depotY)).append("\" r=\"14\" fill=\"#3b82f6\" stroke=\"#ffffff\" stroke-width=\"2\" filter=\"url(#pin-shadow)\" />\n")
                .append("      <text x=\"").append(String.format(Locale.ROOT, "%.1f", depotX)).append("\" y=\"").append(String.format(Locale.ROOT, "%.1f", depotY + 4)).append("\" fill=\"#ffffff\" font-size=\"10\" font-weight=\"900\" text-anchor=\"middle\">START</text>\n")
                .append("      <rect x=\"").append(String.format(Locale.ROOT, "%.1f", depotX - 45)).append("\" y=\"").append(String.format(Locale.ROOT, "%.1f", depotY + 20)).append("\" width=\"90\" height=\"18\" rx=\"4\" fill=\"#1e293b\" stroke=\"#3b82f6\" stroke-width=\"1\" />\n")
                .append("      <text x=\"").append(String.format(Locale.ROOT, "%.1f", depotX)).append("\" y=\"").append(String.format(Locale.ROOT, "%.1f", depotY + 33)).append("\" fill=\"#93c5fd\" font-size=\"9.5\" font-weight=\"700\" text-anchor=\"middle\">Depot (Start)</text>\n")
                .append("    </g>\n");

        // Draw Campus Pin (Final Destination)
        double collegeX = padX + (collegeLon - minLon) / (maxLon - minLon) * (svgW - 2 * padX);
        double collegeY = svgH - padY - (collegeLat - minLat) / (maxLat - minLat) * (svgH - 2 * padY);
        sb.append("    <!-- Final Destination: Campus -->\n")
                .append("      <title>Final Destination&#10;Campus&#10;Coordinates: ").append(String.format(Locale.ROOT, "%.4f, %.4f", collegeLat, collegeLon)).append("</title>\n")
                .append("      <circle cx=\"").append(String.format(Locale.ROOT, "%.1f", collegeX)).append("\" cy=\"").append(String.format(Locale.ROOT, "%.1f", collegeY)).append("\" r=\"24\" fill=\"rgba(212, 175, 55, 0.25)\" filter=\"url(#gold-glow)\" />\n")
                .append("      <circle cx=\"").append(String.format(Locale.ROOT, "%.1f", collegeX)).append("\" cy=\"").append(String.format(Locale.ROOT, "%.1f", collegeY)).append("\" r=\"16\" fill=\"#ffd700\" stroke=\"#ffffff\" stroke-width=\"2.5\" filter=\"url(#pin-shadow)\" />\n")
                .append("      <text x=\"").append(String.format(Locale.ROOT, "%.1f", collegeX)).append("\" y=\"").append(String.format(Locale.ROOT, "%.1f", collegeY + 4)).append("\" fill=\"#000000\" font-size=\"9\" font-weight=\"900\" text-anchor=\"middle\">Campus</text>\n")
                .append("      <rect x=\"").append(String.format(Locale.ROOT, "%.1f", collegeX - 50)).append("\" y=\"").append(String.format(Locale.ROOT, "%.1f", collegeY + 22)).append("\" width=\"100\" height=\"18\" rx=\"4\" fill=\"#1e293b\" stroke=\"#ffd700\" stroke-width=\"1\" />\n")
                .append("      <text x=\"").append(String.format(Locale.ROOT, "%.1f", collegeX)).append("\" y=\"").append(String.format(Locale.ROOT, "%.1f", collegeY + 35)).append("\" fill=\"#ffd700\" font-size=\"9.5\" font-weight=\"700\" text-anchor=\"middle\">Campus</text>\n")
                .append("    </g>\n");

        // Draw Numbered Stop Pins for Stop 1 to Stop 10
        String normHighlight = highlightStopCode != null ? normalizeStopCode(highlightStopCode) : "";
        for (ManagedStop st : route.allStops) {
            double sx = padX + (st.longitude - minLon) / (maxLon - minLon) * (svgW - 2 * padX);
            double sy = svgH - padY - (st.latitude - minLat) / (maxLat - minLat) * (svgH - 2 * padY);
            String num = st.stopCode.replaceAll("[^0-9]", "");
            if (num.isEmpty()) num = String.valueOf(st.stopOrder);

            boolean isHighlighted = !normHighlight.isEmpty() && normHighlight.equalsIgnoreCase(st.stopCode);
            boolean isActive = st.status == StopStatus.REQUIRED;
            boolean isSkipped = st.status == StopStatus.SKIPPED;

            String pinBg = isHighlighted ? "#ffd700" : (isActive ? "#10b981" : (isSkipped ? "#1e293b" : "#334155"));
            String pinBorder = isHighlighted ? "#ffffff" : (isActive ? "#ffffff" : (isSkipped ? "#ef4444" : "#64748b"));
            String textColor = isHighlighted ? "#000000" : (isActive ? "#ffffff" : (isSkipped ? "#ef4444" : "#94a3b8"));
            String labelColor = isHighlighted ? "#ffd700" : (isActive ? "#f8fafc" : (isSkipped ? "#ef4444" : "#94a3b8"));
            String statusText = isActive ? "ACTIVE ON ROUTE" : (isSkipped ? "SKIPPED (ABSENT)" : "EXCLUDED (0 STUDENTS)");

            String tooltip = escapeHtml(st.stopCode + " - " + st.stopName
                    + "\nCoordinates: " + String.format(Locale.ROOT, "%.4f, %.4f", st.latitude, st.longitude)
                    + "\nStatus: " + statusText
                    + "\nReason: " + st.statusReason
                    + "\nAssigned Students: " + st.assignedStudentNames.size());

            sb.append("    <!-- Stop Pin: ").append(escapeHtml(st.stopCode)).append(" -->\n")
                    .append("    <g class=\"map-stop-pin\" style=\"cursor:pointer;\">\n")
                    .append("      <title>").append(tooltip).append("</title>\n");

            if (isHighlighted && isSkipped) {
                sb.append("      <circle cx=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" cy=\"").append(String.format(Locale.ROOT, "%.1f", sy)).append("\" r=\"22\" fill=\"rgba(239, 68, 68, 0.3)\" stroke=\"#ef4444\" stroke-width=\"2\" stroke-dasharray=\"3,3\" />\n")
                        .append("      <circle cx=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" cy=\"").append(String.format(Locale.ROOT, "%.1f", sy)).append("\" r=\"15\" fill=\"#ef4444\" stroke=\"#ffd700\" stroke-width=\"3\" filter=\"url(#pin-shadow)\" />\n")
                        .append("      <text x=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" y=\"").append(String.format(Locale.ROOT, "%.1f", sy + 4.5)).append("\" fill=\"#ffffff\" font-size=\"13\" font-weight=\"900\" text-anchor=\"middle\">&#9733;</text>\n")
                        .append("      <text x=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" y=\"").append(String.format(Locale.ROOT, "%.1f", sy + 28)).append("\" fill=\"#ef4444\" font-size=\"10\" font-weight=\"800\" text-anchor=\"middle\">★ Stop ").append(num).append(" (Your Stop - SKIPPED)</text>\n");
            } else if (isHighlighted) {
                sb.append("      <circle cx=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" cy=\"").append(String.format(Locale.ROOT, "%.1f", sy)).append("\" r=\"22\" fill=\"rgba(255, 215, 0, 0.3)\" stroke=\"#ffd700\" stroke-width=\"1.5\" stroke-dasharray=\"3,3\" />\n")
                        .append("      <circle cx=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" cy=\"").append(String.format(Locale.ROOT, "%.1f", sy)).append("\" r=\"15\" fill=\"#ffd700\" stroke=\"#ffffff\" stroke-width=\"2.5\" filter=\"url(#pin-shadow)\" />\n")
                        .append("      <text x=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" y=\"").append(String.format(Locale.ROOT, "%.1f", sy + 4.5)).append("\" fill=\"#000000\" font-size=\"13\" font-weight=\"900\" text-anchor=\"middle\">&#9733;</text>\n")
                        .append("      <text x=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" y=\"").append(String.format(Locale.ROOT, "%.1f", sy + 28)).append("\" fill=\"#ffd700\" font-size=\"10\" font-weight=\"800\" text-anchor=\"middle\">★ Stop ").append(num).append(" (Your Stop - ACTIVE)</text>\n");
            } else if (isActive) {
                sb.append("      <circle cx=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" cy=\"").append(String.format(Locale.ROOT, "%.1f", sy)).append("\" r=\"18\" fill=\"rgba(16, 185, 129, 0.2)\" />\n")
                        .append("      <circle cx=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" cy=\"").append(String.format(Locale.ROOT, "%.1f", sy)).append("\" r=\"13\" fill=\"#10b981\" stroke=\"#ffffff\" stroke-width=\"2\" filter=\"url(#pin-shadow)\" />\n")
                        .append("      <text x=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" y=\"").append(String.format(Locale.ROOT, "%.1f", sy + 4)).append("\" fill=\"#ffffff\" font-size=\"10.5\" font-weight=\"800\" text-anchor=\"middle\">").append(num).append("</text>\n")
                        .append("      <text x=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" y=\"").append(String.format(Locale.ROOT, "%.1f", sy + 26)).append("\" fill=\"").append(labelColor).append("\" font-size=\"9.5\" font-weight=\"600\" text-anchor=\"middle\">Stop ").append(num).append(": ").append(escapeHtml(st.stopName.length() > 14 ? st.stopName.substring(0, 13) + "…" : st.stopName)).append("</text>\n");
            } else if (isSkipped) {
                sb.append("      <circle cx=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" cy=\"").append(String.format(Locale.ROOT, "%.1f", sy)).append("\" r=\"12\" fill=\"#1e293b\" stroke=\"#ef4444\" stroke-width=\"2\" stroke-dasharray=\"3,2\" filter=\"url(#pin-shadow)\" />\n")
                        .append("      <text x=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" y=\"").append(String.format(Locale.ROOT, "%.1f", sy + 4)).append("\" fill=\"#ef4444\" font-size=\"11\" font-weight=\"900\" text-anchor=\"middle\">&#10005;</text>\n")
                        .append("      <text x=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" y=\"").append(String.format(Locale.ROOT, "%.1f", sy + 25)).append("\" fill=\"#ef4444\" font-size=\"9\" font-weight=\"600\" text-anchor=\"middle\">Stop ").append(num).append(" (SKIPPED)</text>\n");
            } else {
                sb.append("      <circle cx=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" cy=\"").append(String.format(Locale.ROOT, "%.1f", sy)).append("\" r=\"11\" fill=\"#1e293b\" stroke=\"#64748b\" stroke-width=\"1.5\" />\n")
                        .append("      <text x=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" y=\"").append(String.format(Locale.ROOT, "%.1f", sy + 3.5)).append("\" fill=\"#94a3b8\" font-size=\"9.5\" font-weight=\"600\" text-anchor=\"middle\">").append(num).append("</text>\n")
                        .append("      <text x=\"").append(String.format(Locale.ROOT, "%.1f", sx)).append("\" y=\"").append(String.format(Locale.ROOT, "%.1f", sy + 24)).append("\" fill=\"#94a3b8\" font-size=\"8.5\" text-anchor=\"middle\">Stop ").append(num).append(" (0 Students)</text>\n");
            }

            sb.append("    </g>\n");
        }

        sb.append("  </svg>\n")
                .append("</div>\n");


        return sb.toString();
    }
}


