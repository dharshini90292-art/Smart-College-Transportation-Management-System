import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;
import com.sun.net.httpserver.HttpServer;
import lib.DatabaseConnection;

public class VerifyAllRequirementsSection5 {

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
        System.out.println("   SECTION 5 VERIFICATION SUITE: 9 REQUIRED ACCEPTANCE CRITERIA       ");
        System.out.println("======================================================================");

        HttpServer testServer = null;
        int port = 8080;
        try {
            // Setup server for in-process testing
            Main.AttendanceStore attendanceStore = new Main.AttendanceStore(
                    new File("data/attendance.properties").toPath()
            );

            // Bind to 8080 (or fallback if already occupied)
            try {
                testServer = HttpServer.create(new InetSocketAddress(port), 0);
            } catch (BindException be) {
                // If 8080 is already bound by Main, we can test against localhost:8080 directly
                System.out.println("[INFO] Port 8080 is already active; connecting to existing instance.");
            }

            if (testServer != null) {
                testServer.createContext("/", new Main.StaticFileHandler(attendanceStore));
                testServer.setExecutor(null);
                testServer.start();
                System.out.println("[INFO] Test HTTP Server started on port " + port);
            }

            // 1. Student login works with correct student ID and password
            testPoint1_StudentLoginSuccess(port);

            // 2. Incorrect password displays clear error
            testPoint2_IncorrectPasswordDisplaysClearError(port);

            // 3. Eye icon shows and hides entered password (pure HTML/CSS)
            testPoint3_EyeIconHtmlCssImplementation();

            // 4. Resetting student password changes only that student's password
            testPoint4_ResetStudentPasswordChangesOnlyThatStudent(port);

            // 5. Resetting student password does not change management password
            testPoint5_ResetStudentPasswordDoesNotChangeManagementPassword(port);

            // 6. Reset flow rejects unauthorized requests
            testPoint6_ResetFlowRejectsUnauthorizedRequests(port);

            // 7. Demo content removal: breakdown reports, CMP-6, College Fleet, complaints CMP 1-6
            testPoint7_DemoContentRemoved();

            // 8. Real database records and unrelated features remain intact
            testPoint8_RealRecordsAndFeaturesIntact();

            // 9. Project compiles and runs on existing port
            testPoint9_CompilationAndServerRun(port);

        } catch (Exception e) {
            e.printStackTrace();
            check("Fatal exception during test suite", false, e.getMessage());
        } finally {
            if (testServer != null) {
                testServer.stop(0);
                System.out.println("[INFO] Test HTTP Server stopped.");
            }
        }

        System.out.println("----------------------------------------------------------------------");
        System.out.printf("SUMMARY: Total Tests: %d | Passed: %d | Failed: %d%n", total, passed, failed);
        System.out.println("======================================================================");

