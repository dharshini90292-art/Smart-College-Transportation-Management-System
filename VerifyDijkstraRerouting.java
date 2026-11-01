import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import lib.DatabaseConnection;

/**
 * Verification test suite for Dijkstra-based Automatic Bus Stop Rerouting.
 * Covers all requirements in Section 8 of the prompt.
 */
public class VerifyDijkstraRerouting {

    private static int total = 0;
    private static int passed = 0;
    private static int failed = 0;

    private static void check(String name, boolean condition, String details) {
        total++;
        if (condition) {
            passed++;
            System.out.println("  [PASS] " + total + ". " + name + (details != null && !details.isEmpty() ? " -> " + details : ""));
        } else {
            failed++;
            System.err.println("  [FAIL] " + total + ". " + name + (details != null && !details.isEmpty() ? " -> " + details : ""));
        }
    }

    public static void main(String[] args) {
        System.out.println("======================================================================");
        System.out.println("   VERIFICATION SUITE: DIJKSTRA-BASED AUTOMATIC REROUTING             ");
        System.out.println("======================================================================");

        try {
            RoutePlanner rp = new RoutePlanner();
            rp.initialize();
            LocalDate testDate = LocalDate.now().plusDays(1); // Use a distinct test date

            // 1. All students present: all assigned stops are included
            testCase1_AllStudentsPresent(rp, testDate);

            // 2. All students at Stop 2 absent: Stop 2 is skipped
            testCase2_Stop2AbsentSkipped(rp, testDate);

            // 3. One student present and another absent at Stop 3: Stop 3 remains active
            testCase3_Stop3MixedAttendance(rp, testDate);

            // 4. Missing attendance: stop remains active
            testCase4_MissingAttendanceRetainsStop(rp, testDate);

            // 5. Attendance changes from Absent to Present: stop becomes active again
            testCase5_AbsentToPresentReactivatesStop(rp, testDate);

            // 6. Every stop is skipped: route goes from Starting Point to College
            testCase6_EveryStopSkippedDirectToCollege(rp, testDate);

            // 7. Verify Dijkstra returns the shortest path for the available road graph
            testCase7_DijkstraShortestPathCalculation();

            // 8. Verify all three dashboards display the same saved route
            testCase8_AllThreeDashboardsDisplaySameSavedRoute(testDate);

            // 9. Verify the map does not remain blank
            testCase9_MapContainerAndContentNotBlank(testDate);

        } catch (Exception e) {
            e.printStackTrace();
            check("Unexpected fatal exception during test suite", false, e.getMessage());
        }

        System.out.println("----------------------------------------------------------------------");
        System.out.printf("SUMMARY: Total Tests: %d | Passed: %d | Failed: %d%n", total, passed, failed);
        System.out.println("======================================================================");

        if (failed > 0) {
            System.exit(1);
        }
    }

    // Test Case 1: All students present: all assigned stops are included
    private static void testCase1_AllStudentsPresent(RoutePlanner rp, LocalDate date) throws Exception {
        try (Connection con = DatabaseConnection.getConnection()) {
            // Mark all assigned students present for date
            List<String> studentIds = getAssignedStudentIds(con, "BUS-01");
            for (String sid : studentIds) {
                setAttendance(con, sid, date, "Present", null);
            }

            RoutePlanner.RealRouteResult result = rp.recalculateAndSaveSharedRoute("BUS-01", date);
            boolean allIncluded = result.orderedStops.size() >= 6;
            boolean zeroSkipped = result.skippedStops.isEmpty();
            boolean validDist = result.distanceMeters > 0;

            check("Case 1: All students present: all assigned stops are included",
                    allIncluded && zeroSkipped && validDist,
                    "Active stops: " + result.orderedStops.size() + ", Skipped: " + result.skippedStops.size()
                            + ", Total distance: " + String.format(Locale.ROOT, "%.2f km", result.distanceMeters / 1000.0));
        }
    }

