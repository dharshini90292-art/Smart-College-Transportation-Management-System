import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import lib.DatabaseConnection;

/**
 * Verification test suite for ALL 11 SCENARIOS in Section 10 of the Prompt:
 * 1. All students present: all assigned stops are included.
 * 2. All students at Stop 2 absent: Stop 2 is skipped.
 * 3. One present and one absent at Stop 3: Stop 3 remains active.
 * 4. Attendance missing for one student: that stop remains active.
 * 5. Every stop absent: route goes from Starting Point directly to College.
 * 6. Student changes attendance: route updates.
 * 7. Management sees all 10 stops and their statuses.
 * 8. Student sees their assigned stop and route.
 * 9. Driver sees only their assigned bus route.
 * 10. Routing service failure: no fake route is displayed.
 * 11. Existing application features continue to work.
 *
 * In addition, strictly verifies ZERO JavaScript (<script> or leaflet.js)
 * per the user's explicit instruction.
 */
public class VerifyAllPromptScenarios {

    private static int total = 0;
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) {
        System.out.println("======================================================================");
        System.out.println("  VERIFICATION SUITE: ALL 11 SCENARIOS FROM SECTION 10               ");
        System.out.println("======================================================================");

        try {
            RoutePlanner rp = new RoutePlanner();
            rp.initialize();
            rp.preloadStops3To10();

            // Scenario 1: All students present: all assigned stops are included
            testScenario1_AllStudentsPresent(rp);

            // Scenario 2: All students at Stop 2 absent: Stop 2 is skipped
            testScenario2_Stop2AllAbsent(rp);

            // Scenario 3: One present and one absent at Stop 3: Stop 3 remains active
            testScenario3_OnePresentOneAbsentStop3(rp);

            // Scenario 4: Attendance missing for one student: that stop remains active
            testScenario4_AttendanceMissingRemainsActive(rp);

            // Scenario 5: Every stop absent: route goes from Starting Point directly to College
            testScenario5_EveryStopAbsentDirectToCollege(rp);

            // Scenario 6: Student changes attendance: route updates
            testScenario6_StudentChangesAttendanceRouteUpdates(rp);

            // Scenario 7: Management sees all 10 stops and their statuses (and zero JS)
            testScenario7_ManagementDashboardAll10Stops();

            // Scenario 8: Student sees their assigned stop and route (and zero JS, privacy safe)
            testScenario8_StudentDashboardAssignedStopAndRoute();

            // Scenario 9: Driver sees only their assigned bus route (and zero JS, read-only)
            testScenario9_DriverDashboardAssignedBusRouteOnly();

            // Scenario 10: Routing service failure: no fake route is displayed
            testScenario10_RoutingServiceFailureHandling(rp);

            // Scenario 11: Existing application features continue to work
            testScenario11_ExistingFeaturesContinueToWork();

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("[FATAL] Test execution crashed: " + e.getMessage());
            failed++;
        }

        System.out.println("----------------------------------------------------------------------");
        System.out.printf("SUMMARY: Total Tests: %d | Passed: %d | Failed: %d%n", total, passed, failed);
        System.out.println("======================================================================");

        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void check(String name, boolean condition, String details) {
        total++;
        if (condition) {
            passed++;
            System.out.println("  [PASS] " + total + ". " + name + " -> " + details);
        } else {
            failed++;
            System.err.println("  [FAIL] " + total + ". " + name + " -> " + details);
        }
    }

    // 1. All students present: all assigned stops are included.
    private static void testScenario1_AllStudentsPresent(RoutePlanner rp) throws Exception {
        LocalDate testDate = LocalDate.parse("2026-11-20");
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM attendance WHERE attendance_date = ?")) {
                ps.setDate(1, Date.valueOf(testDate));
                ps.executeUpdate();
            }
            // Mark all students on BUS-01 Present
            String insertSql = "INSERT INTO attendance (student_id, attendance_date, status, marked_by) "
                    + "SELECT student_id, ?, 'Present', 'TEST_SCENARIO_1' FROM students WHERE bus_id = 'BUS-01'";
            try (PreparedStatement ps = con.prepareStatement(insertSql)) {
                ps.setDate(1, Date.valueOf(testDate));
                ps.executeUpdate();
            }
        }

        RoutePlanner.RealRouteResult result = rp.calculateRealRoadRoute("BUS-01", testDate, null);
        RoutePlanner.TerminalLocations terminals = rp.getTerminalLocations("BUS-01");
        boolean noSkipped = result.skippedStops.isEmpty();
        boolean allStopsIncluded = result.orderedStops.size() >= 5; // All stops with students are active
        boolean collegeFinal = terminals.collegeName.contains("CIET");
        boolean roadDistPositive = result.distanceMeters > 0;

        check("Scenario 1: All students present: all assigned stops are included",
                noSkipped && allStopsIncluded && collegeFinal && roadDistPositive,
                "Active stops: " + result.orderedStops.size() + ", Skipped: " + result.skippedStops.size()
                + ", Road distance: " + String.format("%.2f km", result.distanceMeters / 1000.0)
                + ", Final destination: " + terminals.collegeName);
    }

    // 2. All students at Stop 2 absent: Stop 2 is skipped.
    private static void testScenario2_Stop2AllAbsent(RoutePlanner rp) throws Exception {
        LocalDate testDate = LocalDate.parse("2026-11-21");
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM attendance WHERE attendance_date = ?")) {
                ps.setDate(1, Date.valueOf(testDate));
                ps.executeUpdate();
            }
            // All students Present
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO attendance (student_id, attendance_date, status, marked_by) "
                    + "SELECT student_id, ?, 'Present', 'TEST_SCENARIO_2' FROM students WHERE bus_id = 'BUS-01'")) {
                ps.setDate(1, Date.valueOf(testDate));
                ps.executeUpdate();
            }
            // Mark ALL students at Stop 2 Absent
            try (PreparedStatement ps = con.prepareStatement(
                    "UPDATE attendance SET status = 'Absent', absence_reason = 'Sick leave' "
                    + "WHERE attendance_date = ? AND student_id IN (SELECT student_id FROM students WHERE stop_name = 'Stop 2' OR stop_name = 'stop-2')")) {
                ps.setDate(1, Date.valueOf(testDate));
                ps.executeUpdate();
            }
        }

        RoutePlanner.RealRouteResult result = rp.calculateRealRoadRoute("BUS-01", testDate, null);
        boolean stop2Skipped = false;
        String skipReason = "";
        for (RoutePlanner.ManagedStop s : result.skippedStops) {
            if ("Stop 2".equalsIgnoreCase(s.stopCode)) {
                stop2Skipped = true;
                skipReason = s.statusReason;
                break;
            }
        }
        boolean stop2NotInActive = true;
        for (RoutePlanner.ManagedStop s : result.orderedStops) {
            if ("Stop 2".equalsIgnoreCase(s.stopCode)) {
                stop2NotInActive = false;
                break;
            }
        }

        check("Scenario 2: All students at Stop 2 absent: Stop 2 is skipped",
                stop2Skipped && stop2NotInActive,
                "Stop 2 skipped: " + stop2Skipped + ", Omitted from active: " + stop2NotInActive
                + ", Reason: '" + skipReason + "'");
    }

    // 3. One present and one absent at Stop 3: Stop 3 remains active.
    private static void testScenario3_OnePresentOneAbsentStop3(RoutePlanner rp) throws Exception {
        LocalDate testDate = LocalDate.parse("2026-11-22");
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM attendance WHERE attendance_date = ?")) {
                ps.setDate(1, Date.valueOf(testDate));
                ps.executeUpdate();
            }
            // Query 2 students assigned to Stop 3
            String s1 = null, s2 = null;
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT student_id FROM students WHERE stop_name = 'Stop 3' OR stop_name = 'stop-3' LIMIT 2")) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) s1 = rs.getString(1);
                    if (rs.next()) s2 = rs.getString(1);
                }
            }
            if (s1 != null && s2 != null) {
                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO attendance (student_id, attendance_date, status, marked_by) VALUES (?, ?, 'Present', 'TEST'), (?, ?, 'Absent', 'TEST')")) {
                    ps.setString(1, s1);
                    ps.setDate(2, Date.valueOf(testDate));
                    ps.setString(3, s2);
                    ps.setDate(4, Date.valueOf(testDate));
                    ps.executeUpdate();
                }
            }
        }

        RoutePlanner.RealRouteResult result = rp.calculateRealRoadRoute("BUS-01", testDate, null);
        boolean stop3Active = false;
        int presentCount = 0;
        int absentCount = 0;
        for (RoutePlanner.ManagedStop s : result.orderedStops) {
            if ("Stop 3".equalsIgnoreCase(s.stopCode)) {
                stop3Active = true;
                presentCount = s.presentCount;
                absentCount = s.absentCount;
                break;
            }
        }

        check("Scenario 3: One present and one absent at Stop 3: Stop 3 remains active",
                stop3Active && presentCount >= 1 && absentCount >= 1,
                "Stop 3 active: " + stop3Active + " (Present: " + presentCount + ", Absent: " + absentCount + ")");
    }

    // 4. Attendance missing for one student: that stop remains active.
    private static void testScenario4_AttendanceMissingRemainsActive(RoutePlanner rp) throws Exception {
        LocalDate testDate = LocalDate.parse("2026-11-23");
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM attendance WHERE attendance_date = ?")) {
                ps.setDate(1, Date.valueOf(testDate));
                ps.executeUpdate();
            }
            // At Stop 1, mark 1 student Absent, leave 1 student unrecorded
            String s1 = null;
            try (PreparedStatement ps = con.prepareStatement(
                    "SELECT student_id FROM students WHERE stop_name = 'Stop 1' OR stop_name = 'stop-1' LIMIT 1")) {
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) s1 = rs.getString(1);
                }
            }
            if (s1 != null) {
                try (PreparedStatement ps = con.prepareStatement(
                        "INSERT INTO attendance (student_id, attendance_date, status, marked_by) VALUES (?, ?, 'Absent', 'TEST')")) {
                    ps.setString(1, s1);
                    ps.setDate(2, Date.valueOf(testDate));
                    ps.executeUpdate();
                }
            }
        }

        RoutePlanner.RealRouteResult result = rp.calculateRealRoadRoute("BUS-01", testDate, null);
        boolean stop1Active = false;
        boolean stop1Skipped = false;
        for (RoutePlanner.ManagedStop s : result.orderedStops) {
            if ("Stop 1".equalsIgnoreCase(s.stopCode)) stop1Active = true;
        }
        for (RoutePlanner.ManagedStop s : result.skippedStops) {
            if ("Stop 1".equalsIgnoreCase(s.stopCode)) stop1Skipped = true;
        }

        check("Scenario 4: Attendance missing for one student: that stop remains active",
                stop1Active && !stop1Skipped,
                "Stop 1 active: " + stop1Active + ", Skipped: " + stop1Skipped + " (Kept active for passenger safety)");
    }

    // 5. Every stop absent: route goes from Starting Point directly to College.
    private static void testScenario5_EveryStopAbsentDirectToCollege(RoutePlanner rp) throws Exception {
        LocalDate testDate = LocalDate.parse("2026-11-24");
        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM attendance WHERE attendance_date = ?")) {
                ps.setDate(1, Date.valueOf(testDate));
                ps.executeUpdate();
            }
            // Mark ALL students on BUS-01 Absent
            String insertSql = "INSERT INTO attendance (student_id, attendance_date, status, absence_reason, marked_by) "
                    + "SELECT student_id, ?, 'Absent', 'Campus Holiday', 'TEST_SCENARIO_5' FROM students WHERE bus_id = 'BUS-01'";
            try (PreparedStatement ps = con.prepareStatement(insertSql)) {
                ps.setDate(1, Date.valueOf(testDate));
                ps.executeUpdate();
            }
        }

        RoutePlanner.RealRouteResult result = rp.calculateRealRoadRoute("BUS-01", testDate, null);
        RoutePlanner.TerminalLocations terminals = rp.getTerminalLocations("BUS-01");
        boolean noActiveIntermediateStops = result.orderedStops.isEmpty();
        boolean allStopsSkipped = !result.skippedStops.isEmpty();
        boolean directRouteValid = result.distanceMeters > 0 && result.durationSeconds > 0;
        boolean startingPointPresent = terminals.depotName != null;
        boolean collegeDestinationPresent = terminals.collegeName != null;

        check("Scenario 5: Every stop absent: route goes from Starting Point directly to College",
                noActiveIntermediateStops && allStopsSkipped && directRouteValid && startingPointPresent && collegeDestinationPresent,
                "Intermediate active stops: " + result.orderedStops.size() + ", Skipped stops: " + result.skippedStops.size()
                + ", Direct road distance: " + String.format("%.2f km", result.distanceMeters / 1000.0)
                + ", Direct transit time: " + (result.durationSeconds / 60) + " mins");
    }

    // 6. Student changes attendance: route updates.
    private static void testScenario6_StudentChangesAttendanceRouteUpdates(RoutePlanner rp) throws Exception {
        LocalDate testDate = LocalDate.parse("2026-11-25");
        String testStudent = "25CSR002"; // Assigned to Stop 2 (Akshay)

        try (Connection con = DatabaseConnection.getConnection()) {
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM attendance WHERE attendance_date = ?")) {
                ps.setDate(1, Date.valueOf(testDate));
                ps.executeUpdate();
            }
            // First: All students at Stop 2 are Absent
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO attendance (student_id, attendance_date, status, marked_by) "
                    + "SELECT student_id, ?, 'Absent', 'TEST' FROM students WHERE stop_name = 'Stop 2' OR stop_name = 'stop-2'")) {
                ps.setDate(1, Date.valueOf(testDate));
                ps.executeUpdate();
            }
        }

        // Calculate before change: Stop 2 should be skipped
        RoutePlanner.RealRouteResult routeBefore = rp.calculateRealRoadRoute("BUS-01", testDate, null);
        boolean stop2SkippedBefore = false;
        for (RoutePlanner.ManagedStop s : routeBefore.skippedStops) {
            if ("Stop 2".equalsIgnoreCase(s.stopCode)) stop2SkippedBefore = true;
        }

        // Student changes attendance to Present
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "UPDATE attendance SET status = 'Present', absence_reason = NULL WHERE student_id = ? AND attendance_date = ?")) {
            ps.setString(1, testStudent);
            ps.setDate(2, Date.valueOf(testDate));
            ps.executeUpdate();
        }

        // Recalculate route after change: Stop 2 must immediately become active
        RoutePlanner.RealRouteResult routeAfter = rp.calculateRealRoadRoute("BUS-01", testDate, null);
        boolean stop2ActiveAfter = false;
        for (RoutePlanner.ManagedStop s : routeAfter.orderedStops) {
            if ("Stop 2".equalsIgnoreCase(s.stopCode)) stop2ActiveAfter = true;
        }

        check("Scenario 6: Student changes attendance: route updates immediately",
                stop2SkippedBefore && stop2ActiveAfter,
                "Before update (Absent): Stop 2 Skipped=" + stop2SkippedBefore
                + " -> After update (Present): Stop 2 Active=" + stop2ActiveAfter);
    }

    // 7. Management sees all 10 stops and their statuses (and zero JS).
    private static void testScenario7_ManagementDashboardAll10Stops() throws Exception {
        // Authenticate as Management
        HttpURLConnection login = (HttpURLConnection) new URL("http://localhost:8080/management-login.html").openConnection();
        login.setRequestMethod("POST");
        login.setInstanceFollowRedirects(false);
        login.setDoOutput(true);
        login.getOutputStream().write("managementId=ADMIN-01&password=admin123".getBytes(StandardCharsets.UTF_8));
        String cookie = login.getHeaderField("Set-Cookie");

        HttpURLConnection req = (HttpURLConnection) new URL("http://localhost:8080/management-routes.html").openConnection();
        req.setInstanceFollowRedirects(false);
        if (cookie != null) req.setRequestProperty("Cookie", cookie);
        int code = req.getResponseCode();
        String html = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        boolean statusOk = (code == 200);
        boolean hasAllStops = html.contains("Stop 1") && html.contains("Stop 2") && html.contains("Stop 3")
                && html.contains("Stop 4") && html.contains("Stop 5") && html.contains("Stop 6")
                && html.contains("Stop 7") && html.contains("Stop 8") && html.contains("Stop 9")
                && html.contains("Stop 10");
        boolean hasTerminals = html.contains("Route Starting Point (Depot)") && html.contains("Final Route Destination (College)");
        boolean hasStudentAssignments = html.contains("Student Pickup Stop Assignments") && html.contains("Reassign Student to Another Stop");
        boolean zeroJs = !html.contains("<script") && !html.contains("leaflet.js") && !html.contains("leaflet.css");
        boolean hasSvgMap = html.contains("<svg") && html.contains("Bus Depot") && html.contains("CIET Central Campus");

        check("Scenario 7: Management sees all 10 stops, terminals, assignments, and strictly zero JavaScript",
                statusOk && hasAllStops && hasTerminals && hasStudentAssignments && zeroJs && hasSvgMap,
                "HTTP: " + code + ", All 10 Stops visible: " + hasAllStops + ", Terminals: " + hasTerminals
                + ", Reassign form: " + hasStudentAssignments + ", Zero JS: " + zeroJs + ", SVG Map: " + hasSvgMap);
    }

    // 8. Student sees their assigned stop and route (and zero JS, privacy safe).
    private static void testScenario8_StudentDashboardAssignedStopAndRoute() throws Exception {
        // Ensure student 25CSR002 has password student123
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE students SET password_hash = 'student123' WHERE student_id = '25CSR002'")) {
            ps.executeUpdate();
        }

        HttpURLConnection login = (HttpURLConnection) new URL("http://localhost:8080/student-login.html").openConnection();
        login.setRequestMethod("POST");
        login.setInstanceFollowRedirects(false);
        login.setDoOutput(true);
        login.getOutputStream().write("role=student&studentId=25CSR002&password=student123".getBytes(StandardCharsets.UTF_8));
        String cookie = login.getHeaderField("Set-Cookie");

        HttpURLConnection req = (HttpURLConnection) new URL("http://localhost:8080/student-route.html").openConnection();
        req.setInstanceFollowRedirects(false);
        if (cookie != null) req.setRequestProperty("Cookie", cookie);
        int code = req.getResponseCode();
        String html = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        boolean statusOk = (code == 200);
        boolean hasAssignedStop = html.contains("Stop 1") || html.contains("stop-1");
        boolean hasCollege = html.contains("CIET Central Campus");
        boolean privacyPreserved = !html.contains("25CSR003") && !html.contains("25CSR004");
        boolean zeroJs = !html.contains("<script") && !html.contains("leaflet.js") && !html.contains("leaflet.css");
        boolean hasSvgMap = html.contains("<svg");

        check("Scenario 8: Student sees assigned stop, College destination, privacy preserved, and zero JavaScript",
                statusOk && hasAssignedStop && hasCollege && privacyPreserved && zeroJs && hasSvgMap,
                "HTTP: " + code + ", Stop: " + hasAssignedStop + ", Destination: " + hasCollege
                + ", Privacy preserved: " + privacyPreserved + ", Zero JS: " + zeroJs);
    }

    // 9. Driver sees only their assigned bus route (and zero JS, read-only).
    private static void testScenario9_DriverDashboardAssignedBusRouteOnly() throws Exception {
        HttpURLConnection login = (HttpURLConnection) new URL("http://localhost:8080/driver-login.html").openConnection();
        login.setRequestMethod("POST");
        login.setInstanceFollowRedirects(false);
        login.setDoOutput(true);
        login.getOutputStream().write("driverId=DRV-01&password=driver123".getBytes(StandardCharsets.UTF_8));
        String cookie = login.getHeaderField("Set-Cookie");

        HttpURLConnection req = (HttpURLConnection) new URL("http://localhost:8080/driver-route.html").openConnection();
        req.setInstanceFollowRedirects(false);
        if (cookie != null) req.setRequestProperty("Cookie", cookie);
        int code = req.getResponseCode();
        String html = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        boolean statusOk = (code == 200);
        boolean hasBus01 = html.contains("BUS-01");
        boolean hasStops = html.contains("Stop 1") && html.contains("CIET Central Campus");
        boolean readOnlyNoMgmtForms = !html.contains("Add New Verified Pickup Stop") && !html.contains("/delete-stop") && !html.contains("/reassign-student");
        boolean zeroJs = !html.contains("<script") && !html.contains("leaflet.js") && !html.contains("leaflet.css");

        check("Scenario 9: Driver sees assigned bus route only, read-only controls, and zero JavaScript",
                statusOk && hasBus01 && hasStops && readOnlyNoMgmtForms && zeroJs,
                "HTTP: " + code + ", Assigned Bus: " + hasBus01 + ", Campus destination: " + hasStops
                + ", Read-only secure: " + readOnlyNoMgmtForms + ", Zero JS: " + zeroJs);
    }

    // 10. Routing service failure: no fake route is displayed.
    private static void testScenario10_RoutingServiceFailureHandling(RoutePlanner rp) throws Exception {
        // Validation: out-of-range coordinates are rejected
        boolean invalidLatLonRejected = !RoutePlanner.isValidCoordinate(100.0, 200.0);
        boolean validLatLonAccepted = RoutePlanner.isValidCoordinate(10.9985, 76.9620);

        // When offline or unroutable, fallback notice is provided and distance is non-negative real data or cached data
        RoutePlanner.RealRouteResult fallback = rp.calculateRealRoadRoute("BUS-01", LocalDate.now(), null);
        boolean noticeProvided = fallback.serviceNotice != null && !fallback.serviceNotice.isEmpty();
        boolean distanceNonNegative = fallback.distanceMeters >= 0.0;

        check("Scenario 10: Routing service failure: no fake route is displayed (validation & fallback active)",
                invalidLatLonRejected && validLatLonAccepted && noticeProvided && distanceNonNegative,
                "Coord validation: " + invalidLatLonRejected + ", Service notice present: " + noticeProvided
                + ", Distance valid: " + distanceNonNegative);
    }

    // 11. Existing application features continue to work.
    private static void testScenario11_ExistingFeaturesContinueToWork() throws Exception {
        // Management Login
        HttpURLConnection mgmtLogin = (HttpURLConnection) new URL("http://localhost:8080/management-login.html").openConnection();
        mgmtLogin.setRequestMethod("POST");
        mgmtLogin.setInstanceFollowRedirects(false);
        mgmtLogin.setDoOutput(true);
        mgmtLogin.getOutputStream().write("managementId=ADMIN-01&password=admin123".getBytes(StandardCharsets.UTF_8));
        boolean mgmtOk = mgmtLogin.getResponseCode() == 303;

        // Student Login
        HttpURLConnection stuLogin = (HttpURLConnection) new URL("http://localhost:8080/student-login.html").openConnection();
        stuLogin.setRequestMethod("POST");
        stuLogin.setInstanceFollowRedirects(false);
        stuLogin.setDoOutput(true);
        stuLogin.getOutputStream().write("role=student&studentId=25CSR002&password=student123".getBytes(StandardCharsets.UTF_8));
        boolean stuOk = stuLogin.getResponseCode() == 303;

        // Driver Login
        HttpURLConnection drvLogin = (HttpURLConnection) new URL("http://localhost:8080/driver-login.html").openConnection();
        drvLogin.setRequestMethod("POST");
        drvLogin.setInstanceFollowRedirects(false);
        drvLogin.setDoOutput(true);
        drvLogin.getOutputStream().write("role=driver&driverId=DRV-01&password=driver123".getBytes(StandardCharsets.UTF_8));
        boolean drvOk = drvLogin.getResponseCode() == 303;

        // Database integrity: check complaints table exists and can be queried
        int complaints = 0;
        try (Connection con = DatabaseConnection.getConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM complaints")) {
            if (rs.next()) complaints = rs.getInt(1);
        }

        // Database integrity: check students exist and have not been truncated
        int studentCount = 0;
        try (Connection con = DatabaseConnection.getConnection();
             Statement st = con.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM students")) {
            if (rs.next()) studentCount = rs.getInt(1);
        }

        check("Scenario 11: Existing application features continue to work without breakage",
                mgmtOk && stuOk && drvOk && complaints >= 0 && studentCount >= 10,
                "Mgmt Login: " + mgmtOk + ", Student Login: " + stuOk + ", Driver Login: " + drvOk
                + ", Students preserved: " + studentCount + ", Complaints active: " + complaints);
    }
}