        if (failed > 0) {
            System.exit(1);
        }
    }

    // Point 1: Student login works with correct student ID and password
    private static void testPoint1_StudentLoginSuccess(int port) throws Exception {
        String studentId = "25CSR002";
        String password = "StudentLoginPass#2026";

        // Set student password to known PBKDF2 hash for deterministic verification
        String knownHash = hashPasswordHelper(password);
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("UPDATE students SET password_hash = ? WHERE student_id = ?")) {
            ps.setString(1, knownHash);
            ps.setString(2, studentId);
            ps.executeUpdate();
        }

        // Perform HTTP POST to /login
        String body = "role=student&studentId=" + URLEncoder.encode(studentId, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);

        HttpURLConnection conn = sendPost("http://localhost:" + port + "/login", body);
        int code = conn.getResponseCode();
        String location = conn.getHeaderField("Location");
        String cookie = conn.getHeaderField("Set-Cookie");

        boolean success = (code == 302 || code == 303)
                && location != null && location.contains("student-dashboard.html")
                && cookie != null && cookie.contains("ciet_session=");

        check("Point 1: Student login works with correct ID and password", success,
                "HTTP Response Code: " + code + ", Redirect: " + location + ", Session Cookie set: " + (cookie != null));
    }

    // Point 2: An incorrect password displays a clear error
    private static void testPoint2_IncorrectPasswordDisplaysClearError(int port) throws Exception {
        String studentId = "25CSR002";
        String wrongPassword = "WrongPassword#999";

        String body = "role=student&studentId=" + URLEncoder.encode(studentId, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(wrongPassword, StandardCharsets.UTF_8);

        HttpURLConnection conn = sendPost("http://localhost:" + port + "/login", body);
        int code = conn.getResponseCode();
        String responseContent = readStream(conn.getErrorStream() != null ? conn.getErrorStream() : conn.getInputStream());

        boolean has401 = (code == 401);
        boolean hasErrorMessage = responseContent.contains("Incorrect ID or password. Please verify your credentials and try again.");
        boolean hasErrorClass = responseContent.contains("registration-message registration-error");

        // Also test GET /student-login.html?error=invalid-credentials
        HttpURLConnection getConn = (HttpURLConnection) new URL("http://localhost:" + port + "/student-login.html?error=invalid-credentials").openConnection();
        String getContent = readStream(getConn.getInputStream());
        boolean getDisplaysError = getContent.contains("Incorrect ID or password. Please verify your credentials and try again.");

        check("Point 2: Incorrect password displays clear error message",
                has401 && hasErrorMessage && hasErrorClass && getDisplaysError,
                "Status: " + code + ", HTML contains formatted error alert box and instructions");
    }

    // Point 3: The eye icon shows and hides the entered password (pure HTML/CSS)
    private static void testPoint3_EyeIconHtmlCssImplementation() throws Exception {
        String[] files = {"student-login.html", "driver-login.html", "management-login.html", "reset-password.html"};
        boolean allFilesHaveToggle = true;
        for (String f : files) {
            String content = Files.readString(new File(f).toPath(), StandardCharsets.UTF_8);
            boolean hasCheckbox = content.contains("class=\"password-toggle-checkbox\"");
            boolean hasInput = content.contains("class=\"form-control password-toggle-input\"") || content.contains("password-toggle-input");
            boolean hasLabel = content.contains("class=\"password-toggle-label\"");
            boolean hasOpenEye = content.contains("eye-icon-open");
            boolean hasClosedEye = content.contains("eye-icon-closed");
            if (!hasCheckbox || !hasInput || !hasLabel || !hasOpenEye || !hasClosedEye) {
                allFilesHaveToggle = false;
                System.err.println("[FAIL] Missing eye icon markup in " + f);
            }
        }

        String css = Files.readString(new File("style.css").toPath(), StandardCharsets.UTF_8);
        boolean hasMaskRule = css.contains("-webkit-text-security: disc;");
        boolean hasUnmaskRule = css.contains("-webkit-text-security: none;");
        boolean hasEyeClosedToggle = css.contains(".password-toggle-checkbox:not(:checked) ~ .password-toggle-label .eye-icon-closed");
        boolean hasEyeOpenToggle = css.contains(".password-toggle-checkbox:checked ~ .password-toggle-label .eye-icon-open");

        check("Point 3: Eye icon markup and CSS pure HTML/CSS password toggle present on all forms",
                allFilesHaveToggle && hasMaskRule && hasUnmaskRule && hasEyeClosedToggle && hasEyeOpenToggle,
                "Password field wrapped in .password-input-wrapper with pure CSS -webkit-text-security toggle");
    }

    // Point 4: Resetting a student password changes only that student's password
    private static void testPoint4_ResetStudentPasswordChangesOnlyThatStudent(int port) throws Exception {
        String targetStudentId = "25CSR002";
        String siblingStudentId = "25CSR003";
        String siblingStudentId2 = "25CSR004";
        String newPassword = "StudentNewSecure#2026";

        String originalSiblingHash1 = getStudentPasswordHash(siblingStudentId);
        String originalSiblingHash2 = getStudentPasswordHash(siblingStudentId2);

        // Perform password reset via HTTP POST to /reset-password using student identity verification
        String body = "role=student&userId=" + URLEncoder.encode(targetStudentId, StandardCharsets.UTF_8)
                + "&studentName=" + URLEncoder.encode("Akshay", StandardCharsets.UTF_8)
                + "&newPassword=" + URLEncoder.encode(newPassword, StandardCharsets.UTF_8)
                + "&confirmPassword=" + URLEncoder.encode(newPassword, StandardCharsets.UTF_8);

        HttpURLConnection conn = sendPost("http://localhost:" + port + "/reset-password", body);
        int code = conn.getResponseCode();
        String location = conn.getHeaderField("Location");

        boolean resetRedirectOk = (code == 302 || code == 303)
                && location != null && location.contains("student-login.html?reset=success");

        // Verify target student's hash changed and verifies new password
        String updatedTargetHash = getStudentPasswordHash(targetStudentId);
        boolean targetUpdated = updatedTargetHash != null && updatedTargetHash.startsWith("PBKDF2$210000$");

        // Verify sibling students' password hashes are 100% UNCHANGED
        String currentSiblingHash1 = getStudentPasswordHash(siblingStudentId);
        String currentSiblingHash2 = getStudentPasswordHash(siblingStudentId2);
        boolean siblingsUntouched = Objects.equals(originalSiblingHash1, currentSiblingHash1)
                && Objects.equals(originalSiblingHash2, currentSiblingHash2);

        // Verify target student can log in with new password
        String loginBody = "role=student&studentId=" + URLEncoder.encode(targetStudentId, StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(newPassword, StandardCharsets.UTF_8);
        HttpURLConnection loginConn = sendPost("http://localhost:" + port + "/login", loginBody);
        int loginCode = loginConn.getResponseCode();
        boolean loginSuccess = (loginCode == 302 || loginCode == 303);

        check("Point 4: Resetting student password updates ONLY target student and leaves other students untouched",
                resetRedirectOk && targetUpdated && siblingsUntouched && loginSuccess,
                "Target " + targetStudentId + " updated & authenticated; siblings " + siblingStudentId + ", " + siblingStudentId2 + " untouched");
    }

    // Point 5: Resetting a student password does not change the management password
    private static void testPoint5_ResetStudentPasswordDoesNotChangeManagementPassword(int port) throws Exception {
        String adminId = "ADMIN-01";
        String originalAdminHash = getManagementPasswordHash(adminId);

        // Perform another student reset for student 25CSR003
        String body = "role=student&userId=25CSR003"
                + "&studentName=" + URLEncoder.encode("Aarthi", StandardCharsets.UTF_8)
                + "&newPassword=" + URLEncoder.encode("AnotherPass#9999", StandardCharsets.UTF_8)
                + "&confirmPassword=" + URLEncoder.encode("AnotherPass#9999", StandardCharsets.UTF_8);

        HttpURLConnection conn = sendPost("http://localhost:" + port + "/reset-password", body);
        int code = conn.getResponseCode();

        // Check management table hash
        String currentAdminHash = getManagementPasswordHash(adminId);
        boolean adminUntouched = Objects.equals(originalAdminHash, currentAdminHash);

        // Check management can still authenticate with admin123
        String adminLoginBody = "role=management&managementId=ADMIN-01&password=admin123";
        HttpURLConnection adminConn = sendPost("http://localhost:" + port + "/login", adminLoginBody);
        int adminCode = adminConn.getResponseCode();
        boolean adminLoginSuccess = (adminCode == 302 || adminCode == 303);

        check("Point 5: Resetting student password does NOT change management password",
                (code == 302 || code == 303) && adminUntouched && adminLoginSuccess,
                "Management password hash unchanged (" + currentAdminHash + "); ADMIN-01 login with admin123 succeeds");
    }

    // Point 6: The reset flow rejects unauthorized or unverified requests
    private static void testPoint6_ResetFlowRejectsUnauthorizedRequests(int port) throws Exception {
        String studentId = "25CSR002";

        // 1. Incorrect student verification details (wrong student name)
        String wrongVerificationBody = "role=student&userId=" + URLEncoder.encode(studentId, StandardCharsets.UTF_8)
                + "&studentName=WrongName"
                + "&newPassword=HackerAttempt#123&confirmPassword=HackerAttempt#123";
        HttpURLConnection conn1 = sendPost("http://localhost:" + port + "/reset-password", wrongVerificationBody);
        String loc1 = conn1.getHeaderField("Location");
        boolean rejectsWrongVerification = loc1 != null && loc1.contains("error=verification-failed");

        // 2. Missing student verification details entirely
        String missingVerificationBody = "role=student&userId=" + URLEncoder.encode(studentId, StandardCharsets.UTF_8)
                + "&newPassword=HackerAttempt#123&confirmPassword=HackerAttempt#123";
        HttpURLConnection conn2 = sendPost("http://localhost:" + port + "/reset-password", missingVerificationBody);
        String loc2 = conn2.getHeaderField("Location");
        boolean rejectsMissingVerification = loc2 != null && loc2.contains("error=verification-failed");

        // 3. Non-existent Student ID
        String nonExistentBody = "role=student&userId=NON_EXISTENT_STUDENT_999"
                + "&studentName=FakeStudent"
                + "&newPassword=ValidPass#12345&confirmPassword=ValidPass#12345";
        HttpURLConnection conn3 = sendPost("http://localhost:" + port + "/reset-password", nonExistentBody);
        String loc3 = conn3.getHeaderField("Location");
        boolean rejectsNonExistent = loc3 != null && loc3.contains("error=user-not-found");

        // 4. Mismatched passwords
        String mismatchBody = "role=student&userId=" + URLEncoder.encode(studentId, StandardCharsets.UTF_8)
                + "&studentName=Akshay"
                + "&newPassword=PasswordOne#123&confirmPassword=PasswordDifferent#999";
        HttpURLConnection conn4 = sendPost("http://localhost:" + port + "/reset-password", mismatchBody);
        String loc4 = conn4.getHeaderField("Location");
        boolean rejectsMismatch = loc4 != null && loc4.contains("error=mismatch");

        // 5. Driver reset with wrong driver verification details
        String driverWrongVerificationBody = "role=driver&userId=DRV-01"
                + "&driverName=Ravi Shankar&phoneNumber=0000000000"
                + "&newPassword=DriverPass#12345&confirmPassword=DriverPass#12345";
        HttpURLConnection conn5 = sendPost("http://localhost:" + port + "/reset-password", driverWrongVerificationBody);
        String loc5 = conn5.getHeaderField("Location");
        boolean driverRejectsWrongDetails = loc5 != null && loc5.contains("error=verification-failed");

        // 6. Driver reset with missing driver verification details
        String driverMissingDetailsBody = "role=driver&userId=DRV-01"
                + "&newPassword=DriverPass#12345&confirmPassword=DriverPass#12345";
        HttpURLConnection conn6 = sendPost("http://localhost:" + port + "/reset-password", driverMissingDetailsBody);
        String loc6 = conn6.getHeaderField("Location");
        boolean driverRejectsMissingDetails = loc6 != null && loc6.contains("error=verification-failed");

        check("Point 6: Reset flow safely rejects unverified student and driver resets and unauthorized requests",
                rejectsWrongVerification && rejectsMissingVerification && rejectsNonExistent && rejectsMismatch && driverRejectsWrongDetails && driverRejectsMissingDetails,
                "Wrong student details: " + rejectsWrongVerification + ", Missing student details: " + rejectsMissingVerification
                        + ", Non-existent user: " + rejectsNonExistent + ", Mismatch: " + rejectsMismatch + ", Driver wrong details: " + driverRejectsWrongDetails + ", Driver missing: " + driverRejectsMissingDetails);
    }

    // Point 7: Unwanted demo content removed from displayed pages and active database
    private static void testPoint7_DemoContentRemoved() throws Exception {
        // A. Breakdown reports HTML check
        String driverBreakdown = Files.readString(new File("driver-breakdown.html").toPath(), StandardCharsets.UTF_8);
        String mgmtBreakdown = Files.readString(new File("management-breakdown.html").toPath(), StandardCharsets.UTF_8);
        boolean noDemoBrkInHtml = !driverBreakdown.contains("BRK-3") && !driverBreakdown.contains("BRK-2") && !driverBreakdown.contains("BRK-1")
                && !mgmtBreakdown.contains("BRK-3") && !mgmtBreakdown.contains("BRK-2") && !mgmtBreakdown.contains("BRK-1");

        // B. Complaints CMP-1 through CMP-6
        String studentDash = Files.readString(new File("student-dashboard.html").toPath(), StandardCharsets.UTF_8);
        boolean noDemoCmpInStudentDash = !studentDash.contains("CMP-1024") && !studentDash.contains("CMP-0987")
                && !studentDash.contains("Bus Timing Query");

        // C. College Fleet sample table
        String mgmtBuses = Files.readString(new File("management-buses.html").toPath(), StandardCharsets.UTF_8);
        String mgmtDash = Files.readString(new File("management-dashboard.html").toPath(), StandardCharsets.UTF_8);
        boolean noDemoFleetTable = !mgmtBuses.contains("<h2 class=\"info-card-title\">College Fleet</h2>")
                && !mgmtDash.contains("<table class=\"data-table\" id=\"mgmt-buses\">");

        // D. Database active table vs backup table verification
        int activeComplaints = 0;
        int backupComplaints = 0;
        int activeBreakdowns = 0;
        int backupBreakdowns = 0;

        try (Connection con = DatabaseConnection.getConnection();
             Statement st = con.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM complaints WHERE complaint_id IN (1,2,3,4,5,6) OR subject LIKE '%Bus Timing Query%'")) {
                if (rs.next()) activeComplaints = rs.getInt(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM complaints_backup_demo")) {
                if (rs.next()) backupComplaints = rs.getInt(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM breakdown_reports WHERE report_id IN (1,2,3)")) {
                if (rs.next()) activeBreakdowns = rs.getInt(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM breakdown_reports_backup_demo")) {
                if (rs.next()) backupBreakdowns = rs.getInt(1);
            }
        }

        boolean dbClean = (activeComplaints == 0 && backupComplaints == 6 && activeBreakdowns == 0 && backupBreakdowns == 3);

        check("Point 7: Unwanted demo content cleanly removed from pages and preserved in backup tables",
                noDemoBrkInHtml && noDemoCmpInStudentDash && noDemoFleetTable && dbClean,
                "HTML demo free; Active CMP-1..6 = 0 (backup = 6); Active BRK-1..3 = 0 (backup = 3)");
    }

    // Point 8: Real database records and unrelated features remain intact
    private static void testPoint8_RealRecordsAndFeaturesIntact() throws Exception {
        int studentCount = 0;
        int driverCount = 0;
        int busCount = 0;
        int attendanceCount = 0;
        int mgmtCount = 0;

        try (Connection con = DatabaseConnection.getConnection();
             Statement st = con.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM students")) {
                if (rs.next()) studentCount = rs.getInt(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM drivers")) {
                if (rs.next()) driverCount = rs.getInt(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM buses")) {
                if (rs.next()) busCount = rs.getInt(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM attendance")) {
                if (rs.next()) attendanceCount = rs.getInt(1);
            }
            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM management")) {
                if (rs.next()) mgmtCount = rs.getInt(1);
            }
        }

        // Check Dijkstra route calculation
        List<RoutePlanner.ConfiguredStopInfo> stops = RoutePlanner.getAllConfiguredStops();
        boolean routePlannerIntact = stops.size() == 30;

        boolean recordsIntact = (studentCount >= 3 && driverCount == 2 && busCount == 5 && attendanceCount >= 4 && mgmtCount >= 1);

        check("Point 8: Real database records (Students, Drivers, Buses, Attendance, Management) and Route Planner intact",
                recordsIntact && routePlannerIntact,
                "Students: " + studentCount + ", Drivers: " + driverCount + ", Buses: " + busCount
                        + ", Attendance: " + attendanceCount + ", Stops: " + stops.size());
    }

    // Point 9: Home page has no reset link; login pages have role-specific links; student reset has no management auth screen
    private static void testPoint9_CompilationAndServerRun(int port) throws Exception {
        // 1. Home page must NOT have a Forgot Password link
        HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + port + "/index.html").openConnection();
        int code = conn.getResponseCode();
        String html = readStream(conn.getInputStream());
        boolean serverRunning = (code == 200);
        boolean hasWelcome = html.contains("Welcome to Smart Transport Management");
        boolean homeNoForgotLink = !html.contains("Forgot Password") && !html.contains("reset-password");

        // 2. Each login page must have its role-specific forgot password link
        String studentLoginHtml = Files.readString(new File("student-login.html").toPath(), StandardCharsets.UTF_8);
        String driverLoginHtml = Files.readString(new File("driver-login.html").toPath(), StandardCharsets.UTF_8);
        String mgmtLoginHtml = Files.readString(new File("management-login.html").toPath(), StandardCharsets.UTF_8);

        boolean studentHasLink = studentLoginHtml.contains("reset-password.html?role=student");
        boolean driverHasLink = driverLoginHtml.contains("reset-password.html?role=driver");
        boolean mgmtHasLink = mgmtLoginHtml.contains("reset-password.html?role=management");

        // 3. Student reset screen checks
        HttpURLConnection resetConn = (HttpURLConnection) new URL("http://localhost:" + port + "/reset-password.html?role=student").openConnection();
        String resetHtml = readStream(resetConn.getInputStream());
        boolean studentNoMgmtAuth = !resetHtml.contains("Management Authorization Required")
                && !resetHtml.contains("To protect student accounts from unauthorized resets")
                && !resetHtml.contains("name=\"adminId\"")
                && !resetHtml.contains("name=\"adminPassword\"");
        boolean studentNoPickupStop = !resetHtml.contains("Assigned Pickup Stop") && !resetHtml.contains("name=\"pickupStop\"");
        boolean studentNoAssistance = !resetHtml.contains("Need assistance") && !resetHtml.contains("admin@ciet.edu");
        boolean hasStudentVerification = resetHtml.contains("Student Identity Verification") && resetHtml.contains("name=\"studentName\"");

        // 4. Driver reset screen checks
        HttpURLConnection driverResetConn = (HttpURLConnection) new URL("http://localhost:" + port + "/reset-password.html?role=driver").openConnection();
        String driverResetHtml = readStream(driverResetConn.getInputStream());
        boolean driverNoMgmtAuthHeading = !driverResetHtml.contains("Management Authorization Required");
        boolean driverNoMgmtAuthMsg = !driverResetHtml.contains("To protect driver staff accounts from unauthorized resets, this reset must be authorized by transport management credentials.");
        boolean driverNoAdminIdField = !driverResetHtml.contains("Management Admin ID") && !driverResetHtml.contains("name=\"adminId\"");
        boolean driverNoAdminPassField = !driverResetHtml.contains("Management Admin Password") && !driverResetHtml.contains("name=\"adminPassword\"");
        boolean driverNoCurrentMgmtHeading = !driverResetHtml.contains("Current Management Password Verification");
        boolean driverNoCurrentMgmtMsg = !driverResetHtml.contains("Please verify your current management credentials to authenticate and update your master password.");
        boolean driverNoCurrentPassField = !driverResetHtml.contains("name=\"currentPassword\"");
        boolean driverNoAssistance = !driverResetHtml.contains("Need assistance") && !driverResetHtml.contains("admin@ciet.edu");
        boolean driverHasDriverVerification = driverResetHtml.contains("Driver Staff Verification")
                && driverResetHtml.contains("name=\"driverName\"")
                && driverResetHtml.contains("name=\"phoneNumber\"");

        // 5. Management reset screen retains master password verification
        HttpURLConnection mgmtResetConn = (HttpURLConnection) new URL("http://localhost:" + port + "/reset-password.html?role=management").openConnection();
        String mgmtResetHtml = readStream(mgmtResetConn.getInputStream());
        boolean mgmtHasCurrentVerification = mgmtResetHtml.contains("Current Management Password Verification")
                && mgmtResetHtml.contains("name=\"currentPassword\"");

        boolean allCorrect = serverRunning && hasWelcome && homeNoForgotLink
                && studentHasLink && driverHasLink && mgmtHasLink
                && studentNoMgmtAuth && studentNoPickupStop && studentNoAssistance && hasStudentVerification
                && driverNoMgmtAuthHeading && driverNoMgmtAuthMsg && driverNoAdminIdField && driverNoAdminPassField
                && driverNoCurrentMgmtHeading && driverNoCurrentMgmtMsg && driverNoCurrentPassField && driverNoAssistance
                && driverHasDriverVerification && mgmtHasCurrentVerification;

        check("Point 9: Home page clean, login pages have role-specific links, driver and student reset screens fully cleaned, management reset independent",
                allCorrect,
                "Home clean: " + homeNoForgotLink + ", Student clean (no pickup stop / no assistance): " + (studentNoPickupStop && studentNoAssistance)
                        + ", Driver clean (no mgmt auth / no current mgmt pass / no assistance): " + (driverNoMgmtAuthHeading && driverNoCurrentMgmtHeading && driverNoAssistance)
                        + ", Driver verification: " + driverHasDriverVerification + ", Mgmt reset independent: " + mgmtHasCurrentVerification);
    }

    // --- Helper Methods ---

    private static HttpURLConnection sendPost(String urlStr, String body) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
        return conn;
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString();
        }
    }

    private static String getStudentPasswordHash(String studentId) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT password_hash FROM students WHERE student_id = ?")) {
            ps.setString(1, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("password_hash");
            }
        }
        return null;
    }

    private static String getManagementPasswordHash(String adminId) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT password_hash FROM management WHERE admin_id = ?")) {
            ps.setString(1, adminId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString("password_hash");
            }
        }
        return null;
    }

    private static String hashPasswordHelper(String password) {
        try {
            byte[] salt = new byte[16];
            new java.security.SecureRandom().nextBytes(salt);
            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                    password.toCharArray(), salt, 210000, 256
            );
            byte[] hash = javax.crypto.SecretKeyFactory
                    .getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
            return "PBKDF2$210000$"
                    + Base64.getEncoder().encodeToString(salt)
                    + "$"
                    + Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