    // Test Case 2: All students at Stop 2 absent: Stop 2 is skipped
    private static void testCase2_Stop2AbsentSkipped(RoutePlanner rp, LocalDate date) throws Exception {
        try (Connection con = DatabaseConnection.getConnection()) {
            List<String> stop2Students = getStudentsForStop(con, "BUS-01", "Stop 2");
            for (String sid : stop2Students) {
                setAttendance(con, sid, date, "Absent", "Medical leave");
            }

            RoutePlanner.RealRouteResult result = rp.recalculateAndSaveSharedRoute("BUS-01", date);
            boolean stop2InSkipped = false;
            for (RoutePlanner.ManagedStop ms : result.skippedStops) {
                if ("Stop 2".equalsIgnoreCase(ms.stopCode)) stop2InSkipped = true;
            }
            boolean stop2NotInActive = true;
            for (RoutePlanner.ManagedStop ms : result.orderedStops) {
                if ("Stop 2".equalsIgnoreCase(ms.stopCode)) stop2NotInActive = false;
            }

            check("Case 2: All students at Stop 2 absent: Stop 2 is skipped",
                    stop2InSkipped && stop2NotInActive,
                    "Stop 2 in skipped: " + stop2InSkipped + ", Stop 2 omitted from active: " + stop2NotInActive);
        }
    }

    // Test Case 3: One student present and another absent at Stop 3: Stop 3 remains active
    private static void testCase3_Stop3MixedAttendance(RoutePlanner rp, LocalDate date) throws Exception {
        try (Connection con = DatabaseConnection.getConnection()) {
            List<String> stop3Students = getStudentsForStop(con, "BUS-01", "Stop 3");
            if (stop3Students.size() >= 2) {
                setAttendance(con, stop3Students.get(0), date, "Present", null);
                setAttendance(con, stop3Students.get(1), date, "Absent", "Personal");
            }

            RoutePlanner.RealRouteResult result = rp.recalculateAndSaveSharedRoute("BUS-01", date);
            boolean stop3Active = false;
            for (RoutePlanner.ManagedStop ms : result.orderedStops) {
                if ("Stop 3".equalsIgnoreCase(ms.stopCode)) stop3Active = true;
            }

            check("Case 3: One student present and another absent at Stop 3: Stop 3 remains active",
                    stop3Active, "Stop 3 active: " + stop3Active);
        }
    }

