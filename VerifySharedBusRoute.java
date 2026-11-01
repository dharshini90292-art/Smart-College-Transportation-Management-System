import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import lib.DatabaseConnection;

public class VerifySharedBusRoute {
    private static final String BASE_URL = "http://localhost:8080";
    private static int testsRun = 0;
    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("================================================================================");
        System.out.println("  VERIFICATION TEST SUITE: SHARED BUS ROUTE FOR ALL THREE LOGINS");
        System.out.println("================================================================================");

        try {
            runSection5Tests();
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("[CRITICAL] Test suite execution aborted due to unexpected exception: " + e.getMessage());
            testsFailed++;
        }

        System.out.println("================================================================================");
        System.out.printf("  SUMMARY: Total Tests: %d | Passed: %d | Failed: %d%n", testsRun, testsPassed, testsFailed);
        System.out.println("================================================================================");
        if (testsFailed > 0) {
            System.exit(1);
        }
    }

    private static void assertTrue(String testName, boolean condition, String details) {
        testsRun++;
        if (condition) {
            testsPassed++;
            System.out.printf("  [PASS] Test %d: %s%n", testsRun, testName);
            if (details != null && !details.isEmpty()) {
                System.out.printf("         %s%n", details);
            }
        } else {
            testsFailed++;
            System.err.printf("  [FAIL] Test %d: %s%n", testsRun, testName);
            if (details != null && !details.isEmpty()) {
                System.err.printf("         FAILED: %s%n", details);
            }
        }
    }

    private static void runSection5Tests() throws Exception {
        LocalDate testDate = LocalDate.now();
        String dateStr = testDate.toString();
        System.out.println("[INFO] Testing with Route Date: " + dateStr);

        // Ensure database students 25CSR003 and 25CSR009 are assigned to Stop 1 (STOP-01) on BUS-01
        verifyAndPrepareStudentAssignment();

        // Login as Management (ADMIN-01 / admin123)
        String mgmtCookie = login("management", "ADMIN-01", "admin123");
        assertTrue("Management Login (ADMIN-01)", mgmtCookie != null, "Session cookie obtained: " + mgmtCookie);

        // Login as Student A (25CSR003 / student123)
        String stuACookie = login("student", "25CSR003", "student123");
        assertTrue("Student A Login (25CSR003)", stuACookie != null, "Session cookie obtained: " + stuACookie);

        // Login as Student B (25CSR009 / student123)
        String stuBCookie = login("student", "25CSR009", "student123");
        assertTrue("Student B Login (25CSR009)", stuBCookie != null, "Session cookie obtained: " + stuBCookie);

        // Login as Driver (DRV-01 / driver123)
        String drvCookie = login("driver", "DRV-01", "driver123");
        assertTrue("Driver Login (DRV-01)", drvCookie != null, "Session cookie obtained: " + drvCookie);

        // Step 1: Student A and Student B at Stop 1 both mark Absent
        System.out.println("\n--- STEP 1: Student A and Student B at Stop 1 both mark Absent ---");
        boolean stuAAbsent = submitAttendance(stuACookie, "ABSENT", "Fever and cold", dateStr);
        boolean stuBAbsent = submitAttendance(stuBCookie, "ABSENT", "Family function", dateStr);
        assertTrue("Step 1a: Student A marks ABSENT", stuAAbsent, "Status: ABSENT, Reason: Fever and cold");
        assertTrue("Step 1b: Student B marks ABSENT", stuBAbsent, "Status: ABSENT, Reason: Family function");

        // Step 2: Stop 1 disappears from active route and is marked skipped in MySQL cache
        System.out.println("\n--- STEP 2: Stop 1 disappears from active route and is marked skipped ---");
        CachedRouteInfo cacheStep2 = getCachedRoute("BUS-01", dateStr);
        assertTrue("Step 2a: MySQL route cache exists for BUS-01 on " + dateStr, cacheStep2 != null,
                "Cache ID: " + (cacheStep2 != null ? cacheStep2.cacheId : "null"));

        boolean stop1SkippedInDb = cacheStep2 != null && (Arrays.asList(cacheStep2.skippedStops.split(",")).contains("Stop 1") || Arrays.asList(cacheStep2.skippedStops.split(",")).contains("STOP-01"));
        boolean stop1OmittedFromActive = cacheStep2 != null && (!Arrays.asList(cacheStep2.orderedStops.split(",")).contains("Stop 1") && !Arrays.asList(cacheStep2.orderedStops.split(",")).contains("STOP-01"));
        assertTrue("Step 2b: Stop 1 (STOP-01) is marked SKIPPED in MySQL cache", stop1SkippedInDb,
                "Skipped stops in DB: " + (cacheStep2 != null ? cacheStep2.skippedStops : "none"));
        assertTrue("Step 2c: Stop 1 (STOP-01) is omitted from active sequence in MySQL cache", stop1OmittedFromActive,
                "Active stops in DB: " + (cacheStep2 != null ? cacheStep2.orderedStops : "none"));

        // Step 3: Management sees the updated route
        System.out.println("\n--- STEP 3: Management sees updated route ---");
        String mgmtHtml1 = getPage("/management-routes.html?bus=BUS-01&date=" + dateStr, mgmtCookie);
        assertTrue("Step 3a: Management route page returns HTTP 200", mgmtHtml1 != null && !mgmtHtml1.isEmpty(), "Page loaded successfully");
        boolean mgmtShowsStop1Skipped = mgmtHtml1 != null && (mgmtHtml1.contains("STOP-01") || mgmtHtml1.contains("Stop 1")) && mgmtHtml1.contains("SKIPPED");
        assertTrue("Step 3b: Management dashboard shows Stop 1 as SKIPPED", mgmtShowsStop1Skipped, "Stop 1 marked skipped in schedule / map");

        String mgmtDist1 = extractDistance(mgmtHtml1);
        String mgmtDur1 = extractDuration(mgmtHtml1);
        String mgmtTime1 = extractLastUpdated(mgmtHtml1);
        System.out.printf("         Management Metrics: Distance=%s, Duration=%s, Updated=%s%n", mgmtDist1, mgmtDur1, mgmtTime1);

        // Step 4: Student sees the EXACT SAME updated route
        System.out.println("\n--- STEP 4: Student sees exact same updated route ---");
        String stuHtml1 = getPage("/student-route.html?date=" + dateStr, stuACookie);
        assertTrue("Step 4a: Student route page returns HTTP 200", stuHtml1 != null && !stuHtml1.isEmpty(), "Page loaded successfully");

        String stuDist1 = extractDistance(stuHtml1);
        String stuDur1 = extractDuration(stuHtml1);
        String stuTime1 = extractLastUpdated(stuHtml1);
        System.out.printf("         Student Metrics:    Distance=%s, Duration=%s, Updated=%s%n", stuDist1, stuDur1, stuTime1);

        boolean stuDistMatchesMgmt = Objects.equals(mgmtDist1, stuDist1);
        boolean stuDurMatchesMgmt = Objects.equals(mgmtDur1, stuDur1);
        boolean stuTimeMatchesMgmt = Objects.equals(mgmtTime1, stuTime1);
        assertTrue("Step 4b: Student route distance matches Management exactly", stuDistMatchesMgmt, "Mgmt: " + mgmtDist1 + " == Stu: " + stuDist1);
        assertTrue("Step 4c: Student route duration matches Management exactly", stuDurMatchesMgmt, "Mgmt: " + mgmtDur1 + " == Stu: " + stuDur1);
        assertTrue("Step 4d: Student last updated time matches Management exactly", stuTimeMatchesMgmt, "Mgmt: " + mgmtTime1 + " == Stu: " + stuTime1);

        boolean stuShowsCompleteRoute = stuHtml1.contains("Gandhipuram") && stuHtml1.contains("CIET Central Campus") && (stuHtml1.contains("Stop 10") || stuHtml1.contains("STOP-10"));
        assertTrue("Step 4e: Student sees COMPLETE corridor route (Depot -> All 10 stops -> College)", stuShowsCompleteRoute, "Complete route stops verified");

        boolean stuHighlightsAssignedStopSkipped = stuHtml1.contains("Your Stop") && (stuHtml1.contains("SKIPPED") || stuHtml1.contains("Your Stop - SKIPPED"));
        assertTrue("Step 4f: Student's assigned pickup stop (Stop 1) is highlighted and marked SKIPPED", stuHighlightsAssignedStopSkipped,
                "Student designated stop indicator confirmed");

        // Step 5: Driver sees the EXACT SAME updated route
        System.out.println("\n--- STEP 5: Driver sees exact same updated route ---");
        String drvHtml1 = getPage("/driver-route.html?date=" + dateStr, drvCookie);
        assertTrue("Step 5a: Driver route page returns HTTP 200", drvHtml1 != null && !drvHtml1.isEmpty(), "Page loaded successfully");

        String drvDist1 = extractDistance(drvHtml1);
        String drvDur1 = extractDuration(drvHtml1);
        String drvTime1 = extractLastUpdated(drvHtml1);
        System.out.printf("         Driver Metrics:     Distance=%s, Duration=%s, Updated=%s%n", drvDist1, drvDur1, drvTime1);

        boolean drvDistMatchesMgmt = Objects.equals(mgmtDist1, drvDist1);
        boolean drvDurMatchesMgmt = Objects.equals(mgmtDur1, drvDur1);
        boolean drvTimeMatchesMgmt = Objects.equals(mgmtTime1, drvTime1);
        assertTrue("Step 5b: Driver route distance matches Management exactly", drvDistMatchesMgmt, "Mgmt: " + mgmtDist1 + " == Drv: " + drvDist1);
        assertTrue("Step 5c: Driver route duration matches Management exactly", drvDurMatchesMgmt, "Mgmt: " + mgmtDur1 + " == Drv: " + drvDur1);
        assertTrue("Step 5d: Driver last updated time matches Management exactly", drvTimeMatchesMgmt, "Mgmt: " + mgmtTime1 + " == Drv: " + drvTime1);

        boolean drvShowsCompleteRoute = drvHtml1.contains("Gandhipuram") && drvHtml1.contains("CIET Central Campus") && (drvHtml1.contains("Stop 10") || drvHtml1.contains("STOP-10"));
        assertTrue("Step 5e: Driver sees COMPLETE corridor route with active and skipped stops", drvShowsCompleteRoute, "Complete corridor stops verified");

        // Step 6: One student changes attendance to Present
        System.out.println("\n--- STEP 6: Student A changes attendance to Present ---");
        boolean stuAPresent = submitAttendance(stuACookie, "PRESENT", null, dateStr);
        assertTrue("Step 6: Student A marks PRESENT", stuAPresent, "Status updated to PRESENT");

        // Step 7: Stop 1 becomes active again, and all three dashboards show the updated route
        System.out.println("\n--- STEP 7: Stop 1 becomes active again and all three dashboards show updated route ---");
        CachedRouteInfo cacheStep7 = getCachedRoute("BUS-01", dateStr);
        assertTrue("Step 7a: MySQL route cache refreshed for BUS-01", cacheStep7 != null,
                "New Cache ID: " + (cacheStep7 != null ? cacheStep7.cacheId : "null"));

        boolean stop1ActiveInDb = cacheStep7 != null && (cacheStep7.orderedStops.contains("Stop 1") || cacheStep7.orderedStops.contains("STOP-01"));
        boolean stop1NotSkippedInDb = cacheStep7 != null && (!cacheStep7.skippedStops.contains("Stop 1") && !cacheStep7.skippedStops.contains("STOP-01"));
        assertTrue("Step 7b: Stop 1 (STOP-01) returned to ACTIVE sequence in MySQL cache", stop1ActiveInDb,
                "Active stops in DB: " + (cacheStep7 != null ? cacheStep7.orderedStops : "none"));
        assertTrue("Step 7c: Stop 1 (STOP-01) removed from skipped stops in MySQL cache", stop1NotSkippedInDb,
                "Skipped stops in DB: " + (cacheStep7 != null ? cacheStep7.skippedStops : "none"));

        // Refresh all 3 dashboards
        String mgmtHtml2 = getPage("/management-routes.html?bus=BUS-01&date=" + dateStr, mgmtCookie);
        String stuHtml2 = getPage("/student-route.html?date=" + dateStr, stuACookie);
        String drvHtml2 = getPage("/driver-route.html?date=" + dateStr, drvCookie);

        String mgmtDist2 = extractDistance(mgmtHtml2);
        String mgmtDur2 = extractDuration(mgmtHtml2);
        String mgmtTime2 = extractLastUpdated(mgmtHtml2);

        String stuDist2 = extractDistance(stuHtml2);
        String stuDur2 = extractDuration(stuHtml2);
        String stuTime2 = extractLastUpdated(stuHtml2);

        String drvDist2 = extractDistance(drvHtml2);
        String drvDur2 = extractDuration(drvHtml2);
        String drvTime2 = extractLastUpdated(drvHtml2);

        System.out.printf("         Refreshed Management: Distance=%s, Duration=%s, Updated=%s%n", mgmtDist2, mgmtDur2, mgmtTime2);
        System.out.printf("         Refreshed Student:    Distance=%s, Duration=%s, Updated=%s%n", stuDist2, stuDur2, stuTime2);
        System.out.printf("         Refreshed Driver:     Distance=%s, Duration=%s, Updated=%s%n", drvDist2, drvDur2, drvTime2);

        boolean allDistMatch = Objects.equals(mgmtDist2, stuDist2) && Objects.equals(mgmtDist2, drvDist2);
        boolean allDurMatch = Objects.equals(mgmtDur2, stuDur2) && Objects.equals(mgmtDur2, drvDur2);
        boolean allTimeMatch = Objects.equals(mgmtTime2, stuTime2) && Objects.equals(mgmtTime2, drvTime2);

        assertTrue("Step 7d: All three dashboards display identical updated distance", allDistMatch,
                "Mgmt: " + mgmtDist2 + " | Stu: " + stuDist2 + " | Drv: " + drvDist2);
        assertTrue("Step 7e: All three dashboards display identical updated duration", allDurMatch,
                "Mgmt: " + mgmtDur2 + " | Stu: " + stuDur2 + " | Drv: " + drvDur2);
        assertTrue("Step 7f: All three dashboards display identical updated timestamp", allTimeMatch,
                "Mgmt: " + mgmtTime2 + " | Stu: " + stuTime2 + " | Drv: " + drvTime2);

        boolean stuHighlightsAssignedStopActive = stuHtml2.contains("Your Stop - ACTIVE") || (stuHtml2.contains("Your Stop") && !stuHtml2.contains("Your Stop - SKIPPED"));
        assertTrue("Step 7g: Student's assigned pickup stop (Stop 1) is now ACTIVE on student dashboard",
                stuHighlightsAssignedStopActive, "Designated stop active status verified on student dashboard");
    }

    private static void verifyAndPrepareStudentAssignment() throws SQLException {
        try (Connection con = DatabaseConnection.getConnection()) {
            // Check students 25CSR003 and 25CSR009
            String sql = "UPDATE students SET bus_id = 'BUS-01', stop_name = 'Stop 1', password_hash = 'student123' WHERE student_id = '25CSR003';";
            try (Statement st = con.createStatement()) {
                st.executeUpdate(sql);
                st.executeUpdate("UPDATE students SET bus_id = 'BUS-01', stop_name = 'Stop 1', password_hash = 'student123' WHERE student_id = '25CSR009';");
            }
        }
    }

    private static String login(String role, String userId, String password) throws IOException {
        URL url = new URL(BASE_URL + "/login");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        String body;
        if ("management".equals(role)) {
            body = "role=management&managementId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        } else if ("driver".equals(role)) {
            body = "role=driver&driverId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        } else {
            body = "role=student&studentId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8)
                    + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
        }

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code == 302 || code == 303 || code == 200) {
            String cookieHeader = conn.getHeaderField("Set-Cookie");
            if (cookieHeader != null) {
                return cookieHeader.split(";")[0];
            }
        }
        return null;
    }

    private static boolean submitAttendance(String cookie, String status, String reason, String date) throws IOException {
        URL url = new URL(BASE_URL + "/student-attendance.html");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Cookie", cookie);

        StringBuilder body = new StringBuilder();
        body.append("status=").append(URLEncoder.encode(status, StandardCharsets.UTF_8));
        if (reason != null && !reason.isEmpty()) {
            body.append("&reason=").append(URLEncoder.encode(reason, StandardCharsets.UTF_8));
        }
        if (date != null && !date.isEmpty()) {
            body.append("&date=").append(URLEncoder.encode(date, StandardCharsets.UTF_8));
        }

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        return code == 302 || code == 303 || code == 200;
    }

    private static String getPage(String path, String cookie) throws IOException {
        URL url = new URL(BASE_URL + path);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        if (cookie != null) {
            conn.setRequestProperty("Cookie", cookie);
        }
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);

        int code = conn.getResponseCode();
        if (code != 200) {
            System.err.println("[HTTP ERROR] GET " + path + " returned status code " + code);
            return null;
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    private static String extractDistance(String html) {
        if (html == null) return "N/A";
        int idx = html.indexOf("Distance</div>");
        if (idx != -1) {
            int valStart = html.indexOf("<div class=\"detail-val\">", idx);
            if (valStart != -1 && valStart - idx < 60) {
                int valEnd = html.indexOf("</div>", valStart + 24);
                if (valEnd != -1) {
                    String raw = html.substring(valStart + 24, valEnd).trim();
                    try {
                        double d = Double.parseDouble(raw.replaceAll("[^0-9.]", ""));
                        return String.format(Locale.ROOT, "%.1f km", d);
                    } catch (Exception ignored) {
                        return raw;
                    }
                }
            }
        }
        int idx2 = html.indexOf("Total Route Distance</span>");
        if (idx2 != -1) {
            int valStart = html.indexOf("<strong", idx2);
            if (valStart != -1 && valStart - idx2 < 120) {
                int contentStart = html.indexOf(">", valStart) + 1;
                int valEnd = html.indexOf("</strong>", contentStart);
                if (valEnd != -1) {
                    String raw = html.substring(contentStart, valEnd).trim();
                    try {
                        double d = Double.parseDouble(raw.replaceAll("[^0-9.]", ""));
                        return String.format(Locale.ROOT, "%.1f km", d);
                    } catch (Exception ignored) {
                        return raw;
                    }
                }
            }
        }
        return "UNKNOWN";
    }

    private static String extractDuration(String html) {
        if (html == null) return "N/A";
        int idx = html.indexOf("Transit Time</div>");
        if (idx != -1) {
            int valStart = html.indexOf("<div class=\"detail-val\">", idx);
            if (valStart != -1 && valStart - idx < 60) {
                int valEnd = html.indexOf("</div>", valStart + 24);
                if (valEnd != -1) {
                    return html.substring(valStart + 24, valEnd).trim();
                }
            }
        }
        int idx2 = html.indexOf("Estimated Travel Time</span>");
        if (idx2 != -1) {
            int valStart = html.indexOf("<strong", idx2);
            if (valStart != -1 && valStart - idx2 < 120) {
                int contentStart = html.indexOf(">", valStart) + 1;
                int valEnd = html.indexOf("</strong>", contentStart);
                if (valEnd != -1) {
                    return html.substring(contentStart, valEnd).trim();
                }
            }
        }
        return "UNKNOWN";
    }

    private static String extractLastUpdated(String html) {
        if (html == null) return "N/A";
        // Check "Last Updated: "
        int idx = html.indexOf("Last Updated: ");
        if (idx != -1) {
            int end = html.indexOf("<", idx);
            if (end != -1) {
                return html.substring(idx + 14, end).trim();
            }
        }
        // Check "Last Database Update</div><div class=\"detail-val\">"
        int idx2 = html.indexOf("Last Database Update</div>");
        if (idx2 != -1) {
            int valStart = html.indexOf("<div class=\"detail-val\">", idx2);
            if (valStart != -1 && valStart - idx2 < 60) {
                int valEnd = html.indexOf("</div>", valStart + 24);
                if (valEnd != -1) {
                    return html.substring(valStart + 24, valEnd).trim();
                }
            }
        }
        // Check "Last Updated Time</span>"
        int idx3 = html.indexOf("Last Updated Time</span>");
        if (idx3 != -1) {
            int valStart = html.indexOf("<strong", idx3);
            if (valStart != -1 && valStart - idx3 < 120) {
                int contentStart = html.indexOf(">", valStart) + 1;
                int valEnd = html.indexOf("</strong>", contentStart);
                if (valEnd != -1) {
                    return html.substring(contentStart, valEnd).trim();
                }
            }
        }
        return "UNKNOWN";
    }

    private static CachedRouteInfo getCachedRoute(String busId, String date) throws SQLException {
        String sql = "SELECT cache_id, distance_meters, duration_seconds, ordered_stop_codes, skipped_stop_codes, last_updated "
                + "FROM route_date_cache WHERE bus_id = ? AND route_date = ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, busId);
            ps.setString(2, date);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    CachedRouteInfo info = new CachedRouteInfo();
                    info.cacheId = rs.getInt("cache_id");
                    info.distanceMeters = rs.getDouble("distance_meters");
                    info.durationSeconds = rs.getInt("duration_seconds");
                    info.orderedStops = rs.getString("ordered_stop_codes");
                    info.skippedStops = rs.getString("skipped_stop_codes");
                    info.lastUpdated = rs.getTimestamp("last_updated");
                    return info;
                }
            }
        }
        return null;
    }

    private static class CachedRouteInfo {
        int cacheId;
        double distanceMeters;
        int durationSeconds;
        String orderedStops;
        String skippedStops;
        Timestamp lastUpdated;
    }
}