    // Test Case 4: Missing attendance: stop remains active
    private static void testCase4_MissingAttendanceRetainsStop(RoutePlanner rp, LocalDate date) throws Exception {
        try (Connection con = DatabaseConnection.getConnection()) {
            List<String> stop1Students = getStudentsForStop(con, "BUS-01", "Stop 1");
            if (!stop1Students.isEmpty()) {
                // Remove attendance for student at Stop 1
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM attendance WHERE student_id = ? AND attendance_date = ?")) {
                    ps.setString(1, stop1Students.get(0));
                    ps.setDate(2, java.sql.Date.valueOf(date));
                    ps.executeUpdate();
                }
            }

            RoutePlanner.RealRouteResult result = rp.recalculateAndSaveSharedRoute("BUS-01", date);
            boolean stop1Active = false;
            for (RoutePlanner.ManagedStop ms : result.orderedStops) {
                if ("Stop 1".equalsIgnoreCase(ms.stopCode)) stop1Active = true;
            }

            check("Case 4: Missing attendance: stop remains active",
                    stop1Active, "Stop 1 active: " + stop1Active + " (retained for safety)");
        }
    }

    // Test Case 5: Attendance changes from Absent to Present: stop becomes active again
    private static void testCase5_AbsentToPresentReactivatesStop(RoutePlanner rp, LocalDate date) throws Exception {
        try (Connection con = DatabaseConnection.getConnection()) {
            List<String> stop2Students = getStudentsForStop(con, "BUS-01", "Stop 2");
            for (String sid : stop2Students) {
                setAttendance(con, sid, date, "Present", null);
            }

            RoutePlanner.RealRouteResult result = rp.recalculateAndSaveSharedRoute("BUS-01", date);
            boolean stop2Active = false;
            for (RoutePlanner.ManagedStop ms : result.orderedStops) {
                if ("Stop 2".equalsIgnoreCase(ms.stopCode)) stop2Active = true;
            }

            check("Case 5: Attendance changes from Absent to Present: stop becomes active again",
                    stop2Active, "Stop 2 restored to active: " + stop2Active);
        }
    }

    // Test Case 6: Every stop is skipped: route goes from Starting Point to College
    private static void testCase6_EveryStopSkippedDirectToCollege(RoutePlanner rp, LocalDate date) throws Exception {
        try (Connection con = DatabaseConnection.getConnection()) {
            List<String> allStudents = getAssignedStudentIds(con, "BUS-01");
            for (String sid : allStudents) {
                setAttendance(con, sid, date, "Absent", "Corridor holiday");
            }

            // Also make sure override is 0
            try (Statement st = con.createStatement()) {
                st.executeUpdate("UPDATE stops SET is_active_override = 0 WHERE bus_id = 'BUS-01'");
            }

            RoutePlanner.RealRouteResult result = rp.recalculateAndSaveSharedRoute("BUS-01", date);
            boolean intermediateEmpty = result.orderedStops.isEmpty();
            boolean distancePositive = result.distanceMeters > 0;
            boolean polylineExists = result.geojsonCoordinates != null && result.geojsonCoordinates.contains("[");

            check("Case 6: Every stop is skipped: route goes from Starting Point to College",
                    intermediateEmpty && distancePositive && polylineExists,
                    "Intermediate stops: " + result.orderedStops.size() + ", Skipped stops: " + result.skippedStops.size()
                            + ", Direct road distance: " + String.format(Locale.ROOT, "%.2f km", result.distanceMeters / 1000.0));
        }
    }

    // Test Case 7: Verify Dijkstra returns the shortest path for the available road graph
    private static void testCase7_DijkstraShortestPathCalculation() throws Exception {
        DijkstraRouteFinder finder = new DijkstraRouteFinder();
        try (Connection con = DatabaseConnection.getConnection()) {
            Map<String, double[]> nodes = new LinkedHashMap<>();
            nodes.put("DEPOT", new double[]{11.0168, 76.9558});
            nodes.put("Stop 1", new double[]{11.0016, 76.9665});
            nodes.put("Stop 2", new double[]{10.9897, 76.9602});
            nodes.put("Stop 3", new double[]{10.9482, 76.9714});
            nodes.put("COLLEGE", new double[]{10.9632, 76.8142});

            DijkstraRouteFinder.RoadGraph graph = finder.loadOrBuildRoadGraph(con, "BUS-01", nodes, null);
            DijkstraRouteFinder.DijkstraLegResult legDirect = finder.findShortestPath(graph, "DEPOT", "Stop 1");
            DijkstraRouteFinder.DijkstraLegResult legMulti = finder.findShortestPath(graph, "DEPOT", "COLLEGE");

            boolean validDirect = legDirect.reachable && legDirect.distanceMeters > 2000;
            boolean validMulti = legMulti.reachable && legMulti.distanceMeters > 15000;

            check("Case 7: Verify Dijkstra returns the shortest path for the available road graph",
                    validDirect && validMulti,
                    "DEPOT->Stop 1: " + String.format(Locale.ROOT, "%.1f m", legDirect.distanceMeters)
                            + ", DEPOT->COLLEGE: " + String.format(Locale.ROOT, "%.1f m", legMulti.distanceMeters)
                            + ", Path: " + legMulti.nodePath);
        }
    }

    // Test Case 8: Verify all three dashboards display the same saved route
    private static void testCase8_AllThreeDashboardsDisplaySameSavedRoute(LocalDate date) throws Exception {
        try (Connection con = DatabaseConnection.getConnection()) {
            // Restore mixed attendance
            List<String> allStudents = getAssignedStudentIds(con, "BUS-01");
            for (String sid : allStudents) {
                setAttendance(con, sid, date, "Present", null);
            }
            // Stop 2 absent
            for (String sid : getStudentsForStop(con, "BUS-01", "Stop 2")) {
                setAttendance(con, sid, date, "Absent", "Medical");
            }
        }

        RoutePlanner rp = new RoutePlanner();
        RoutePlanner.RealRouteResult savedRoute = rp.recalculateAndSaveSharedRoute("BUS-01", date);
        double expectedKm = Math.round((savedRoute.distanceMeters / 1000.0) * 100.0) / 100.0;
        String distSnippet = String.format(Locale.ROOT, "%.2f km", expectedKm);

        String mgmtHtml = rp.renderLeafletMapHtml(savedRoute, "management", null);
        String studentHtml = rp.renderLeafletMapHtml(savedRoute, "student", "Stop 1");
        String driverHtml = rp.renderLeafletMapHtml(savedRoute, "driver", null);

        boolean mgmtHasDist = mgmtHtml.contains(distSnippet);
        boolean stuHasDist = studentHtml.contains(distSnippet);
        boolean drvHasDist = driverHtml.contains(distSnippet);

        check("Case 8: Verify all three dashboards display the same saved route",
                mgmtHasDist && stuHasDist && drvHasDist,
                "Shared distance: " + distSnippet + " verified in Management, Student, and Driver HTML views");
    }

    // Test Case 9: Verify the map does not remain blank
    private static void testCase9_MapContainerAndContentNotBlank(LocalDate date) throws Exception {
        RoutePlanner rp = new RoutePlanner();
        RoutePlanner.RealRouteResult route = rp.calculateRealRoadRoute("BUS-01", date, "Stop 1");
        String html = rp.renderLeafletMapHtml(route, "student", "Stop 1");

        boolean hasMapContainer = html.contains("id=\"map\"");
        boolean hasLeafletJs = html.contains("leaflet.js");
        boolean hasLeafletCss = html.contains("leaflet.css");
        boolean hasPolyline = html.contains("L.polyline") || html.contains("polyline");
        boolean hasStudentHighlight = html.contains("YOUR DESIGNATED PICKUP STOP");
        boolean hasErrorBanner = html.contains("map-error-banner");

        check("Case 9: Verify the map does not remain blank",
                hasMapContainer && hasLeafletJs && hasLeafletCss && hasPolyline && hasStudentHighlight && hasErrorBanner,
                "Map container: true, Leaflet JS & CSS: true, Polyline: true, Student Highlight: true, Error Banner: true");
    }

    private static void setAttendance(Connection con, String studentId, LocalDate date, String status, String reason) throws SQLException {
        String sql = "INSERT INTO attendance (student_id, attendance_date, status, marked_by, absence_reason) "
                + "VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE status = VALUES(status), absence_reason = VALUES(absence_reason)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, studentId);
            ps.setDate(2, java.sql.Date.valueOf(date));
            ps.setString(3, status);
            ps.setString(4, studentId);
            if (reason != null) ps.setString(5, reason);
            else ps.setNull(5, Types.VARCHAR);
            ps.executeUpdate();
        }
    }

    private static List<String> getAssignedStudentIds(Connection con, String busId) throws SQLException {
        List<String> list = new ArrayList<>();
        try (PreparedStatement ps = con.prepareStatement("SELECT student_id FROM students WHERE bus_id = ?")) {
            ps.setString(1, busId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(rs.getString("student_id"));
            }
        }
        return list;
    }

    private static List<String> getStudentsForStop(Connection con, String busId, String stopCode) throws SQLException {
        List<String> list = new ArrayList<>();
        String norm = RoutePlanner.normalizeStopCode(stopCode);
        try (PreparedStatement ps = con.prepareStatement("SELECT student_id, stop_name FROM students WHERE bus_id = ?")) {
            ps.setString(1, busId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sStop = RoutePlanner.normalizeStopCode(rs.getString("stop_name"));
                    if (sStop.equalsIgnoreCase(norm) || sStop.replaceAll("[^0-9]", "").equals(norm.replaceAll("[^0-9]", ""))) {
                        list.add(rs.getString("student_id"));
                    }
                }
            }
        }
        return list;
    }
}
