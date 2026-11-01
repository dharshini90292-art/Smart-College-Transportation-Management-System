import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.util.Base64;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import lib.DatabaseConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.awt.Desktop;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * =============================================================================
 * SMART COLLEGE TRANSPORTATION MANAGEMENT SYSTEM
 * Course: 2nd Year CSE Mini-Project
 * Technology: Pure Java (Application Logic & Page Routing) + Pure HTML/CSS Front-End
 * 
 * Purpose:
 *   1. Starts an embedded lightweight HTTP Web Server on port 8080 using standard Java.
 *   2. Handles application routing and navigation across all HTML pages and CSS styling.
 *   3. Enforces transportation business rules (40 students, 3 buses, 10 pickup stops).
 *   4. Logs user navigation events (Student, Driver, Management) in the console.
 *   5. Automatically launches the project inside your default web browser!
 * =============================================================================
 */
public class Main {

    private static final int PORT = 8080;
    private static final String SERVER_URL = "http://localhost:" + PORT + "/";
    private static final RoutePlanner ROUTE_PLANNER = new RoutePlanner();
    private static final Map<String, Profile> PROFILE_DATA = new ConcurrentHashMap<>();
    private static final Map<String, Student> STUDENTS = new ConcurrentHashMap<>();
private static final Map<String, String> STUDENT_PASSWORDS = new ConcurrentHashMap<>();
private static final Map<String, String> DRIVER_BUSES = new ConcurrentHashMap<>();
private static final Map<String, String> DRIVER_PASSWORDS = new ConcurrentHashMap<>();
private static final List<String> STUDENT_DEPARTMENTS = Collections.unmodifiableList(Arrays.asList(
        "Computer Science and Engineering (CSE)",
        "Information Technology (IT)",
        "Artificial Intelligence and Data Science (AI&DS)",
        "Artificial Intelligence and Machine Learning (AI&ML)",
        "Electronics and Communication Engineering (ECE)",
        "Electrical and Electronics Engineering (EEE)",
        "Mechanical Engineering (MECH)",
        "Civil Engineering (CIVIL)",
        "Computer Science and Engineering (Cyber Security)",
        "Computer Science and Engineering (IoT)",
        "Biomedical Engineering (BME)",
        "Biotechnology (BT)",
        "Chemical Engineering (CHEM)",
        "Mechatronics Engineering (MCT)",
        "Automobile Engineering (AUTO)",
        "Aeronautical Engineering (AERO)",
        "Agricultural Engineering (AGRI)",
        "Robotics and Automation (RA)",
        "Information Science and Engineering (ISE)",
        "Instrumentation and Control Engineering (ICE)"
));

private static final int MAX_STUDENTS = 100;
private static final int MAX_BUSES = 5;
private static final int MAX_DRIVERS = 5;
    private static final Map<String, UserSession> SESSIONS = new ConcurrentHashMap<>();

    static {
        // Profiles are loaded dynamically from the MySQL database based on the authenticated session.
    }

  
public static void main(String[] args) {
    try {
        ensureStudentAcademicColumns();
        ensureStudentPhotoColumn();
        ensureDriverColumns();
        ensureManagementTable();
        ensureBreakdownTable();
        ensureAttendanceReasonColumn();
        try (Connection con = DatabaseConnection.getConnection()) {
            TransportFeeService.ensureTables(con);
            TransportFeeService.recalculateAllStudentFees(con);
        } catch (Exception e) {
            System.err.println("[WARN] Fee service init: " + e.getMessage());
        }
        ROUTE_PLANNER.initialize();
        loadStudentsFromDatabase();
        loadDriversFromDatabase();

        Path attendancePath = Paths.get(
                System.getProperty(
                        "ciet.attendance.file",
                        "data/attendance.properties"
                )
        );

        AttendanceStore attendanceStore =
                new AttendanceStore(attendancePath);

        HttpServer server = HttpServer.create(
                new InetSocketAddress(PORT), 0
        );

        server.createContext(
                "/", new StaticFileHandler(attendanceStore)
        );

        server.setExecutor(null);
        server.start();

        printProjectBanner();
        launchBrowser(SERVER_URL);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(10000);
            } catch (InterruptedException e) {
                break;
            }
        }

    } catch (IOException | SQLException e) {
        System.err.println(
                "[ERROR] Failed to initialize the Java server or database schema: "
                        + e.getMessage()
        );
        System.err.println(
                "Make sure port " + PORT
                        + " is not already being used."
        );
    }
}

private static void ensureStudentAcademicColumns() throws SQLException {
    try (Connection con = DatabaseConnection.getConnection()) {
        boolean hasDepartment = false;
        boolean hasAcademicYear = false;
        try (ResultSet columns = con.getMetaData().getColumns(
                con.getCatalog(), null, "students", null)) {
            while (columns.next()) {
                String columnName = columns.getString("COLUMN_NAME");
                if ("department".equalsIgnoreCase(columnName)) {
                    hasDepartment = true;
                } else if ("academic_year".equalsIgnoreCase(columnName)) {
                    hasAcademicYear = true;
                }
            }
        }

        if (!hasDepartment) {
            try (java.sql.Statement statement = con.createStatement()) {
                statement.executeUpdate(
                        "ALTER TABLE students ADD COLUMN department VARCHAR(150) NULL"
                );
            }
        }
        if (!hasAcademicYear) {
            try (java.sql.Statement statement = con.createStatement()) {
                statement.executeUpdate(
                        "ALTER TABLE students ADD COLUMN academic_year VARCHAR(10) NULL"
                );
            }
        }
    }
}

private static void ensureStudentPhotoColumn() throws SQLException {
    try (Connection con = DatabaseConnection.getConnection()) {
        boolean hasPhoto = false;
        try (ResultSet columns = con.getMetaData().getColumns(
                con.getCatalog(), null, "students", null)) {
            while (columns.next()) {
                if ("profile_photo".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    hasPhoto = true;
                    break;
                }
            }
        }
        if (!hasPhoto) {
            try (java.sql.Statement statement = con.createStatement()) {
                statement.executeUpdate("ALTER TABLE students ADD COLUMN profile_photo VARCHAR(255) NULL");
            } catch (SQLException ignore) {}
        }
    }
}

private static void ensureDriverColumns() throws SQLException {
    try (Connection con = DatabaseConnection.getConnection()) {
        Map<String, String> required = new HashMap<>();
        required.put("phone_number", "VARCHAR(32) NULL");
        required.put("email", "VARCHAR(150) NULL");
        required.put("address", "VARCHAR(255) NULL");
        required.put("license_number", "VARCHAR(50) NULL");
        required.put("license_expiry", "VARCHAR(30) NULL");
        required.put("license_class", "VARCHAR(50) NULL");
        required.put("profile_photo", "VARCHAR(255) NULL");

        try (ResultSet columns = con.getMetaData().getColumns(
                con.getCatalog(), null, "drivers", null)) {
            while (columns.next()) {
                String col = columns.getString("COLUMN_NAME");
                if (col != null) {
                    required.remove(col.toLowerCase());
                }
            }
        }
        for (Map.Entry<String, String> entry : required.entrySet()) {
            try (java.sql.Statement st = con.createStatement()) {
                st.executeUpdate("ALTER TABLE drivers ADD COLUMN " + entry.getKey() + " " + entry.getValue());
            } catch (SQLException ignore) {}
        }
    }
}

private static void ensureDriverPhoneColumn() throws SQLException {
    ensureDriverColumns();
}

private static String resolvePhotoUrl(String dbPhoto) {
    if (dbPhoto != null && !dbPhoto.trim().isEmpty()) {
        String clean = dbPhoto.trim().replace("\\", "/");
        if (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        File f = new File(clean);
        if (f.exists() && !f.isDirectory()) {
            return "/" + clean;
        }
    }
    return "/uploads/profiles/default-avatar.svg";
}

private static int indexOf(byte[] data, byte[] target, int start, int max) {
    if (target.length == 0 || max - start < target.length) return -1;
    for (int i = start; i <= max - target.length; i++) {
        boolean match = true;
        for (int j = 0; j < target.length; j++) {
            if (data[i + j] != target[j]) {
                match = false;
                break;
            }
        }
        if (match) return i;
    }
    return -1;
}

private static void ensureManagementTable() throws SQLException {
    try (Connection con = DatabaseConnection.getConnection();
         java.sql.Statement st = con.createStatement()) {
        st.executeUpdate("CREATE TABLE IF NOT EXISTS management ("
                + "admin_id VARCHAR(30) PRIMARY KEY, "
                + "name VARCHAR(100) NOT NULL, "
                + "role VARCHAR(100) DEFAULT 'Transport Administrator', "
                + "email VARCHAR(150) DEFAULT 'admin@ciet.edu', "
                + "phone VARCHAR(32) DEFAULT '080-2699-0011', "
                + "organization VARCHAR(150) DEFAULT 'CIET Main Campus', "
                + "duty VARCHAR(100) DEFAULT 'Morning Shift', "
                + "profile_photo VARCHAR(255) NULL, "
                + "password_hash VARCHAR(255) NULL)");

        st.executeUpdate("INSERT INTO management (admin_id, name, role, email, phone, organization, duty) "
                + "VALUES ('ADMIN-01', 'Transport Directorate', 'Transport Administrator', 'admin@ciet.edu', '080-2699-0011', 'CIET Main Campus', 'Morning Shift') "
                + "ON DUPLICATE KEY UPDATE name = VALUES(name)");
    }
    ensureManagementPasswordColumn();
}

private static void ensureManagementPasswordColumn() {
    try (Connection con = DatabaseConnection.getConnection()) {
        boolean hasPassword = false;
        try (ResultSet columns = con.getMetaData().getColumns(con.getCatalog(), null, "management", null)) {
            while (columns.next()) {
                if ("password_hash".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    hasPassword = true;
                    break;
                }
            }
        }
        if (!hasPassword) {
            try (java.sql.Statement statement = con.createStatement()) {
                statement.executeUpdate("ALTER TABLE management ADD COLUMN password_hash VARCHAR(255) NULL");
            }
        }
    } catch (SQLException e) {
        System.err.println("[WARN] Could not ensure password_hash column on management table: " + e.getMessage());
    }
}

private static void ensureBreakdownTable() throws SQLException {
    try (Connection con = DatabaseConnection.getConnection();
         java.sql.Statement st = con.createStatement()) {
        st.executeUpdate("CREATE TABLE IF NOT EXISTS breakdown_reports ("
                + "report_id INT AUTO_INCREMENT PRIMARY KEY, "
                + "driver_id VARCHAR(30) NOT NULL, "
                + "bus_id VARCHAR(30) NOT NULL, "
                + "breakdown_date DATETIME NOT NULL, "
                + "issue_description TEXT NOT NULL, "
                + "severity VARCHAR(20) NOT NULL DEFAULT 'Medium', "
                + "status ENUM('Reported', 'In Progress', 'Resolved') NOT NULL DEFAULT 'Reported', "
                + "action_taken TEXT NULL, "
                + "remarks TEXT NULL, "
                + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                + "KEY idx_driver (driver_id), "
                + "KEY idx_bus (bus_id))");
    }
}

private static void ensureAttendanceReasonColumn() throws SQLException {
    try (Connection con = DatabaseConnection.getConnection()) {
        boolean hasReason = false;
        try (ResultSet columns = con.getMetaData().getColumns(
                con.getCatalog(), null, "attendance", null)) {
            while (columns.next()) {
                if ("absence_reason".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) {
                    hasReason = true;
                    break;
                }
            }
        }
        if (!hasReason) {
            try (java.sql.Statement statement = con.createStatement()) {
                statement.executeUpdate(
                        "ALTER TABLE attendance ADD COLUMN absence_reason VARCHAR(255) NULL"
                );
            }
        }
    }
}

private static void loadDriversFromDatabase() {
    String sql = "SELECT driver_id, bus_id FROM drivers";

    try (Connection con = DatabaseConnection.getConnection();
         PreparedStatement ps = con.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {

        DRIVER_BUSES.clear();

        while (rs.next()) {
            String driverId = rs.getString("driver_id");
            String busId = rs.getString("bus_id");

            if (driverId != null && busId != null) {
                driverId = driverId.trim();
                busId = busId.trim();

                String bus;
                if (busId.matches("BUS-\\d+")) {
                    bus = "Bus #" + busId.substring(4);
                } else {
                    bus = busId;
                }
                DRIVER_BUSES.put(driverId, bus);
            }
        }

        System.out.println(
                "[OK] Loaded " + DRIVER_BUSES.size()
                        + " driver-to-bus assignments from MySQL."
        );

    } catch (SQLException e) {
        System.err.println(
                "[ERROR] Could not load drivers from MySQL."
        );
        e.printStackTrace();
    }
}


    /**
     * HTTP Handler that handles file serving and page navigation.
     */
    static class StaticFileHandler implements HttpHandler {
        private final AttendanceStore attendanceStore;

        StaticFileHandler(AttendanceStore attendanceStore) {
            this.attendanceStore = attendanceStore;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String requestPath = exchange.getRequestURI().getPath();

            // Default route "/" redirects/maps to "index.html"
            if (requestPath.equals("/") || requestPath.isEmpty()) {
                requestPath = "/index.html";
            }

            // Clean the requested file name
            String fileName = requestPath.startsWith("/") ? requestPath.substring(1) : requestPath;

            // Handle query strings in forms (e.g. student-dashboard.html?studentId=...)
            if (fileName.contains("?")) {
                fileName = fileName.substring(0, fileName.indexOf("?"));
            }

            logNavigationEvent(fileName, exchange.getRequestURI().getQuery());

            if ("upload-profile-photo".equals(fileName)) {
                handleUploadProfilePhoto(exchange);
                return;
            }

            if ("login".equals(fileName)) {
                handleLogin(exchange);
                return;
            }

            if ("student-login.html".equals(fileName)
                    || "driver-login.html".equals(fileName)
                    || "management-login.html".equals(fileName)) {
                if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                    handleLogin(exchange);
                } else {
                    handleLoginPage(exchange, fileName);
                }
                return;
            }

            if ("reset-password".equals(fileName) || "reset-password.html".equals(fileName)) {
                handleResetPassword(exchange);
                return;
            }

if ("add-student".equals(fileName)) {
    handleAddStudent(exchange);
    return;
}

if ("reset-student-password".equals(fileName)) {
    handleResetStudentPassword(exchange);
    return;
}

if ("reset-management-password".equals(fileName)) {
    handleResetManagementPassword(exchange);
    return;
}

if ("add-driver".equals(fileName)) {
    handleAddDriver(exchange);
    return;
}

if ("route-location".equals(fileName)) {
    handleRouteLocationSave(exchange);
    return;
}

if ("add-stop".equals(fileName)) {
    handleAddStop(exchange);
    return;
}

if ("edit-stop".equals(fileName)) {
    handleEditStop(exchange);
    return;
}

if ("delete-stop".equals(fileName)) {
    handleDeleteStop(exchange);
    return;
}

if ("reassign-and-delete-stop".equals(fileName)) {
    handleReassignAndDeleteStop(exchange);
    return;
}

if ("toggle-stop-activation".equals(fileName)) {
    handleToggleStopActivation(exchange);
    return;
}

if ("preload-stops".equals(fileName)) {
    handlePreloadStops(exchange);
    return;
}

if ("update-terminals".equals(fileName)) {
    handleUpdateTerminals(exchange);
    return;
}

if ("reassign-student".equals(fileName)) {
    handleReassignStudent(exchange, attendanceStore);
    return;
}

if ("reallocate-student-bus".equals(fileName) || "assign-student-bus".equals(fileName) || "reallocate-bus".equals(fileName)) {
    handleReallocateStudentBus(exchange);
    return;
}

if ("update-fee-slabs".equals(fileName)) {
    handleUpdateFeeSlabs(exchange);
    return;
}

if ("api/route-data".equals(fileName) || "api-route-data".equals(fileName)) {
    handleApiRouteData(exchange);
    return;
}

if ("management-students.html".equals(fileName)) {
    handleStudentRegistrationPage(exchange);
    return;
}

if ("management-dashboard.html".equals(fileName)) {
    handleManagementDashboardPage(exchange);
    return;
}

if ("management-buses.html".equals(fileName)) {
    handleManagementBusesPage(exchange);
    return;
}

if ("management-drivers.html".equals(fileName)) {
    handleManagementDriversPage(exchange);
    return;
}

if ("management-stops.html".equals(fileName)) {
    handleManagementStopsPage(exchange);
    return;
}

if ("management-fees.html".equals(fileName)) {
    handleManagementFeesPage(exchange);
    return;
}

if ("management-breakdown.html".equals(fileName)) {
    handleManagementBreakdownPage(exchange);
    return;
}

if ("student-dashboard.html".equals(fileName)) {
    handleStudentDashboardPage(exchange);
    return;
}

if ("student-bus.html".equals(fileName)) {
    handleStudentBusPage(exchange);
    return;
}

if ("student-fees.html".equals(fileName)) {
    handleStudentFeesPage(exchange);
    return;
}

if ("driver-dashboard.html".equals(fileName)) {
    handleDriverDashboardPage(exchange);
    return;
}

if ("driver-bus.html".equals(fileName)) {
    handleDriverBusPage(exchange);
    return;
}

if ("driver-pickup-list.html".equals(fileName)) {
    handleDriverPickupListPage(exchange);
    return;
}

if ("driver-breakdown.html".equals(fileName)) {
    handleDriverBreakdownPage(exchange);
    return;
}

if ("management-routes.html".equals(fileName)) {
    handleManagementRoutesPage(exchange, attendanceStore);
    return;
}

if ("driver-route.html".equals(fileName)) {
    handleDriverRoutePage(exchange, attendanceStore);
    return;
}

if ("student-route.html".equals(fileName)) {
    handleRouteMapPage(exchange, attendanceStore);
    return;
}

if ("route-map.html".equals(fileName) || "route-map".equals(fileName)) {
    handleRouteMapPage(exchange, attendanceStore);
    return;
}

if ("recalculate-route".equals(fileName)) {
    handleRecalculateRoute(exchange, attendanceStore);
    return;
}
          
if ("logout".equals(fileName)) {
    clearSession(exchange);
    redirect(exchange, "/index.html");
    return;
}

if ("driver-profile.html".equals(fileName) || "student-profile.html".equals(fileName)) {
    handleProfileRequest(exchange, fileName);
    return;
}

if ("management-profile.html".equals(fileName)) {
    handleManagementProfilePage(exchange);
    return;
}

if ("driver-complaints.html".equals(fileName)) {
    handleDriverComplaintsPage(exchange);
    return;
}

if ("student-complaints.html".equals(fileName)) {
    handleStudentComplaintsPage(exchange);
    return;
}

if ("management-complaints.html".equals(fileName)) {
    handleManagementComplaintsPage(exchange);
    return;
}

            if (isAttendancePage(fileName)) {
                handleAttendanceRequest(exchange, fileName);
                return;
            }

            if (!isPublicAsset(fileName)) {
                sendError(exchange, 404, "The requested public page or asset could not be found.");
                return;
            }

            File file = new File(fileName);

            if (file.exists() && !file.isDirectory()) {
                // Determine MIME type
                String contentType = getContentType(fileName);
                exchange.getResponseHeaders().set("Content-Type", contentType);

                // Send 200 OK status code with file length
                exchange.sendResponseHeaders(200, file.length());

                // Stream the file content to the browser
                try (OutputStream os = exchange.getResponseBody();
                     FileInputStream fis = new FileInputStream(file)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        os.write(buffer, 0, bytesRead);
                    }
                }
            } else {
                // File not found 404 response
                String notFoundHtml = "<html><body style='font-family:sans-serif;text-align:center;padding:50px;'>"
                        + "<h1 style='color:#0f2b5c;'>404 - Page Not Found</h1>"
                        + "<p>The requested page <code>" + fileName + "</code> could not be found.</p>"
                        + "<a href='/index.html' style='color:#0284c7;font-weight:bold;'>&larr; Return to Home Page</a>"
                        + "</body></html>";
                byte[] responseBytes = notFoundHtml.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(404, responseBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(responseBytes);
                }
            }
        }

        private boolean isAttendancePage(String fileName) {
            return "student-attendance.html".equals(fileName)
                    || "driver-attendance.html".equals(fileName)
                    || "management-attendance.html".equals(fileName);
        }

        private boolean isPublicAsset(String fileName) {
            if (fileName.startsWith("uploads/profiles/") || fileName.startsWith("uploads\\profiles\\")) {
                String sub = fileName.substring(17);
                if (sub.contains("..") || sub.contains("/") || sub.contains("\\")) {
                    return false;
                }
                return sub.endsWith(".png") || sub.endsWith(".jpg") || sub.endsWith(".jpeg") || sub.endsWith(".svg");
            }
            if (fileName.contains("/") || fileName.contains("\\")) {
                return false;
            }
            return fileName.endsWith(".html") || fileName.endsWith(".css") || fileName.endsWith(".svg")
                    || fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")
                    || fileName.endsWith(".js");
        }

        private void handleUploadProfilePhoto(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "POST method required for photo upload.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null) {
                redirect(exchange, "/index.html");
                return;
            }

            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (contentType == null || !contentType.toLowerCase().contains("multipart/form-data")) {
                sendError(exchange, 400, "Content-Type must be multipart/form-data.");
                return;
            }

            String boundary = null;
            for (String part : contentType.split(";")) {
                part = part.trim();
                if (part.toLowerCase().startsWith("boundary=")) {
                    boundary = part.substring(9).trim();
                    if (boundary.startsWith("\"") && boundary.endsWith("\"")) {
                        boundary = boundary.substring(1, boundary.length() - 1);
                    }
                    break;
                }
            }
            if (boundary == null || boundary.isEmpty()) {
                sendError(exchange, 400, "Missing boundary in multipart request.");
                return;
            }

            int maxAllowed = 5 * 1024 * 1024 + 65536;
            java.io.InputStream is = exchange.getRequestBody();
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            int total = 0;
            while ((r = is.read(buf)) != -1) {
                total += r;
                if (total > maxAllowed) {
                    sendError(exchange, 400, "File size exceeds 5MB limit.");
                    return;
                }
                baos.write(buf, 0, r);
            }
            byte[] bodyBytes = baos.toByteArray();

            byte[] boundaryBytes = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
            int firstBoundary = indexOf(bodyBytes, boundaryBytes, 0, bodyBytes.length);
            if (firstBoundary < 0) {
                sendError(exchange, 400, "Invalid multipart body format.");
                return;
            }

            byte[] crlfcrlf = new byte[] { 13, 10, 13, 10 };
            byte[] lflf = new byte[] { 10, 10 };
            int headerEnd = indexOf(bodyBytes, crlfcrlf, firstBoundary, bodyBytes.length);
            int headerEndLen = 4;
            if (headerEnd < 0) {
                headerEnd = indexOf(bodyBytes, lflf, firstBoundary, bodyBytes.length);
                headerEndLen = 2;
            }
            if (headerEnd < 0) {
                sendError(exchange, 400, "Invalid part headers.");
                return;
            }

            String partHeaders = new String(bodyBytes, firstBoundary, headerEnd - firstBoundary, StandardCharsets.UTF_8);
            int filenameIdx = partHeaders.indexOf("filename=\"");
            if (filenameIdx < 0) {
                sendError(exchange, 400, "No file uploaded.");
                return;
            }
            int fnStart = filenameIdx + 10;
            int fnEnd = partHeaders.indexOf("\"", fnStart);
            if (fnEnd < 0) {
                sendError(exchange, 400, "Malformed filename in multipart header.");
                return;
            }
            String originalFilename = partHeaders.substring(fnStart, fnEnd).trim();
            if (originalFilename.isEmpty()) {
                sendError(exchange, 400, "No file selected for upload.");
                return;
            }

            int dataStart = headerEnd + headerEndLen;
            int nextBoundary = indexOf(bodyBytes, boundaryBytes, dataStart, bodyBytes.length);
            if (nextBoundary < 0) {
                sendError(exchange, 400, "Corrupted multipart stream.");
                return;
            }

            int dataEnd = nextBoundary;
            if (dataEnd >= 2 && bodyBytes[dataEnd - 2] == 13 && bodyBytes[dataEnd - 1] == 10) {
                dataEnd -= 2;
            } else if (dataEnd >= 1 && bodyBytes[dataEnd - 1] == 10) {
                dataEnd -= 1;
            }

            int fileLen = dataEnd - dataStart;
            if (fileLen <= 0) {
                sendError(exchange, 400, "Uploaded file is empty.");
                return;
            }
            if (fileLen > 5 * 1024 * 1024) {
                sendError(exchange, 400, "File size exceeds 5MB limit.");
                return;
            }

            byte[] fileData = new byte[fileLen];
            System.arraycopy(bodyBytes, dataStart, fileData, 0, fileLen);

            String lowerFn = originalFilename.toLowerCase();
            String ext = null;
            if (lowerFn.endsWith(".png")) {
                ext = ".png";
            } else if (lowerFn.endsWith(".jpg") || lowerFn.endsWith(".jpeg")) {
                ext = ".jpg";
            } else {
                sendError(exchange, 400, "Invalid image format. Allowed formats are JPG, JPEG, and PNG.");
                return;
            }

            boolean validMagic = false;
            if (".png".equals(ext)) {
                if (fileData.length >= 8
                        && (fileData[0] & 0xFF) == 0x89
                        && (fileData[1] & 0xFF) == 0x50
                        && (fileData[2] & 0xFF) == 0x4E
                        && (fileData[3] & 0xFF) == 0x47) {
                    validMagic = true;
                }
            } else {
                if (fileData.length >= 3
                        && (fileData[0] & 0xFF) == 0xFF
                        && (fileData[1] & 0xFF) == 0xD8
                        && (fileData[2] & 0xFF) == 0xFF) {
                    validMagic = true;
                }
            }
            if (!validMagic) {
                sendError(exchange, 400, "Invalid image content. File header does not match a valid image.");
                return;
            }

            File uploadDir = new File("uploads/profiles");
            if (!uploadDir.exists()) {
                uploadDir.mkdirs();
            }
            String safeUserId = session.userId.replaceAll("[^a-zA-Z0-9_-]", "");
            String safeFilename = "photo_" + session.role + "_" + safeUserId + "_" + System.currentTimeMillis() + ext;
            File savedFile = new File(uploadDir, safeFilename);
            Files.write(savedFile.toPath(), fileData);

            String dbPath = "uploads/profiles/" + safeFilename;
            try (Connection con = DatabaseConnection.getConnection()) {
                if ("student".equals(session.role)) {
                    String sql = "UPDATE students SET profile_photo = ? WHERE student_id = ?";
                    try (PreparedStatement ps = con.prepareStatement(sql)) {
                        ps.setString(1, dbPath);
                        ps.setString(2, session.userId);
                        ps.executeUpdate();
                    }
                    redirect(exchange, "/student-profile.html?saved=1");
                } else if ("driver".equals(session.role)) {
                    String sql = "UPDATE drivers SET profile_photo = ? WHERE driver_id = ?";
                    try (PreparedStatement ps = con.prepareStatement(sql)) {
                        ps.setString(1, dbPath);
                        ps.setString(2, session.userId);
                        ps.executeUpdate();
                    }
                    redirect(exchange, "/driver-profile.html?saved=1");
                } else if ("management".equals(session.role)) {
                    String sql = "UPDATE management SET profile_photo = ? WHERE admin_id = ?";
                    try (PreparedStatement ps = con.prepareStatement(sql)) {
                        ps.setString(1, dbPath);
                        ps.setString(2, session.userId);
                        ps.executeUpdate();
                    }
                    redirect(exchange, "/management-profile.html?saved=1");
                } else {
                    sendError(exchange, 403, "Unknown role for photo upload.");
                }
            } catch (SQLException e) {
                e.printStackTrace();
                sendError(exchange, 500, "Database error saving profile photo.");
            }
        }

        private void handleStudentRegistrationPage(HttpExchange exchange) throws IOException {
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET method required for the student register.");
                return;
            }

            try {
                ensureStudentAcademicColumns();
                String html = new String(
                        Files.readAllBytes(new File("management-students.html").toPath()),
                        StandardCharsets.UTF_8
                );
                String msg = registrationMessage(exchange.getRequestURI().getQuery());
                String deptOptions = departmentOptions();
                String busOptions = registeredBusOptions();
                String studentRows = registeredStudentRows();
                String resetOptions = studentResetOptions(exchange.getRequestURI().getQuery());
                html = html.replace("{{registrationMessage}}", msg)
                        .replace("<!-- REGISTRATION_MESSAGE -->", msg)
                        .replace("{{departmentOptions}}", deptOptions)
                        .replace("<!-- DEPARTMENT_OPTIONS -->", deptOptions)
                        .replace("{{busOptions}}", busOptions)
                        .replace("<!-- STUDENT_BUS_OPTIONS -->", busOptions)
                        .replace("{{studentRows}}", studentRows)
                        .replace("<!-- STUDENT_ROWS -->", studentRows)
                        .replace("{{studentResetOptions}}", resetOptions)
                        .replace("<!-- STUDENT_RESET_OPTIONS -->", resetOptions);
                sendHtml(exchange, html);
            } catch (SQLException exception) {
                exception.printStackTrace();
                sendError(exchange, 500, "Could not load student records from the database.");
            }
        }

        private void handleManagementDashboardPage(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET method required for the management dashboard.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }
            try {
                ensureStudentAcademicColumns();
                String html = new String(
                        Files.readAllBytes(new File("management-dashboard.html").toPath()),
                        StandardCharsets.UTF_8
                );
                String rows = managementDashboardStudentRows(attendanceStore);
                String busRows = managementDashboardBusRows();
                String busRouteManagement = managementBusRouteCards(attendanceStore);
                String regMsg = registrationMessage(exchange.getRequestURI().getQuery());
                int count = getStudentCount();
                Map<String, Integer> attendanceCounts = managementAttendanceCounts(attendanceStore);
                Map<String, Integer> busStudentCounts = getStudentCountsByBus();
                String adminPhoto = null;
                try (Connection con = DatabaseConnection.getConnection();
                     PreparedStatement ps = con.prepareStatement("SELECT profile_photo FROM management WHERE admin_id = ?")) {
                    ps.setString(1, session.userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) adminPhoto = rs.getString("profile_photo");
                    }
                }

                StringBuilder feeRows = new StringBuilder();
                try (Connection con = DatabaseConnection.getConnection()) {
                    List<TransportFeeService.StudentFeeInfo> feeList = TransportFeeService.getAllStudentFees(con);
                    for (TransportFeeService.StudentFeeInfo f : feeList) {
                        feeRows.append("<tr>")
                                .append("<td><strong>").append(escapeHtml(f.studentId)).append("</strong></td>")
                                .append("<td>").append(escapeHtml(f.studentName)).append("</td>")
                                .append("<td><span class=\"badge badge-info\">").append(escapeHtml(f.busId)).append("</span></td>")
                                .append("<td>").append(escapeHtml(f.stopName)).append("</td>")
                                .append("<td>").append(String.format(Locale.ROOT, "%.2f km", f.distanceKm)).append("</td>")
                                .append("<td style=\"font-weight:700; color:var(--green-success);\">&#8377; ").append(String.format(Locale.ROOT, "%,.2f", f.feeAmount)).append("</td>")
                                .append("</tr>");
                    }
                } catch (Exception e) {
                    System.err.println("[WARN] Mgmt dashboard fee rows: " + e.getMessage());
                }
                String feeRowsStr = feeRows.length() > 0 ? feeRows.toString() : "<tr><td colspan=\"6\" class=\"registration-empty\">No fee records available.</td></tr>";

                html = html.replace("{{studentRows}}", rows)
                        .replace("<!-- STUDENT_ROWS -->", rows)
                        .replace("{{busRows}}", busRows)
                        .replace("<!-- BUS_ROWS -->", busRows)
                        .replace("{{busRouteManagement}}", busRouteManagement)
                        .replace("<!-- BUS_ROUTE_MANAGEMENT -->", busRouteManagement)
                        .replace("{{registrationMessage}}", regMsg)
                        .replace("<!-- REGISTRATION_MESSAGE -->", regMsg)
                        .replace("{{totalStudentsCount}}", String.valueOf(count))
                        .replace("{{todayPresentCount}}", String.valueOf(attendanceCounts.get("present")))
                        .replace("{{todayAbsentCount}}", String.valueOf(attendanceCounts.get("absent")))
                        .replace("{{todayUnmarkedCount}}", String.valueOf(attendanceCounts.get("unmarked")))
                        .replace("{{authorizedStudentsCount}}", String.valueOf(attendanceCounts.get("authorized")))
                        .replace("{{unauthorizedStudentsCount}}", String.valueOf(attendanceCounts.get("unauthorized")))
                        .replace("{{bus01StudentCount}}", String.valueOf(busStudentCounts.getOrDefault("BUS-01", 0)))
                        .replace("{{bus02StudentCount}}", String.valueOf(busStudentCounts.getOrDefault("BUS-02", 0)))
                        .replace("{{bus03StudentCount}}", String.valueOf(busStudentCounts.getOrDefault("BUS-03", 0)))
                        .replace("{{managementPhotoUrl}}", resolvePhotoUrl(adminPhoto))
                        .replace("{{managementFeeRows}}", feeRowsStr);
                sendHtml(exchange, html);
            } catch (SQLException exception) {
                exception.printStackTrace();
                sendError(exchange, 500, "Could not load management dashboard from the database.");
            }
        }

        private String managementBusRouteCards(AttendanceStore attendanceStore) {
            StringBuilder sb = new StringBuilder();
            String[] buses = {"BUS-01", "BUS-02", "BUS-03"};
            String[] busLabels = {"Bus 01 (North Sector Corridor)", "Bus 02 (South Corridor)", "Bus 03 (East Corridor)"};
            LocalDate today = LocalDate.now();

            for (int i = 0; i < buses.length; i++) {
                String busId = buses[i];
                String label = busLabels[i];
                try {
                    RoutePlanner.RealRouteResult realRoute = ROUTE_PLANNER.calculateRealRoadRoute(busId, today, null);
                    String distStr = String.format(Locale.ROOT, "%.1f km", realRoute.distanceMeters / 1000.0);
                    long durationMins = Math.round(realRoute.durationSeconds / 60.0);
                    int activeCount = realRoute.orderedStops.size();
                    int skippedCount = realRoute.skippedStops.size();

                    sb.append("<article class=\"summary-card\" style=\"background:var(--bg-card);border:1px solid var(--border-light);padding:20px;border-radius:var(--radius-md);margin-bottom:14px;\">\n")
                            .append("  <div style=\"display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;\">\n")
                            .append("    <h3 style=\"color:var(--navy-primary);font-size:1.15rem;margin:0;\">").append(escapeHtml(label)).append("</h3>\n")
                            .append("    <span class=\"badge badge-success\">Road Optimized</span>\n")
                            .append("  </div>\n")
                            .append("  <div style=\"display:grid;grid-template-columns:repeat(auto-fit,minmax(140px,1fr));gap:12px;margin:12px 0 16px;\">\n")
                            .append("    <div style=\"background:#111;padding:10px;border-radius:6px;border:1px solid rgba(212,175,55,0.2);text-align:center;\">\n")
                            .append("      <div style=\"font-size:1.2rem;font-weight:700;color:var(--navy-primary);\">").append(distStr).append("</div>\n")
                            .append("      <div style=\"font-size:0.75rem;color:var(--text-muted);text-transform:uppercase;\">Distance</div>\n")
                            .append("    </div>\n")
                            .append("    <div style=\"background:#111;padding:10px;border-radius:6px;border:1px solid rgba(212,175,55,0.2);text-align:center;\">\n")
                            .append("      <div style=\"font-size:1.2rem;font-weight:700;color:var(--navy-primary);\">").append(durationMins).append(" min</div>\n")
                            .append("      <div style=\"font-size:0.75rem;color:var(--text-muted);text-transform:uppercase;\">Est. Transit</div>\n")
                            .append("    </div>\n")
                            .append("    <div style=\"background:#111;padding:10px;border-radius:6px;border:1px solid rgba(212,175,55,0.2);text-align:center;\">\n")
                            .append("      <div style=\"font-size:1.2rem;font-weight:700;color:var(--green-success);\">").append(activeCount).append("</div>\n")
                            .append("      <div style=\"font-size:0.75rem;color:var(--text-muted);text-transform:uppercase;\">Active Stops</div>\n")
                            .append("    </div>\n")
                            .append("    <div style=\"background:#111;padding:10px;border-radius:6px;border:1px solid rgba(212,175,55,0.2);text-align:center;\">\n")
                            .append("      <div style=\"font-size:1.2rem;font-weight:700;color:var(--red-danger);\">").append(skippedCount).append("</div>\n")
                            .append("      <div style=\"font-size:0.75rem;color:var(--text-muted);text-transform:uppercase;\">Skipped</div>\n")
                            .append("    </div>\n")
                            .append("  </div>\n")
                            .append("  <div style=\"display:flex;gap:10px;align-items:center;\">\n")
                            .append("    <a href=\"/management-routes.html?busId=").append(busId).append("&date=").append(today).append("\" class=\"btn btn-primary\" style=\"padding:7px 16px;font-size:0.85rem;\">Open Interactive Route Map &rarr;</a>\n")
                            .append("  </div>\n")
                            .append("</article>\n");
                } catch (Exception e) {
                    sb.append("<article class=\"summary-card\" style=\"padding:16px;\"><h3 style=\"color:var(--navy-primary);\">").append(escapeHtml(label)).append("</h3><p class=\"text-muted\">Route calculation pending.</p></article>\n");
                }
            }
            return sb.toString();
        }

        private void handleManagementBusesPage(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET method required for the buses page.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }
            try {
                String html = new String(
                        Files.readAllBytes(new File("management-buses.html").toPath()),
                        StandardCharsets.UTF_8
                );
                String busRows = managementBusesRows();
                html = html.replace("{{busRows}}", busRows)
                        .replace("<!-- BUS_ROWS -->", "");
                sendHtml(exchange, html);
            } catch (SQLException exception) {
                exception.printStackTrace();
                sendError(exchange, 500, "Could not load buses from the database.");
            }
        }

        private String managementBusesRows() throws SQLException {
            StringBuilder rows = new StringBuilder();
            String sql = "SELECT b.bus_id, b.bus_number, b.capacity, b.route_name, "
                    + "d.name AS driver_name "
                    + "FROM buses b "
                    + "LEFT JOIN drivers d ON d.bus_id = b.bus_id "
                    + "ORDER BY b.bus_id";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement statement = con.prepareStatement(sql);
                 ResultSet rs = statement.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    count++;
                    String busId = rs.getString("bus_id");
                    String reg = defaultDisplay(rs.getString("bus_number"));
                    int capacity = rs.getInt("capacity");
                    String route = defaultDisplay(rs.getString("route_name"));
                    String driverName = rs.getString("driver_name");

                    rows.append("<tr>")
                            .append("<td><strong>").append(escapeHtml(busId)).append("</strong></td>")
                            .append("<td>").append(escapeHtml(reg)).append("</td>")
                            .append("<td>").append(capacity > 0 ? capacity + " seats" : "Not specified").append("</td>")
                            .append("<td>").append(escapeHtml(route)).append("</td>")
                            .append("<td>").append(escapeHtml(driverName != null ? driverName : "Unassigned")).append("</td>")
                            .append("<td><span class=\"badge badge-success\">Available</span></td>")
                            .append("</tr>");
                }
                if (count == 0) {
                    rows.append("<tr><td colspan=\"6\" class=\"registration-empty\">No buses available.</td></tr>");
                }
            }
            return rows.toString();
        }

        private String managementDashboardBusRows() throws SQLException {
            StringBuilder rows = new StringBuilder();
            String sql = "SELECT b.bus_id, b.bus_number, b.capacity, b.route_name, "
                    + "d.name AS driver_name, d.phone_number AS driver_phone, "
                    + "COUNT(DISTINCT CASE WHEN s.transport_authorized = 1 THEN s.student_id END) AS assigned_students "
                    + "FROM buses b "
                    + "LEFT JOIN drivers d ON d.bus_id = b.bus_id "
                    + "LEFT JOIN students s ON s.bus_id = b.bus_id "
                    + "GROUP BY b.bus_id, b.bus_number, b.capacity, b.route_name, d.name, d.phone_number "
                    + "ORDER BY b.bus_id";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement statement = con.prepareStatement(sql);
                 ResultSet rs = statement.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    count++;
                    String busId = rs.getString("bus_id");
                    String reg = defaultDisplay(rs.getString("bus_number"));
                    String driverName = rs.getString("driver_name");
                    String driverPhone = rs.getString("driver_phone");
                    String route = defaultDisplay(rs.getString("route_name"));
                    int capacity = rs.getInt("capacity");
                    int assignedStudents = rs.getInt("assigned_students");

                    rows.append("<tr>")
                            .append("<td><strong>").append(escapeHtml(busId)).append("</strong></td>")
                            .append("<td>").append(escapeHtml(reg)).append("</td>")
                            .append("<td>").append(escapeHtml(driverName != null ? driverName : "Unassigned")).append("</td>")
                            .append("<td>").append(escapeHtml(driverPhone != null ? driverPhone : "Not specified")).append("</td>")
                            .append("<td>").append(escapeHtml(route)).append("</td>")
                            .append("<td>").append(capacity > 0 ? capacity + " seats" : "Not specified").append("</td>")
                            .append("<td>").append(assignedStudents).append(" students</td>")
                            .append("<td><span class=\"badge badge-success\">Operational</span></td>")
                            .append("</tr>");
                }
                if (count == 0) {
                    rows.append("<tr><td colspan=\"8\" class=\"registration-empty\">No buses available.</td></tr>");
                }
            }
            return rows.toString();
        }

        private void handleManagementDriversPage(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET method required for the drivers page.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }
            try {
                ensureDriverPhoneColumn();
                String html = new String(
                        Files.readAllBytes(new File("management-drivers.html").toPath()),
                        StandardCharsets.UTF_8
                );
                String msg = driverRegistrationMessage(exchange.getRequestURI().getQuery());
                String busOptions = registeredBusOptions();
                String driverRows = registeredDriverRows();
                html = html.replace("{{registrationMessage}}", msg)
                        .replace("<!-- REGISTRATION_MESSAGE -->", msg)
                        .replace("{{busOptions}}", busOptions)
                        .replace("<!-- DRIVER_BUS_OPTIONS -->", busOptions)
                        .replace("{{driverRows}}", driverRows)
                        .replace("<!-- DRIVER_ROWS -->", "");
                sendHtml(exchange, html);
            } catch (SQLException exception) {
                exception.printStackTrace();
                sendError(exchange, 500, "Could not load driver records from the database.");
            }
        }

        private String registeredDriverRows() throws SQLException {
            StringBuilder rows = new StringBuilder();
            String sql = "SELECT d.driver_id, d.name, d.phone_number, d.email, d.bus_id, d.license_number, d.profile_photo, b.bus_number "
                    + "FROM drivers d "
                    + "LEFT JOIN buses b ON b.bus_id = d.bus_id "
                    + "ORDER BY d.driver_id";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement statement = con.prepareStatement(sql);
                 ResultSet rs = statement.executeQuery()) {
                int count = 0;
                while (rs.next()) {
                    count++;
                    String driverId = rs.getString("driver_id");
                    String name = rs.getString("name");
                    String phone = rs.getString("phone_number");
                    String email = rs.getString("email");
                    String contact = phone != null && !phone.trim().isEmpty() ? phone.trim() : (email != null && !email.trim().isEmpty() ? email.trim() : "Not provided");
                    String busId = rs.getString("bus_id");
                    String busNumber = rs.getString("bus_number");
                    String busDisplay = busNumber != null ? busNumber : busId;
                    String license = rs.getString("license_number");
                    String photo = rs.getString("profile_photo");

                    rows.append("<tr>")
                            .append("<td><img src=\"").append(escapeHtml(resolvePhotoUrl(photo))).append("\" class=\"profile-avatar-sm\" alt=\"Driver Photo\"></td>")
                            .append("<td><strong>").append(escapeHtml(driverId)).append("</strong></td>")
                            .append("<td>").append(escapeHtml(defaultDisplay(name))).append("</td>")
                            .append("<td>").append(escapeHtml(contact)).append("</td>")
                            .append("<td>").append(escapeHtml(defaultDisplay(busDisplay))).append("</td>")
                            .append("<td>").append(escapeHtml(defaultDisplay(license))).append("</td>")
                            .append("<td><span class=\"badge badge-success\">Active Duty</span></td>")
                            .append("</tr>");
                }
                if (count == 0) {
                    rows.append("<tr><td colspan=\"7\" class=\"registration-empty\">No drivers available.</td></tr>");
                }
            }
            return rows.toString();
        }

        private void handleManagementStopsPage(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET method required for pickup stops.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }
            File file = new File("management-stops.html");
            if (!file.exists() || file.isDirectory()) {
                sendError(exchange, 404, "Management stops page not found.");
                return;
            }

            Map<String, Integer> studentCounts = new HashMap<>();
            try (Connection con = DatabaseConnection.getConnection();
                 Statement st = con.createStatement();
                 ResultSet rs = st.executeQuery("SELECT stop_name, COUNT(*) AS cnt FROM students WHERE stop_name IS NOT NULL AND stop_name != '' GROUP BY stop_name")) {
                while (rs.next()) {
                    studentCounts.put(rs.getString("stop_name").trim().toLowerCase(Locale.ROOT), rs.getInt("cnt"));
                }
            } catch (Exception ignored) {}

            List<RoutePlanner.ConfiguredStopInfo> stops = RoutePlanner.getAllConfiguredStops();
            StringBuilder rows = new StringBuilder();
            for (RoutePlanner.ConfiguredStopInfo s : stops) {
                int count = 0;
                for (Map.Entry<String, Integer> entry : studentCounts.entrySet()) {
                    String key = entry.getKey();
                    if (key.contains(s.stopCode.toLowerCase(Locale.ROOT)) || key.contains(s.stopName.toLowerCase(Locale.ROOT))) {
                        count += entry.getValue();
                    }
                }
                rows.append("<tr>")
                        .append("<td><span class=\"badge badge-info\">").append(escapeHtml(s.stopCode)).append("</span></td>")
                        .append("<td><strong>").append(escapeHtml(s.stopName)).append("</strong></td>")
                        .append("<td>").append(escapeHtml(s.corridor)).append("</td>")
                        .append("<td><span class=\"badge badge-primary\">").append(escapeHtml(s.busId)).append("</span></td>")
                        .append("<td>").append(count > 0 ? ("<span class=\"badge badge-success\">" + count + " Student" + (count > 1 ? "s" : "") + "</span>") : "<span class=\"text-muted\">0 Assigned</span>").append("</td>")
                        .append("</tr>");
            }

            String stopRows = rows.length() > 0 ? rows.toString() : "<tr><td colspan=\"5\" class=\"registration-empty\">No pickup stops configured.</td></tr>";
            String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                    .replace("{{stopRows}}", stopRows);
            sendHtml(exchange, html);
        }

        private void handleManagementFeesPage(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET method required for transport fees.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }
            String html = new String(
                    Files.readAllBytes(new File("management-fees.html").toPath()),
                    StandardCharsets.UTF_8
            );
            StringBuilder slabRows = new StringBuilder();
            StringBuilder feeRows = new StringBuilder();
            try (Connection con = DatabaseConnection.getConnection()) {
                TransportFeeService.ensureTables(con);
                List<TransportFeeService.FeeSlab> slabs = TransportFeeService.getFeeSlabs(con);
                for (TransportFeeService.FeeSlab slab : slabs) {
                    slabRows.append("<tr>")
                            .append("<td><strong>Slab #").append(slab.slabId).append("</strong></td>")
                            .append("<td>").append(String.format(Locale.ROOT, "%.2f", slab.minDistanceKm)).append(" &ndash; ")
                            .append(slab.maxDistanceKm >= 900 ? "Above " + slab.minDistanceKm : String.format(Locale.ROOT, "%.2f", slab.maxDistanceKm)).append(" km</td>")
                            .append("<td>").append(escapeHtml(slab.slabLabel)).append("</td>")
                            .append("<td><form method=\"POST\" action=\"/update-fee-slabs\" style=\"display:flex;align-items:center;gap:8px;margin:0;\">")
                            .append("<input type=\"hidden\" name=\"slabId\" value=\"").append(slab.slabId).append("\">")
                            .append("<span style=\"font-weight:700;\">&#8377;</span> ")
                            .append("<input type=\"number\" name=\"feeAmount\" value=\"").append((long) slab.feeAmount).append("\" min=\"25000\" step=\"500\" style=\"background:#111;border:1px solid var(--border-light);color:#fff;padding:4px 8px;border-radius:4px;width:110px;font-weight:700;\">")
                            .append("<button type=\"submit\" class=\"btn btn-primary\" style=\"padding:4px 10px;font-size:0.78rem;\">Update</button></form></td>")
                            .append("<td><span class=\"badge badge-success\">Configured</span></td>")
                            .append("</tr>");
                }

                List<TransportFeeService.StudentFeeInfo> feeList = TransportFeeService.getAllStudentFees(con);
                for (TransportFeeService.StudentFeeInfo f : feeList) {
                    feeRows.append("<tr>")
                            .append("<td><strong>").append(escapeHtml(f.studentId)).append("</strong></td>")
                            .append("<td>").append(escapeHtml(f.studentName)).append("</td>")
                            .append("<td><span class=\"badge badge-info\">").append(escapeHtml(f.busId)).append("</span></td>")
                            .append("<td>").append(escapeHtml(f.stopName)).append("</td>")
                            .append("<td>").append(String.format(Locale.ROOT, "%.2f km", f.distanceKm)).append("</td>")
                            .append("<td style=\"font-weight:700; color:var(--green-success);\">&#8377; ").append(String.format(Locale.ROOT, "%,.2f", f.feeAmount)).append("</td>")
                            .append("<td><span class=\"badge badge-success\">").append(escapeHtml(f.paymentStatus)).append("</span></td>")
                            .append("</tr>");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            String query = exchange.getRequestURI().getQuery();
            String alertMsg = "";
            if (query != null && query.contains("saved=1")) {
                alertMsg = "<div class=\"alert-callout alert-success\" style=\"margin-bottom:20px;\"><div class=\"alert-icon\">&#10004;</div><div><div class=\"alert-title\">Fee Slabs Updated</div><div class=\"alert-body\">Distance slabs updated successfully and student transport fees recalculated in MySQL.</div></div></div>";
            }

            String slabRowsStr = slabRows.length() > 0 ? slabRows.toString() : "<tr><td colspan=\"5\" class=\"registration-empty\">No distance fee slabs configured.</td></tr>";
            String feeRowsStr = feeRows.length() > 0 ? feeRows.toString() : "<tr><td colspan=\"7\" class=\"registration-empty\">No fee records available.</td></tr>";

            html = html.replace("{{feeAlertMessage}}", alertMsg)
                    .replace("{{slabRows}}", slabRowsStr)
                    .replace("{{feeRows}}", feeRowsStr)
                    .replace("<!-- FEE_ROWS -->", "");
            sendHtml(exchange, html);
        }

        private void handleUpdateFeeSlabs(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "POST method required.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }
            Map<String, String> form = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
            String slabIdStr = form.get("slabId");
            String feeAmountStr = form.get("feeAmount");
            if (slabIdStr != null && feeAmountStr != null) {
                try {
                    int slabId = Integer.parseInt(slabIdStr.trim());
                    double feeAmount = Double.parseDouble(feeAmountStr.trim());
                    try (Connection con = DatabaseConnection.getConnection()) {
                        TransportFeeService.updateFeeSlab(con, slabId, feeAmount);
                    }
                    redirect(exchange, "/management-fees.html?saved=1");
                    return;
                } catch (Exception e) {
                    System.err.println("[WARN] Could not update fee slab: " + e.getMessage());
                }
            }
            redirect(exchange, "/management-fees.html?error=invalid_data");
        }

        private void handleStudentFeesPage(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET method required for transport fees.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"student".equals(session.role)) {
                redirect(exchange, "/student-login.html");
                return;
            }
            File file = new File("student-fees.html");
            if (!file.exists() || file.isDirectory()) {
                sendError(exchange, 404, "Student fees page not found.");
                return;
            }
            String content = "<p class=\"registration-empty\">No fee records available.</p>";
            try (Connection con = DatabaseConnection.getConnection()) {
                TransportFeeService.ensureTables(con);
                TransportFeeService.StudentFeeInfo fee = TransportFeeService.getStudentFee(con, session.userId);
                if (fee != null) {
                    content = "<div class=\"details-grid\" style=\"margin-top:10px;\">"
                            + "<div class=\"detail-item\"><div class=\"detail-label\">Student ID</div><div class=\"detail-val\"><strong>" + escapeHtml(fee.studentId) + "</strong></div></div>"
                            + "<div class=\"detail-item\"><div class=\"detail-label\">Student Name</div><div class=\"detail-val\">" + escapeHtml(fee.studentName) + "</div></div>"
                            + "<div class=\"detail-item\"><div class=\"detail-label\">Assigned Bus</div><div class=\"detail-val\"><span class=\"badge badge-info\">" + escapeHtml(fee.busId) + "</span></div></div>"
                            + "<div class=\"detail-item\"><div class=\"detail-label\">Designated Pickup Stop</div><div class=\"detail-val\" style=\"color:var(--navy-primary);font-weight:700;\">" + escapeHtml(fee.stopName) + "</div></div>"
                            + "<div class=\"detail-item\"><div class=\"detail-label\">Distance to Campus</div><div class=\"detail-val\" style=\"font-weight:700;\">" + String.format(Locale.ROOT, "%.2f km", fee.distanceKm) + "</div></div>"
                            + "<div class=\"detail-item\"><div class=\"detail-label\">Applicable Transport Fee</div><div class=\"detail-val\" style=\"color:var(--green-success);font-size:1.3rem;font-weight:800;\">&#8377; " + String.format(Locale.ROOT, "%,.2f", fee.feeAmount) + "</div></div>"
                            + "<div class=\"detail-item\"><div class=\"detail-label\">Payment Status</div><div class=\"detail-val\"><span class=\"badge badge-success\">" + escapeHtml(fee.paymentStatus) + "</span></div></div>"
                            + "<div class=\"detail-item\"><div class=\"detail-label\">Fee Policy</div><div class=\"detail-val\" style=\"font-size:0.85rem;color:var(--text-muted);\">Distance-based structure (Starting minimum fee: &#8377; 25,000)</div></div>"
                            + "</div>";
                }
            } catch (Exception ignored) {}

            String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                    .replace("{{studentFeeContent}}", content);
            sendHtml(exchange, html);
        }

        private void handleManagementBreakdownPage(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET method required for breakdown reports.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }
            File file = new File("management-breakdown.html");
            if (!file.exists() || file.isDirectory()) {
                sendError(exchange, 404, "Management breakdown page not found.");
                return;
            }

            StringBuilder rows = new StringBuilder();
            int count = 0;
            String sql = "SELECT r.report_id, r.bus_id, r.driver_id, d.name AS driver_name, "
                    + "b.bus_number, r.breakdown_date, r.severity, r.status, r.issue_description, "
                    + "r.action_taken, r.remarks, r.created_at, r.updated_at "
                    + "FROM breakdown_reports r "
                    + "LEFT JOIN drivers d ON d.driver_id = r.driver_id "
                    + "LEFT JOIN buses b ON b.bus_id = r.bus_id "
                    + "ORDER BY r.created_at DESC, r.report_id DESC";

            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    count++;
                    int id = rs.getInt("report_id");
                    String busId = rs.getString("bus_id");
                    String busNumber = rs.getString("bus_number");
                    String busDisplay = busNumber != null ? busNumber : busId;
                    String driverId = rs.getString("driver_id");
                    String driverName = rs.getString("driver_name");
                    String driverDisplay = driverName != null ? driverName + " (" + driverId + ")" : driverId;
                    Timestamp bDate = rs.getTimestamp("breakdown_date");
                    String sev = rs.getString("severity");
                    if (sev == null || sev.isEmpty()) sev = "Medium";
                    String stat = rs.getString("status");
                    if (stat == null || stat.isEmpty()) stat = "Reported";
                    String desc = defaultDisplay(rs.getString("issue_description"));
                    String act = rs.getString("action_taken");
                    String rem = rs.getString("remarks");
                    Timestamp updated = rs.getTimestamp("updated_at");

                    StringBuilder notes = new StringBuilder();
                    if (act != null && !act.trim().isEmpty()) {
                        notes.append("<strong>Action:</strong> ").append(escapeHtml(act.trim()));
                    }
                    if (rem != null && !rem.trim().isEmpty()) {
                        if (notes.length() > 0) notes.append("<br>");
                        notes.append("<strong>Remarks:</strong> ").append(escapeHtml(rem.trim()));
                    }
                    if (updated != null) {
                        if (notes.length() > 0) notes.append("<br>");
                        notes.append("<span style=\"font-size:0.75rem;color:var(--text-muted);\">Updated: ").append(updated.toString()).append("</span>");
                    }
                    if (notes.length() == 0) notes.append("None recorded");

                    String sevBadge = "<span class=\"badge badge-sev-" + sev.toLowerCase() + "\">" + escapeHtml(sev) + "</span>";
                    String statBadge = "<span class=\"badge " + ("Resolved".equalsIgnoreCase(stat) ? "badge-resolved" : "In Progress".equalsIgnoreCase(stat) ? "badge-inprogress" : "badge-reported") + "\">" + escapeHtml(stat) + "</span>";

                    rows.append("<tr>")
                            .append("<td><strong>BRK-").append(id).append("</strong></td>")
                            .append("<td>").append(escapeHtml(busDisplay)).append("</td>")
                            .append("<td>").append(escapeHtml(driverDisplay)).append("</td>")
                            .append("<td>").append(sevBadge).append("</td>")
                            .append("<td>").append(bDate != null ? bDate.toString() : "-").append("</td>")
                            .append("<td>").append(escapeHtml(desc)).append("</td>")
                            .append("<td>").append(notes.toString()).append("</td>")
                            .append("<td>").append(statBadge).append("</td>")
                            .append("</tr>");
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }

            if (count == 0) {
                rows.append("<tr><td colspan=\"8\" class=\"registration-empty\">No breakdown records available.</td></tr>");
            }

            String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                    .replace("{{breakdownRows}}", rows.toString())
                    .replace("<!-- BREAKDOWN_ROWS -->", "");

            sendHtml(exchange, html);
        }

        private void handleDriverBusPage(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET method required for the driver's bus page.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"driver".equals(session.role)) {
                sendError(exchange, 403, "Driver login required.");
                return;
            }
            try {
                String html = new String(
                        Files.readAllBytes(new File("driver-bus.html").toPath()),
                        StandardCharsets.UTF_8
                );
                String busId = null;
                String busNumber = null;
                int capacity = 0;
                String routeName = null;

                String driverSql = "SELECT d.bus_id, b.bus_number, b.capacity, b.route_name "
                        + "FROM drivers d LEFT JOIN buses b ON b.bus_id = d.bus_id "
                        + "WHERE d.driver_id = ?";
                try (Connection con = DatabaseConnection.getConnection();
                     PreparedStatement statement = con.prepareStatement(driverSql)) {
                    statement.setString(1, session.userId);
                    try (ResultSet rs = statement.executeQuery()) {
                        if (rs.next()) {
                            busId = rs.getString("bus_id");
                            busNumber = rs.getString("bus_number");
                            capacity = rs.getInt("capacity");
                            routeName = rs.getString("route_name");
                        }
                    }
                }

                String busDisplay = "No bus assigned.";
                String busIdDisplay = "None";
                String routeDisplay = "No route assigned";
                String capacityDisplay = "N/A";
                int studentCount = 0;

                if (busId != null && !busId.trim().isEmpty()) {
                    busDisplay = busNumber != null ? busNumber : busId;
                    busIdDisplay = busId;
                    routeDisplay = routeName != null ? routeName : "Not specified";
                    capacityDisplay = capacity > 0 ? capacity + " seats" : "Not specified";

                    String countSql = "SELECT COUNT(*) FROM students "
                            + "WHERE bus_id = ? AND transport_authorized = 1";
                    try (Connection con = DatabaseConnection.getConnection();
                         PreparedStatement statement = con.prepareStatement(countSql)) {
                        statement.setString(1, busId);
                        try (ResultSet rs = statement.executeQuery()) {
                            if (rs.next()) {
                                studentCount = rs.getInt(1);
                            }
                        }
                    }
                }

                html = html.replace("{{driverBus}}", escapeHtml(busDisplay))
                        .replace("{{busId}}", escapeHtml(busIdDisplay))
                        .replace("{{routeName}}", escapeHtml(routeDisplay))
                        .replace("{{busCapacity}}", escapeHtml(capacityDisplay))
                        .replace("{{assignedStudentCount}}", String.valueOf(studentCount));
                sendHtml(exchange, html);
            } catch (SQLException exception) {
                exception.printStackTrace();
                sendError(exchange, 500, "Could not load bus details from the database.");
            }
        }

        private void handleDriverPickupListPage(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET method required for the pickup list page.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"driver".equals(session.role)) {
                sendError(exchange, 403, "Driver login required.");
                return;
            }
            try {
                String html = new String(
                        Files.readAllBytes(new File("driver-pickup-list.html").toPath()),
                        StandardCharsets.UTF_8
                );
                String busId = null;
                String busNumber = null;
                String driverSql = "SELECT d.bus_id, b.bus_number "
                        + "FROM drivers d LEFT JOIN buses b ON b.bus_id = d.bus_id "
                        + "WHERE d.driver_id = ?";
                try (Connection con = DatabaseConnection.getConnection();
                     PreparedStatement statement = con.prepareStatement(driverSql)) {
                    statement.setString(1, session.userId);
                    try (ResultSet rs = statement.executeQuery()) {
                        if (rs.next()) {
                            busId = rs.getString("bus_id");
                            busNumber = rs.getString("bus_number");
                        }
                    }
                }

                String busDisplay = "No bus assigned.";
                StringBuilder rows = new StringBuilder();
                LocalDate today = LocalDate.now();

                if (busId != null && !busId.trim().isEmpty()) {
                    busDisplay = busNumber != null ? busNumber : busId;
                    List<DriverStudentEntry> studentList = new ArrayList<>();
                    String studentsSql = "SELECT s.student_id, s.name, s.stop_name "
                            + "FROM students s "
                            + "WHERE s.bus_id = ? AND s.stop_name IS NOT NULL AND TRIM(s.stop_name) <> '' "
                            + "ORDER BY s.student_id";
                    try (Connection con = DatabaseConnection.getConnection();
                         PreparedStatement statement = con.prepareStatement(studentsSql)) {
                        statement.setString(1, busId);
                        try (ResultSet rs = statement.executeQuery()) {
                            while (rs.next()) {
                                String sId = rs.getString("student_id");
                                String name = defaultDisplay(rs.getString("name"));
                                String rawStop = rs.getString("stop_name");
                                String normStop = RoutePlanner.normalizeStopCode(rawStop);
                                int order = extractStopOrder(normStop);
                                String attStatus = getStudentAttendanceStatus(con, attendanceStore, sId, today);
                                studentList.add(new DriverStudentEntry(sId, name, normStop, order, attStatus));
                            }
                        }
                    }

                    // Sort Stop 1 to Stop 10 order
                    studentList.sort((a, b) -> {
                        if (a.stopOrder != b.stopOrder) {
                            return Integer.compare(a.stopOrder, b.stopOrder);
                        }
                        int stopCmp = a.stopName.compareToIgnoreCase(b.stopName);
                        if (stopCmp != 0) return stopCmp;
                        return a.studentId.compareToIgnoreCase(b.studentId);
                    });

                    for (DriverStudentEntry entry : studentList) {
                        String rowClass = "row-unmarked";
                        String badgeHtml = "<span class=\"badge badge-neutral\">Not Marked</span>";
                        if ("Present".equalsIgnoreCase(entry.attendance)) {
                            rowClass = "row-present";
                            badgeHtml = "<span class=\"badge badge-success\" style=\"font-weight:700;\">&#10004; Present</span>";
                        } else if ("Absent".equalsIgnoreCase(entry.attendance)) {
                            rowClass = "row-absent";
                            badgeHtml = "<span class=\"badge badge-danger\">Absent</span>";
                        }

                        rows.append("<tr class=\"").append(rowClass).append("\">")
                                .append("<td><strong>").append(escapeHtml(entry.studentId)).append("</strong></td>")
                                .append("<td>").append(escapeHtml(entry.name)).append("</td>")
                                .append("<td>").append(escapeHtml(entry.stopName)).append("</td>")
                                .append("<td>").append(badgeHtml).append("</td>")
                                .append("</tr>\n");
                    }
                }

                if (rows.length() == 0) {
                    rows.append("<tr><td colspan=\"4\" class=\"registration-empty\">No students assigned to this bus.</td></tr>\n");
                }

                html = html.replace("{{driverBus}}", escapeHtml(busDisplay))
                        .replace("{{pickupListRows}}", rows.toString())
                        .replace("<!-- PICKUP_LIST_ROWS -->", "");
                sendHtml(exchange, html);
            } catch (SQLException exception) {
                exception.printStackTrace();
                sendError(exchange, 500, "Could not load pickup list from the database.");
            }
        }

        private void handleDriverBreakdownPage(HttpExchange exchange) throws IOException {
            UserSession session = currentSession(exchange);
            if (session == null || !"driver".equals(session.role)) {
                redirect(exchange, "/driver-login.html");
                return;
            }

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                handleDriverBreakdownSubmission(exchange, session);
                return;
            }

            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET and POST supported for breakdown reports.");
                return;
            }

            File file = new File("driver-breakdown.html");
            if (!file.exists() || file.isDirectory()) {
                sendError(exchange, 404, "Breakdown report page not found.");
                return;
            }

            String busId = "BUS-01";
            String busNumber = "Bus 01";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(
                         "SELECT d.bus_id, b.bus_number FROM drivers d LEFT JOIN buses b ON b.bus_id = d.bus_id WHERE d.driver_id = ?")) {
                ps.setString(1, session.userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String b = rs.getString("bus_id");
                        if (b != null && !b.trim().isEmpty()) busId = b.trim();
                        String bn = rs.getString("bus_number");
                        if (bn != null && !bn.trim().isEmpty()) busNumber = bn.trim();
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }

            StringBuilder itemsHtml = new StringBuilder();
            int reportCount = 0;
            String querySql = "SELECT report_id, driver_id, bus_id, breakdown_date, issue_description, severity, status, action_taken, remarks, created_at, updated_at "
                    + "FROM breakdown_reports WHERE driver_id = ? OR bus_id = ? "
                    + "ORDER BY created_at DESC, report_id DESC";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(querySql)) {
                ps.setString(1, session.userId);
                ps.setString(2, busId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        reportCount++;
                        int id = rs.getInt("report_id");
                        String rDriverId = rs.getString("driver_id");
                        String rBusId = rs.getString("bus_id");
                        Timestamp bDate = rs.getTimestamp("breakdown_date");
                        String desc = defaultDisplay(rs.getString("issue_description"));
                        String sev = rs.getString("severity");
                        if (sev == null || sev.isEmpty()) sev = "Medium";
                        String stat = rs.getString("status");
                        if (stat == null || stat.isEmpty()) stat = "Reported";
                        String act = defaultDisplay(rs.getString("action_taken"));
                        String rem = defaultDisplay(rs.getString("remarks"));
                        Timestamp created = rs.getTimestamp("created_at");
                        Timestamp updated = rs.getTimestamp("updated_at");

                        String sevClass = "badge-sev-" + sev.toLowerCase();
                        String statClass;
                        if ("Resolved".equalsIgnoreCase(stat)) {
                            statClass = "badge-resolved";
                        } else if ("In Progress".equalsIgnoreCase(stat)) {
                            statClass = "badge-inprogress";
                        } else {
                            statClass = "badge-reported";
                        }

                        boolean isOwner = session.userId.equals(rDriverId);

                        itemsHtml.append("<div class=\"breakdown-card\">")
                                .append("<div class=\"breakdown-card-header\">")
                                .append("<div>")
                                .append("<strong>BRK-").append(id).append(" &bull; ").append(escapeHtml(rBusId)).append("</strong>")
                                .append("<span style=\"margin-left: 10px; font-size: 0.85rem; color: var(--text-muted);\">Logged by: ").append(escapeHtml(rDriverId)).append("</span>")
                                .append("</div>")
                                .append("<div style=\"display: flex; gap: 8px;\">")
                                .append("<span class=\"badge ").append(sevClass).append("\">").append(escapeHtml(sev)).append(" Severity</span>")
                                .append("<span class=\"badge ").append(statClass).append("\">").append(escapeHtml(stat)).append("</span>")
                                .append("</div>")
                                .append("</div>")
                                .append("<div style=\"font-size: 0.9rem; margin-bottom: 8px;\"><strong>Incident Time: </strong>").append(bDate != null ? bDate.toString() : "Not recorded").append("</div>")
                                .append("<p style=\"margin-bottom: 8px;\"><strong>Issue: </strong>").append(escapeHtml(desc)).append("</p>")
                                .append("<p style=\"font-size: 0.9rem; margin-bottom: 4px;\"><strong>Action Taken: </strong>").append(escapeHtml(act)).append("</p>")
                                .append("<p style=\"font-size: 0.9rem; margin-bottom: 12px;\"><strong>Remarks: </strong>").append(escapeHtml(rem)).append("</p>")
                                .append("<div style=\"font-size: 0.8rem; color: var(--text-muted); margin-bottom: 12px;\">Created: ").append(created != null ? created.toString() : "-")
                                .append(" &bull; Last Updated: ").append(updated != null ? updated.toString() : "-").append("</div>");

                        if (isOwner) {
                            itemsHtml.append("<form action=\"driver-breakdown.html\" method=\"post\" class=\"breakdown-update-box\">")
                                    .append("<input type=\"hidden\" name=\"action\" value=\"update\">")
                                    .append("<input type=\"hidden\" name=\"reportId\" value=\"").append(id).append("\">")
                                    .append("<div style=\"font-weight: 600; font-size: 0.9rem; margin-bottom: 6px; color: var(--navy-primary);\">Update Status &amp; Notes:</div>")
                                    .append("<div style=\"display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-bottom: 8px;\">")
                                    .append("<div><label style=\"font-size: 0.85rem; font-weight: 600;\">Severity</label>")
                                    .append("<select name=\"severity\" class=\"form-control\" style=\"padding: 6px;\">")
                                    .append("<option value=\"Low\"").append("Low".equalsIgnoreCase(sev) ? " selected" : "").append(">Low</option>")
                                    .append("<option value=\"Medium\"").append("Medium".equalsIgnoreCase(sev) ? " selected" : "").append(">Medium</option>")
                                    .append("<option value=\"High\"").append("High".equalsIgnoreCase(sev) ? " selected" : "").append(">High</option>")
                                    .append("<option value=\"Critical\"").append("Critical".equalsIgnoreCase(sev) ? " selected" : "").append(">Critical</option>")
                                    .append("</select></div>")
                                    .append("<div><label style=\"font-size: 0.85rem; font-weight: 600;\">Status</label>")
                                    .append("<select name=\"status\" class=\"form-control\" style=\"padding: 6px;\">")
                                    .append("<option value=\"Reported\"").append("Reported".equalsIgnoreCase(stat) ? " selected" : "").append(">Reported</option>")
                                    .append("<option value=\"In Progress\"").append("In Progress".equalsIgnoreCase(stat) ? " selected" : "").append(">In Progress</option>")
                                    .append("<option value=\"Resolved\"").append("Resolved".equalsIgnoreCase(stat) ? " selected" : "").append(">Resolved</option>")
                                    .append("</select></div></div>")
                                    .append("<div style=\"margin-bottom: 8px;\"><label style=\"font-size: 0.85rem; font-weight: 600;\">Action Taken</label>")
                                    .append("<input type=\"text\" name=\"actionTaken\" class=\"form-control\" value=\"").append(escapeHtml("Not specified".equals(act) ? "" : act)).append("\" placeholder=\"Update action taken\"></div>")
                                    .append("<div style=\"margin-bottom: 8px;\"><label style=\"font-size: 0.85rem; font-weight: 600;\">Remarks</label>")
                                    .append("<input type=\"text\" name=\"remarks\" class=\"form-control\" value=\"").append(escapeHtml("Not specified".equals(rem) ? "" : rem)).append("\" placeholder=\"Update remarks\"></div>")
                                    .append("<button type=\"submit\" class=\"btn btn-primary btn-sm\">Save Update</button>")
                                    .append("</form>");
                        }

                        itemsHtml.append("</div>\n");
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }

            String listContent = reportCount == 0
                    ? "<p class=\"registration-empty\" style=\"padding: 24px; text-align: center;\">No breakdown reports available.</p>"
                    : itemsHtml.toString();

            String query = exchange.getRequestURI().getQuery();
            String msg = "";
            if ("created".equals(getQueryValue(query, "result"))) {
                msg = "<p class=\"registration-message registration-success\" role=\"status\">Breakdown report submitted successfully.</p>";
            } else if ("updated".equals(getQueryValue(query, "result"))) {
                msg = "<p class=\"registration-message registration-success\" role=\"status\">Breakdown report updated successfully.</p>";
            } else if ("invalid".equals(getQueryValue(query, "error"))) {
                msg = "<p class=\"registration-message registration-error\" role=\"alert\">Please fill in all required breakdown report fields.</p>";
            } else if ("unauthorized".equals(getQueryValue(query, "error"))) {
                msg = "<p class=\"registration-message registration-error\" role=\"alert\">You are not authorized to edit this report.</p>";
            }

            String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                    .replace("{{assignedBus}}", escapeHtml(busNumber))
                    .replace("{{assignedBusId}}", escapeHtml(busId))
                    .replace("{{currentDateTime}}", java.time.LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MINUTES).toString())
                    .replace("{{breakdownMessage}}", msg)
                    .replace("<!-- BREAKDOWN_MESSAGE -->", msg)
                    .replace("{{breakdownReportItems}}", listContent)
                    .replace("<!-- BREAKDOWN_REPORTS -->", "");

            sendHtml(exchange, html);
        }

        private void handleDriverBreakdownSubmission(HttpExchange exchange, UserSession session) throws IOException {
            Map<String, String> fields;
            try {
                fields = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
            } catch (Exception e) {
                redirect(exchange, "/driver-breakdown.html?error=invalid");
                return;
            }

            String action = fields.get("action");
            if ("create".equalsIgnoreCase(action)) {
                String busId = fields.get("busId");
                String dateStr = fields.get("breakdownDate");
                String severity = fields.get("severity");
                String status = fields.get("status");
                String description = fields.get("issueDescription");
                String actionTaken = fields.get("actionTaken");
                String remarks = fields.get("remarks");

                if (busId == null || description == null || description.trim().isEmpty()) {
                    redirect(exchange, "/driver-breakdown.html?error=invalid");
                    return;
                }
                if (severity == null || severity.isEmpty()) severity = "Medium";
                if (status == null || status.isEmpty()) status = "Reported";

                java.sql.Timestamp bTime;
                try {
                    if (dateStr != null && !dateStr.trim().isEmpty()) {
                        bTime = java.sql.Timestamp.valueOf(java.time.LocalDateTime.parse(dateStr.trim()));
                    } else {
                        bTime = new java.sql.Timestamp(System.currentTimeMillis());
                    }
                } catch (Exception e) {
                    bTime = new java.sql.Timestamp(System.currentTimeMillis());
                }

                String insertSql = "INSERT INTO breakdown_reports (driver_id, bus_id, breakdown_date, issue_description, severity, status, action_taken, remarks) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
                try (Connection con = DatabaseConnection.getConnection();
                     PreparedStatement ps = con.prepareStatement(insertSql)) {
                    ps.setString(1, session.userId);
                    ps.setString(2, busId.trim());
                    ps.setTimestamp(3, bTime);
                    ps.setString(4, description.trim());
                    ps.setString(5, severity);
                    ps.setString(6, status);
                    ps.setString(7, actionTaken != null && !actionTaken.trim().isEmpty() ? actionTaken.trim() : null);
                    ps.setString(8, remarks != null && !remarks.trim().isEmpty() ? remarks.trim() : null);
                    ps.executeUpdate();
                    redirect(exchange, "/driver-breakdown.html?result=created");
                } catch (SQLException e) {
                    e.printStackTrace();
                    redirect(exchange, "/driver-breakdown.html?error=database");
                }
            } else if ("update".equalsIgnoreCase(action)) {
                String reportIdStr = fields.get("reportId");
                String severity = fields.get("severity");
                String status = fields.get("status");
                String actionTaken = fields.get("actionTaken");
                String remarks = fields.get("remarks");

                if (reportIdStr == null || reportIdStr.isEmpty()) {
                    redirect(exchange, "/driver-breakdown.html?error=invalid");
                    return;
                }

                try {
                    int reportId = Integer.parseInt(reportIdStr);
                    String checkSql = "SELECT driver_id FROM breakdown_reports WHERE report_id = ?";
                    try (Connection con = DatabaseConnection.getConnection();
                         PreparedStatement checkPs = con.prepareStatement(checkSql)) {
                        checkPs.setInt(1, reportId);
                        try (ResultSet rs = checkPs.executeQuery()) {
                            if (!rs.next() || !session.userId.equals(rs.getString("driver_id"))) {
                                redirect(exchange, "/driver-breakdown.html?error=unauthorized");
                                return;
                            }
                        }
                    }

                    String updateSql = "UPDATE breakdown_reports SET severity = ?, status = ?, action_taken = ?, remarks = ?, updated_at = NOW() WHERE report_id = ? AND driver_id = ?";
                    try (Connection con = DatabaseConnection.getConnection();
                         PreparedStatement ps = con.prepareStatement(updateSql)) {
                        ps.setString(1, severity != null ? severity : "Medium");
                        ps.setString(2, status != null ? status : "Reported");
                        ps.setString(3, actionTaken != null && !actionTaken.trim().isEmpty() ? actionTaken.trim() : null);
                        ps.setString(4, remarks != null && !remarks.trim().isEmpty() ? remarks.trim() : null);
                        ps.setInt(5, reportId);
                        ps.setString(6, session.userId);
                        ps.executeUpdate();
                        redirect(exchange, "/driver-breakdown.html?result=updated");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    redirect(exchange, "/driver-breakdown.html?error=database");
                }
            } else {
                redirect(exchange, "/driver-breakdown.html?error=invalid");
            }
        }

        private String renderDriverDashboardBreakdownSection(String driverId, String busId) {
            String sql = "SELECT report_id, severity, status, issue_description, breakdown_date, action_taken, remarks "
                    + "FROM breakdown_reports WHERE driver_id = ? OR bus_id = ? "
                    + "ORDER BY created_at DESC, report_id DESC LIMIT 1";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, driverId);
                ps.setString(2, busId != null ? busId : "");
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int id = rs.getInt("report_id");
                        String sev = rs.getString("severity");
                        String stat = rs.getString("status");
                        String desc = defaultDisplay(rs.getString("issue_description"));
                        Timestamp bDate = rs.getTimestamp("breakdown_date");
                        String sevBadge = "<span class=\"badge badge-sev-" + (sev != null ? sev.toLowerCase() : "medium") + "\">" + escapeHtml(sev) + "</span>";
                        String statBadge = "<span class=\"badge " + ("Resolved".equalsIgnoreCase(stat) ? "badge-resolved" : "In Progress".equalsIgnoreCase(stat) ? "badge-inprogress" : "badge-reported") + "\">" + escapeHtml(stat) + "</span>";

                        return "<article class=\"info-card\" id=\"driver-breakdown\">"
                                + "<div class=\"info-card-header\"><h2 class=\"info-card-title\">Active Breakdown Incident</h2>"
                                + "<div style=\"display:flex;gap:8px;\">" + sevBadge + statBadge + "<a href=\"driver-breakdown.html\" class=\"btn btn-secondary btn-sm\">Manage Reports</a></div></div>"
                                + "<div class=\"info-card-body\"><p><strong>Report: </strong>BRK-" + id + " &bull; <strong>Time: </strong>" + (bDate != null ? bDate.toString() : "-") + "</p>"
                                + "<p><strong>Issue: </strong>" + escapeHtml(desc) + "</p></div></article>";
                    }
                }
            } catch (SQLException ignore) {}
            return "<article class=\"info-card\" id=\"driver-breakdown\"><div class=\"info-card-header\"><h2 class=\"info-card-title\">Breakdown Reports</h2>"
                    + "<a href=\"driver-breakdown.html\" class=\"btn btn-secondary btn-sm\">Manage Reports</a></div>"
                    + "<div class=\"info-card-body\"><p class=\"registration-empty\">No breakdown reports available.</p></div></article>";
        }

        private String managementDashboardStudentRows(AttendanceStore attendanceStore) throws SQLException {
            StringBuilder rows = new StringBuilder();
            String sql = "SELECT s.student_id, s.name, s.department, s.academic_year, "
                    + "s.bus_id, b.bus_number, s.stop_name, s.transport_authorized "
                    + "FROM students s LEFT JOIN buses b ON b.bus_id = s.bus_id "
                    + "ORDER BY s.student_id";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement statement = con.prepareStatement(sql);
                 ResultSet students = statement.executeQuery()) {
                int count = 0;
                while (students.next()) {
                    count++;
                    String busNumber = students.getString("bus_number");
                    String busId = students.getString("bus_id");
                    String busDisplay = busNumber == null ? busId : busNumber;
                    String dept = students.getString("department");
                    String year = students.getString("academic_year");
                    String deptYear = (dept != null && !dept.isEmpty() ? dept : "Not specified")
                            + (year != null && !year.isEmpty() ? " - " + year : "");
                    boolean auth = students.getBoolean("transport_authorized");
                    Attendance attendance = attendanceStore.find(
                            students.getString("student_id"), LocalDate.now());
                    String attendanceStatus = attendance == null
                            ? "<span class=\"badge badge-neutral\">Not Marked</span>"
                            : statusBadge(attendance.status);

                    rows.append("<tr>")
                            .append("<td>").append(count).append("</td>")
                            .append("<td><strong>").append(escapeHtml(students.getString("student_id"))).append("</strong></td>")
                            .append("<td>").append(escapeHtml(students.getString("name"))).append("</td>")
                            .append("<td>").append(escapeHtml(deptYear)).append("</td>")
                            .append("<td>").append(escapeHtml(defaultDisplay(busDisplay))).append("</td>")
                            .append("<td>").append(escapeHtml(defaultDisplay(students.getString("stop_name")))).append("</td>")
                            .append("<td>").append(attendanceStatus).append("</td>")
                            .append("<td>").append(auth
                                     ? "<span class=\"badge badge-success\">Authorized</span>"
                                     : "<span class=\"badge badge-warning\">Not Authorized</span>").append("</td>")
                            .append("</tr>");
                }
                if (count == 0) {
                    rows.append("<tr><td colspan=\"8\" class=\"registration-empty\">No students registered yet.</td></tr>");
                }
            }
            return rows.toString();
        }

        private Map<String, Integer> managementAttendanceCounts(AttendanceStore attendanceStore)
                throws SQLException {
            Map<String, Integer> counts = new LinkedHashMap<>();
            counts.put("present", 0);
            counts.put("absent", 0);
            counts.put("unmarked", 0);
            counts.put("authorized", 0);
            counts.put("unauthorized", 0);
            String sql = "SELECT student_id, transport_authorized FROM students";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement statement = con.prepareStatement(sql);
                 ResultSet students = statement.executeQuery()) {
                LocalDate today = LocalDate.now();
                while (students.next()) {
                    Attendance attendance = attendanceStore.find(
                            students.getString("student_id"), today);
                    if (attendance == null) {
                        counts.put("unmarked", counts.get("unmarked") + 1);
                    } else if ("PRESENT".equals(attendance.status)) {
                        counts.put("present", counts.get("present") + 1);
                    } else if ("ABSENT".equals(attendance.status)) {
                        counts.put("absent", counts.get("absent") + 1);
                    }
                    String authorizationCountKey = students.getBoolean("transport_authorized")
                            ? "authorized" : "unauthorized";
                    counts.put(authorizationCountKey, counts.get(authorizationCountKey) + 1);
                }
            }
            return counts;
        }

        private int getStudentCount() throws SQLException {
            String sql = "SELECT COUNT(*) FROM students";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement statement = con.prepareStatement(sql);
                 ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
            return 0;
        }

        private Map<String, Integer> getStudentCountsByBus() throws SQLException {
            Map<String, Integer> counts = new HashMap<>();
            String sql = "SELECT bus_id, COUNT(*) AS student_count "
                    + "FROM students GROUP BY bus_id";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement statement = con.prepareStatement(sql);
                 ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String busId = rs.getString("bus_id");
                    if (busId != null) {
                        counts.put(busId, rs.getInt("student_count"));
                    }
                }
            }
            return counts;
        }

        private String registrationMessage(String query) {
            String result = queryValue(query, "result");
            String error = queryValue(query, "error");
            if ("route-full".equals(error) || "route_full".equals(error)) {
                return "<p class=\"registration-message registration-error\" role=\"alert\">All buses on this route are full. Please contact Management.</p>";
            }
            if ("student-reallocated".equals(result)) {
                String reallocatedBus = queryValue(query, "assignedBus");
                return "<p class=\"registration-message registration-success\" role=\"status\">Requested bus was at full capacity. Student was automatically reallocated to available " + escapeHtml(reallocatedBus != null ? reallocatedBus : "bus") + " on this route corridor with original pickup stop preserved.</p>";
            }
            if ("student-added".equals(result)) {
                return "<p class=\"registration-message registration-success\" role=\"status\">Student registered successfully.</p>";
            }
            if ("password-reset".equals(result)) {
                String sid = queryValue(query, "studentId");
                String who = (sid != null && !sid.trim().isEmpty()) ? "for student (" + escapeHtml(sid) + ") " : "";
                return "<p class=\"registration-message registration-success\" role=\"status\">&#10004; Password " + who + "was successfully reset using secure PBKDF2 hash. The student can now log in with the new password.</p>";
            }
            if ("mismatch".equals(error)) {
                return "<p class=\"registration-message registration-error\" role=\"alert\">Password reset failed: The new password and confirmation password do not match. Please re-enter both carefully.</p>";
            }
            if ("invalid-password".equals(error)) {
                return "<p class=\"registration-message registration-error\" role=\"alert\">Password reset failed: New password must be between 8 and 128 characters.</p>";
            }
            if ("invalid-student".equals(error)) {
                return "<p class=\"registration-message registration-error\" role=\"alert\">Password reset failed: Please select or specify a valid registered student.</p>";
            }
            if ("student-not-found".equals(error)) {
                return "<p class=\"registration-message registration-error\" role=\"alert\">Password reset failed: The specified Student ID was not found in the database.</p>";
            }
            if ("duplicate".equals(error)) {
                return "<p class=\"registration-message registration-error\" role=\"alert\">That Student ID is already registered. No existing record was changed.</p>";
            }
            if ("invalid".equals(error)) {
                return "<p class=\"registration-message registration-error\" role=\"alert\">Check all required fields, choose a valid department, year, and bus, and use a password of at least 8 characters.</p>";
            }
            if ("database".equals(error)) {
                return "<p class=\"registration-message registration-error\" role=\"alert\">The student could not be saved to MySQL. Please try again or check the server log.</p>";
            }
            return "";
        }

        private static class DriverStudentEntry {
            String studentId;
            String name;
            String stopName;
            int stopOrder;
            String attendance;

            DriverStudentEntry(String studentId, String name, String stopName, int stopOrder, String attendance) {
                this.studentId = studentId;
                this.name = name;
                this.stopName = stopName;
                this.stopOrder = stopOrder;
                this.attendance = attendance;
            }
        }

        private String getStudentAttendanceStatus(Connection con, AttendanceStore store, String studentId, LocalDate date) {
            if (store != null) {
                Attendance att = store.find(studentId, date);
                if (att != null) {
                    if ("PRESENT".equalsIgnoreCase(att.status)) return "Present";
                    if ("ABSENT".equalsIgnoreCase(att.status)) return "Absent";
                }
            }
            if (con != null) {
                String sql = "SELECT status FROM attendance WHERE student_id = ? AND attendance_date = ?";
                try (PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setString(1, studentId);
                    ps.setDate(2, java.sql.Date.valueOf(date));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            String st = rs.getString("status");
                            if ("PRESENT".equalsIgnoreCase(st)) return "Present";
                            if ("ABSENT".equalsIgnoreCase(st)) return "Absent";
                        }
                    }
                } catch (SQLException ignored) {}
            }
            return "Not Marked";
        }

        private static int extractStopOrder(String stopCode) {
            if (stopCode == null) return 999;
            String norm = RoutePlanner.normalizeStopCode(stopCode);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Stop\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(norm);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (NumberFormatException ignored) {}
            }
            String lower = norm.toLowerCase();
            if (lower.contains("depot") || lower.contains("gandhipuram") || lower.contains("start")) {
                return 0;
            }
            if (lower.contains("campus") || lower.contains("college")) {
                return 100;
            }
            return 999;
        }

        private void handleStudentDashboardPage(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET method required for the student dashboard.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"student".equals(session.role)) {
                redirect(exchange, "/student-login.html");
                return;
            }
            try (Connection con = DatabaseConnection.getConnection()) {
                String studentName = "Student";
                String studentId = session.userId;
                String busId = "BUS-01";
                String pickupStop = "Stop 1";
                String department = "Information Technology (IT)";
                String academicYear = "II Year";
                boolean authorized = true;
                String photo = null;

                String query = "SELECT student_id, name, bus_id, stop_name, department, academic_year, transport_authorized, profile_photo "
                        + "FROM students WHERE student_id = ?";
                try (PreparedStatement ps = con.prepareStatement(query)) {
                    ps.setString(1, session.userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            studentName = defaultDisplay(rs.getString("name"));
                            String b = rs.getString("bus_id");
                            if (b != null && !b.trim().isEmpty()) busId = b.trim();
                            String s = rs.getString("stop_name");
                            if (s != null && !s.trim().isEmpty()) pickupStop = s.trim();
                            String d = rs.getString("department");
                            if (d != null && !d.trim().isEmpty()) department = d.trim();
                            String y = rs.getString("academic_year");
                            if (y != null && !y.trim().isEmpty()) academicYear = y.trim();
                            authorized = rs.getBoolean("transport_authorized");
                            photo = rs.getString("profile_photo");
                        }
                    }
                }

                LocalDate today = LocalDate.now();
                String attendanceStatus = getStudentAttendanceStatus(con, attendanceStore, session.userId, today);

                // Fetch or calculate student's transport fee details
                TransportFeeService.StudentFeeInfo feeInfo = TransportFeeService.getStudentFee(con, session.userId);
                if (feeInfo == null) {
                    feeInfo = TransportFeeService.calculateAndSaveStudentFee(con, session.userId);
                }
                String feeFormatted = feeInfo != null ? String.format(Locale.ROOT, "₹%,.0f", feeInfo.feeAmount) : "₹25,000";
                String distanceFormatted = feeInfo != null ? String.format(Locale.ROOT, "%.2f km", feeInfo.distanceKm) : "N/A";
                String paymentStatus = (feeInfo != null && feeInfo.paymentStatus != null) ? feeInfo.paymentStatus : "Paid";

                // Build "My Pickup List" - students sharing this student's pickup stop
                String normMyStop = RoutePlanner.normalizeStopCode(pickupStop);
                StringBuilder pickupListRows = new StringBuilder();
                int stopStudentCount = 0;

                String peerSql = "SELECT student_id, name, stop_name FROM students WHERE stop_name IS NOT NULL AND TRIM(stop_name) <> '' ORDER BY student_id";
                try (PreparedStatement peerPs = con.prepareStatement(peerSql);
                     ResultSet peerRs = peerPs.executeQuery()) {
                    while (peerRs.next()) {
                        String peerStop = peerRs.getString("stop_name");
                        if (RoutePlanner.normalizeStopCode(peerStop).equalsIgnoreCase(normMyStop)) {
                            stopStudentCount++;
                            String pId = peerRs.getString("student_id");
                            String pName = defaultDisplay(peerRs.getString("name"));
                            String pDisplayStop = RoutePlanner.normalizeStopCode(peerStop);
                            String pAtt = getStudentAttendanceStatus(con, attendanceStore, pId, today);

                            String rowClass = "row-unmarked";
                            String badgeHtml = "<span class=\"badge badge-neutral\">Not Marked</span>";
                            if ("Present".equalsIgnoreCase(pAtt)) {
                                rowClass = "row-present";
                                badgeHtml = "<span class=\"badge badge-success\" style=\"font-weight:700;\">&#10004; Present</span>";
                            } else if ("Absent".equalsIgnoreCase(pAtt)) {
                                rowClass = "row-absent";
                                badgeHtml = "<span class=\"badge badge-danger\">Absent</span>";
                            }

                            boolean isSelf = session.userId.equalsIgnoreCase(pId);
                            String displayName = escapeHtml(pName) + (isSelf ? " <span style=\"color:var(--navy-primary);font-weight:700;font-size:0.85rem;\">(You)</span>" : "");

                            pickupListRows.append("<tr class=\"").append(rowClass).append("\">")
                                    .append("<td><strong>").append(escapeHtml(pId)).append("</strong></td>")
                                    .append("<td>").append(displayName).append("</td>")
                                    .append("<td>").append(escapeHtml(pDisplayStop)).append("</td>")
                                    .append("<td>").append(badgeHtml).append("</td>")
                                    .append("</tr>\n");
                        }
                    }
                }

                if (stopStudentCount == 0) {
                    pickupListRows.append("<tr><td colspan=\"4\" class=\"registration-empty\">No students are currently assigned to this pickup stop.</td></tr>\n");
                }

                String html = new String(
                        Files.readAllBytes(new File("student-dashboard.html").toPath()),
                        StandardCharsets.UTF_8
                );

                html = html.replace("{{studentName}}", escapeHtml(studentName))
                        .replace("{{studentId}}", escapeHtml(studentId))
                        .replace("{{studentBus}}", escapeHtml(busId))
                        .replace("{{studentPickupStop}}", escapeHtml(pickupStop))
                        .replace("{{studentDepartment}}", escapeHtml(department))
                        .replace("{{studentAcademicYear}}", escapeHtml(academicYear))
                        .replace("{{studentAttendanceStatus}}", escapeHtml(attendanceStatus))
                        .replace("{{myPickupListRows}}", pickupListRows.toString())
                        .replace("{{studentStopDistance}}", escapeHtml(distanceFormatted))
                        .replace("{{studentTransportFee}}", escapeHtml(feeFormatted))
                        .replace("{{studentFeePaymentStatus}}", escapeHtml(paymentStatus))
                        .replace("{{studentPhotoUrl}}", resolvePhotoUrl(photo));

                sendHtml(exchange, html);
            } catch (SQLException exception) {
                exception.printStackTrace();
                sendError(exchange, 500, "Could not load student dashboard from the database.");
            }
        }

        private void handleStudentBusPage(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET method required for the student bus page.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"student".equals(session.role)) {
                redirect(exchange, "/student-login.html");
                return;
            }

            try {
                String studentBusId = null;
                String studentStop = null;

                // Step 1: Query the currently logged-in student's assigned bus and stop
                String studentSql = "SELECT bus_id, stop_name FROM students WHERE student_id = ?";
                try (Connection con = DatabaseConnection.getConnection();
                     PreparedStatement ps = con.prepareStatement(studentSql)) {
                    ps.setString(1, session.userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            studentBusId = rs.getString("bus_id");
                            studentStop = rs.getString("stop_name");
                        } else {
                            sendError(exchange, 404, "Student record not found in database.");
                            return;
                        }
                    }
                }

                // Default displays if unassigned or missing
                String busNumber = "Not assigned";
                String busIdDisplay = "Not assigned";
                String routeName = "Not available";
                String busCapacity = "Not available";
                String pickupStop = (studentStop != null && !studentStop.trim().isEmpty()) ? studentStop.trim() : "Not assigned";
                String busStatus = "Not Assigned";
                String busBadgeClass = "badge-warning";

                String driverId = "Not assigned";
                String driverName = "Driver not assigned yet";
                String driverPhone = "Not available";
                String driverEmail = "Not available";
                String driverLicenseNumber = "Not available";
                String driverLicenseExpiry = "Not available";
                String driverLicenseClass = "Not available";
                String driverPhotoUrl = "/uploads/profiles/default-avatar.svg";
                String driverStatus = "Driver not assigned yet";
                String driverBadgeClass = "badge-warning";
                String driverBusDisplay = "Not assigned";

                if (studentBusId != null && !studentBusId.trim().isEmpty()) {
                    studentBusId = studentBusId.trim();
                    busIdDisplay = studentBusId;

                    // Step 2: Query bus details from `buses` table
                    String busSql = "SELECT bus_number, capacity, route_name FROM buses WHERE bus_id = ?";
                    try (Connection con = DatabaseConnection.getConnection();
                         PreparedStatement ps = con.prepareStatement(busSql)) {
                        ps.setString(1, studentBusId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                String bn = rs.getString("bus_number");
                                if (bn != null && !bn.trim().isEmpty()) busNumber = bn.trim();
                                int cap = rs.getInt("capacity");
                                if (cap > 0) busCapacity = cap + " seats";
                                String rn = rs.getString("route_name");
                                if (rn != null && !rn.trim().isEmpty()) routeName = rn.trim();
                                busStatus = "Operational";
                                busBadgeClass = "badge-success";
                            } else {
                                busNumber = studentBusId;
                                busStatus = "Operational";
                                busBadgeClass = "badge-success";
                            }
                        }
                    }

                    driverBusDisplay = busNumber;

                    // Step 3: Query driver assigned to this bus from `drivers` table
                    String driverSql = "SELECT driver_id, name, phone_number, email, license_number, license_expiry, license_class, profile_photo "
                            + "FROM drivers WHERE bus_id = ? LIMIT 1";
                    try (Connection con = DatabaseConnection.getConnection();
                         PreparedStatement ps = con.prepareStatement(driverSql)) {
                        ps.setString(1, studentBusId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                String did = rs.getString("driver_id");
                                if (did != null && !did.trim().isEmpty()) driverId = did.trim();

                                String dname = rs.getString("name");
                                if (dname != null && !dname.trim().isEmpty()) {
                                    driverName = dname.trim();
                                } else {
                                    driverName = "Not available";
                                }

                                String phone = rs.getString("phone_number");
                                if (phone != null && !phone.trim().isEmpty()) driverPhone = phone.trim();

                                String email = rs.getString("email");
                                if (email != null && !email.trim().isEmpty()) driverEmail = email.trim();

                                String licNum = rs.getString("license_number");
                                if (licNum != null && !licNum.trim().isEmpty()) driverLicenseNumber = licNum.trim();

                                String licExp = rs.getString("license_expiry");
                                if (licExp != null && !licExp.trim().isEmpty()) driverLicenseExpiry = licExp.trim();

                                String licCls = rs.getString("license_class");
                                if (licCls != null && !licCls.trim().isEmpty()) driverLicenseClass = licCls.trim();

                                driverPhotoUrl = resolvePhotoUrl(rs.getString("profile_photo"));
                                driverStatus = "Active Duty";
                                driverBadgeClass = "badge-success";
                            }
                        }
                    }
                }

                File templateFile = new File("student-bus.html");
                if (!templateFile.exists()) {
                    sendError(exchange, 404, "student-bus.html template not found.");
                    return;
                }

                String html = new String(Files.readAllBytes(templateFile.toPath()), StandardCharsets.UTF_8)
                        .replace("{{busNumber}}", escapeHtml(busNumber))
                        .replace("{{busId}}", escapeHtml(busIdDisplay))
                        .replace("{{routeName}}", escapeHtml(routeName))
                        .replace("{{busCapacity}}", escapeHtml(busCapacity))
                        .replace("{{pickupStop}}", escapeHtml(pickupStop))
                        .replace("{{busStatus}}", escapeHtml(busStatus))
                        .replace("{{busBadgeClass}}", escapeHtml(busBadgeClass))
                        .replace("{{driverId}}", escapeHtml(driverId))
                        .replace("{{driverName}}", escapeHtml(driverName))
                        .replace("{{driverPhone}}", escapeHtml(driverPhone))
                        .replace("{{driverEmail}}", escapeHtml(driverEmail))
                        .replace("{{driverBusDisplay}}", escapeHtml(driverBusDisplay))
                        .replace("{{driverLicenseNumber}}", escapeHtml(driverLicenseNumber))
                        .replace("{{driverLicenseExpiry}}", escapeHtml(driverLicenseExpiry))
                        .replace("{{driverLicenseClass}}", escapeHtml(driverLicenseClass))
                        .replace("{{driverPhotoUrl}}", escapeHtml(driverPhotoUrl))
                        .replace("{{driverStatus}}", escapeHtml(driverStatus))
                        .replace("{{driverBadgeClass}}", escapeHtml(driverBadgeClass));

                sendHtml(exchange, html);
            } catch (SQLException e) {
                e.printStackTrace();
                sendError(exchange, 500, "Database error loading student bus details.");
            }
        }

        private void handleDriverDashboardPage(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET method required for the driver dashboard.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"driver".equals(session.role)) {
                sendError(exchange, 403, "Driver login required.");
                return;
            }
            try {
                ensureDriverPhoneColumn();
                String html = new String(
                        Files.readAllBytes(new File("driver-dashboard.html").toPath()),
                        StandardCharsets.UTF_8
                );
                String msg = driverRegistrationMessage(exchange.getRequestURI().getQuery());
                String busOptions = registeredBusOptions();
                html = renderDriverDashboard(html, session, attendanceStore)
                        .replace("{{driverRegistrationMessage}}", msg)
                        .replace("{{registrationMessage}}", msg)
                        .replace("<!-- DRIVER_REGISTRATION_MESSAGE -->", msg)
                        .replace("{{driverBusOptions}}", busOptions)
                        .replace("{{busOptions}}", busOptions)
                        .replace("<!-- DRIVER_BUS_OPTIONS -->", busOptions);
                sendHtml(exchange, html);
            } catch (SQLException exception) {
                exception.printStackTrace();
                sendError(exchange, 500, "Could not load bus options for driver registration.");
            }
        }

        private String renderDriverDashboard(
                String html, UserSession session, AttendanceStore attendanceStore)
                throws SQLException {
            String driverName = "Driver";
            String busId = null;
            String busDisplay = "Not assigned";
            String driverPhoto = null;
            String driverSql = "SELECT d.name, d.bus_id, d.profile_photo, b.bus_number "
                    + "FROM drivers d LEFT JOIN buses b ON b.bus_id = d.bus_id "
                    + "WHERE d.driver_id = ?";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement statement = con.prepareStatement(driverSql)) {
                statement.setString(1, session.userId);
                try (ResultSet driver = statement.executeQuery()) {
                    if (driver.next()) {
                        driverName = defaultDisplay(driver.getString("name"));
                        busId = driver.getString("bus_id");
                        driverPhoto = driver.getString("profile_photo");
                        String busNumber = driver.getString("bus_number");
                        if (busId != null && !busId.trim().isEmpty()) {
                            busDisplay = defaultDisplay(busNumber == null ? busId : busNumber);
                        }
                    }
                }
            }

            List<DriverStudentEntry> studentList = new ArrayList<>();
            LocalDate today = LocalDate.now();
            int totalStudents = 0;
            int presentCount = 0;
            int absentCount = 0;

            if (busId != null && !busId.trim().isEmpty()) {
                String studentsSql = "SELECT s.student_id, s.name, s.stop_name "
                        + "FROM students s "
                        + "WHERE s.bus_id = ? AND s.stop_name IS NOT NULL AND TRIM(s.stop_name) <> '' "
                        + "ORDER BY s.student_id";
                try (Connection con = DatabaseConnection.getConnection();
                     PreparedStatement statement = con.prepareStatement(studentsSql)) {
                    statement.setString(1, busId);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            String sId = rs.getString("student_id");
                            String name = defaultDisplay(rs.getString("name"));
                            String rawStop = rs.getString("stop_name");
                            String normStop = RoutePlanner.normalizeStopCode(rawStop);
                            int order = extractStopOrder(normStop);
                            String attStatus = getStudentAttendanceStatus(con, attendanceStore, sId, today);
                            studentList.add(new DriverStudentEntry(sId, name, normStop, order, attStatus));
                        }
                    }
                }
            }

            // Sort by corridor sequence (Stop 1 to Stop 10), then stop name, then student ID
            studentList.sort((a, b) -> {
                if (a.stopOrder != b.stopOrder) {
                    return Integer.compare(a.stopOrder, b.stopOrder);
                }
                int stopCmp = a.stopName.compareToIgnoreCase(b.stopName);
                if (stopCmp != 0) return stopCmp;
                return a.studentId.compareToIgnoreCase(b.studentId);
            });

            StringBuilder todayPickupRows = new StringBuilder();
            for (DriverStudentEntry entry : studentList) {
                totalStudents++;
                String rowClass = "row-unmarked";
                String badgeHtml = "<span class=\"badge badge-neutral\">Not Marked</span>";
                if ("Present".equalsIgnoreCase(entry.attendance)) {
                    presentCount++;
                    rowClass = "row-present";
                    badgeHtml = "<span class=\"badge badge-success\" style=\"font-weight:700;\">&#10004; Present</span>";
                } else if ("Absent".equalsIgnoreCase(entry.attendance)) {
                    absentCount++;
                    rowClass = "row-absent";
                    badgeHtml = "<span class=\"badge badge-danger\">Absent</span>";
                }

                todayPickupRows.append("<tr class=\"").append(rowClass).append("\">")
                        .append("<td><strong>").append(escapeHtml(entry.studentId)).append("</strong></td>")
                        .append("<td>").append(escapeHtml(entry.name)).append("</td>")
                        .append("<td>").append(escapeHtml(entry.stopName)).append("</td>")
                        .append("<td>").append(badgeHtml).append("</td>")
                        .append("</tr>\n");
            }

            if (studentList.isEmpty()) {
                todayPickupRows.append("<tr><td colspan=\"4\" class=\"registration-empty\">No students are assigned to this bus yet.</td></tr>\n");
            }

            int unmarkedCount = totalStudents - presentCount - absentCount;
            String routeStatus = (busId == null || busId.trim().isEmpty()) ? "No bus assigned" : "Corridor Route Active";

            return html.replace("{{driverName}}", escapeHtml(driverName))
                    .replace("{{driverId}}", escapeHtml(session.userId))
                    .replace("{{driverBus}}", escapeHtml(busDisplay))
                    .replace("{{driverPhotoUrl}}", resolvePhotoUrl(driverPhoto))
                    .replace("{{assignedStudentCount}}", String.valueOf(totalStudents))
                    .replace("{{presentStudentCount}}", String.valueOf(presentCount))
                    .replace("{{absentStudentCount}}", String.valueOf(absentCount))
                    .replace("{{unmarkedStudentCount}}", String.valueOf(unmarkedCount))
                    .replace("{{routeStatus}}", escapeHtml(routeStatus))
                    .replace("{{driverTodayPickupListRows}}", todayPickupRows.toString());
        }

        private Map<String, String> attendanceStatusesForToday(AttendanceStore attendanceStore) {
            Map<String, String> statuses = new HashMap<>();
            LocalDate today = LocalDate.now();
            for (Attendance attendance : attendanceStore.all()) {
                if (today.equals(attendance.date)) {
                    statuses.put(attendance.studentId, attendance.status);
                }
            }
            return statuses;
        }

        private String renderDriverRouteStops(
                RoutePlanner.BusRoute route, Map<String, int[]> pickupCounts) {
            StringBuilder rows = new StringBuilder();
            int order = 0;
            List<RoutePlanner.Stop> requiredStops = route.orderedStops;
            for (RoutePlanner.Stop stop : requiredStops) {
                int[] counts = pickupCounts.get(stop.name);
                String summary = counts == null ? "Scheduled pickup"
                        : counts[1] + " present, " + counts[2]
                                + " absent, " + counts[3] + " not marked";
                rows.append("<li class=\"driver-pickup-row route-required\"><strong>");
                if (route.available) {
                    rows.append(++order).append(". ");
                }
                rows.append(escapeHtml(stop.name)).append(" (").append(escapeHtml(stop.label)).append(")</strong><span>")
                        .append(escapeHtml(summary)).append(" &bull; Required</span></li>\n");
            }
            if (rows.length() == 0) {
                rows.append("<li class=\"registration-empty\">No required pickup stops on this route today.</li>\n");
            }
            if (route.available) {
                rows.append("<li class=\"driver-pickup-row route-destination\"><strong>")
                        .append("Final Destination: ").append(RoutePlanner.COLLEGE_DESTINATION_NAME)
                        .append("</strong><span>College Campus Arrival</span></li>\n");
            }
            return rows.toString();
        }

        private String renderRouteMetrics(RoutePlanner.BusRoute route) {
            if (route == null || !route.available) {
                return "<span class=\"driver-route-metric\">Distance and travel time unavailable.</span>";
            }
            String distance = String.format(java.util.Locale.ROOT, "%.1f km",
                    route.distanceMeters / 1000.0);
            long minutes = Math.round(route.durationSeconds / 60.0);
            return "<span class=\"driver-route-metric\">"
                    + escapeHtml(distance) + " road distance &bull; "
                    + minutes + " min estimated</span>";
        }

        private String renderMapRouteLink(RoutePlanner.BusRoute route) {
            if (route == null || !route.available || route.mapUrl == null) {
                return "<span class=\"map-link-unavailable\">Map route unavailable until coordinates and routing data are configured.</span>";
            }
            return "<a class=\"btn btn-primary\" target=\"_blank\" rel=\"noopener noreferrer\" href=\""
                    + escapeHtml(route.mapUrl) + "\">Open route in OpenStreetMap</a>";
        }

        private void handleManagementRoutesPage(
                HttpExchange exchange, AttendanceStore attendanceStore) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET method required for route management.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }
            try {
                String query = exchange.getRequestURI().getQuery();
                String busId = queryValue(query, "busId");
                if (busId == null || busId.trim().isEmpty()) {
                    busId = queryValue(query, "bus");
                }
                if (busId == null || busId.trim().isEmpty() || "ALL".equalsIgnoreCase(busId)) {
                    busId = "BUS-01";
                } else {
                    busId = RoutePlanner.normalizeBusId(busId);
                }

                String dateStr = queryValue(query, "date");
                LocalDate date = LocalDate.now();
                if (dateStr != null && !dateStr.trim().isEmpty()) {
                    try {
                        date = LocalDate.parse(dateStr.trim());
                    } catch (Exception ignored) {}
                }

                String result = queryValue(query, "result");
                String error = queryValue(query, "error");
                String warning = queryValue(query, "warning");
                String stopCodeParam = queryValue(query, "stopCode");

                // Calculate real road route for this bus and date directly from MySQL attendance for the selected date
                RoutePlanner.RealRouteResult realRoute = ROUTE_PLANNER.recalculateAndSaveSharedRoute(busId, date);

                // Build status message
                StringBuilder statusMsg = new StringBuilder();
                if ("1".equals(queryValue(query, "recalculated"))) {
                    statusMsg.append("<div class=\"alert-callout alert-success\" style=\"margin-bottom:16px;\"><div class=\"alert-icon\">&#10004;</div><div><div class=\"alert-title\">Route Recalculated</div><div class=\"alert-body\">Fleet bus route successfully recalculated using latest MySQL attendance records for ").append(date).append(". No attendance data was modified.</div></div></div>");
                } else if ("stop_added".equals(result)) {
                    statusMsg.append("<div class=\"alert-callout alert-success\" style=\"margin-bottom:16px;\"><div class=\"alert-icon\">&#10004;</div><div><div class=\"alert-title\">Stop Added Successfully</div><div class=\"alert-body\">New pickup stop and verified coordinates saved to MySQL. Route recomputed.</div></div></div>");
                } else if ("stop_updated".equals(result)) {
                    statusMsg.append("<div class=\"alert-callout alert-success\" style=\"margin-bottom:16px;\"><div class=\"alert-icon\">&#10004;</div><div><div class=\"alert-title\">Stop Updated</div><div class=\"alert-body\">Pickup stop coordinates and location details successfully updated.</div></div></div>");
                } else if ("stop_deleted".equals(result)) {
                    statusMsg.append("<div class=\"alert-callout alert-success\" style=\"margin-bottom:16px;\"><div class=\"alert-icon\">&#10004;</div><div><div class=\"alert-title\">Stop Deleted</div><div class=\"alert-body\">Pickup stop successfully removed. Daily route recalculated.</div></div></div>");
                } else if ("reassigned_deleted".equals(result)) {
                    statusMsg.append("<div class=\"alert-callout alert-success\" style=\"margin-bottom:16px;\"><div class=\"alert-icon\">&#10004;</div><div><div class=\"alert-title\">Safe Reassignment Complete</div><div class=\"alert-body\">Assigned students were safely reassigned to the replacement stop, and the previous stop was removed.</div></div></div>");
                } else if ("preloaded".equals(result)) {
                    statusMsg.append("<div class=\"alert-callout alert-success\" style=\"margin-bottom:16px;\"><div class=\"alert-icon\">&#10004;</div><div><div class=\"alert-title\">Remaining Stops Preloaded</div><div class=\"alert-body\">Stops 3 to 10 successfully created with verified Coimbatore road coordinates.</div></div></div>");
                } else if ("override_toggled".equals(result)) {
                    statusMsg.append("<div class=\"alert-callout alert-success\" style=\"margin-bottom:16px;\"><div class=\"alert-icon\">&#10004;</div><div><div class=\"alert-title\">Activation Toggled</div><div class=\"alert-body\">Stop activation status updated for daily pickup route.</div></div></div>");
                } else if ("terminals_updated".equals(result)) {
                    statusMsg.append("<div class=\"alert-callout alert-success\" style=\"margin-bottom:16px;\"><div class=\"alert-icon\">&#10004;</div><div><div class=\"alert-title\">Terminal Locations Updated</div><div class=\"alert-body\">Starting Point and College Destination coordinates saved to MySQL. Real road route recalculated.</div></div></div>");
                } else if ("student_reassigned".equals(result)) {
                    statusMsg.append("<div class=\"alert-callout alert-success\" style=\"margin-bottom:16px;\"><div class=\"alert-icon\">&#10004;</div><div><div class=\"alert-title\">Student Reassigned</div><div class=\"alert-body\">Student pickup stop assignment updated in MySQL. Daily route recomputed.</div></div></div>");
                } else if ("reassign_failed".equals(error)) {
                    statusMsg.append("<div class=\"alert-callout alert-warning\" style=\"border-left-color:var(--red-danger);margin-bottom:16px;\"><div class=\"alert-icon\" style=\"color:var(--red-danger);\">&#9888;</div><div><div class=\"alert-title\" style=\"color:var(--red-danger);\">Reassignment Failed</div><div class=\"alert-body\">Could not reassign student. Please select a valid student and stop.</div></div></div>");
                } else if ("invalid_coords".equals(error)) {
                    statusMsg.append("<div class=\"alert-callout alert-warning\" style=\"border-left-color:var(--red-danger);margin-bottom:16px;\"><div class=\"alert-icon\" style=\"color:var(--red-danger);\">&#9888;</div><div><div class=\"alert-title\" style=\"color:var(--red-danger);\">Invalid Coordinates</div><div class=\"alert-body\">Latitude must be between -90 and 90, Longitude must be between -180 and 180.</div></div></div>");
                } else if ("missing_fields".equals(error)) {
                    statusMsg.append("<div class=\"alert-callout alert-warning\" style=\"border-left-color:var(--red-danger);margin-bottom:16px;\"><div class=\"alert-icon\" style=\"color:var(--red-danger);\">&#9888;</div><div><div class=\"alert-title\" style=\"color:var(--red-danger);\">Missing Required Fields</div><div class=\"alert-body\">Please provide both stop code and location name.</div></div></div>");
                }

                // Deletion Warning & Safe Reassignment
                StringBuilder deletionWarn = new StringBuilder();
                if ("has_students".equals(warning) && stopCodeParam != null) {
                    List<String> studentNames = ROUTE_PLANNER.getAssignedStudentNames(stopCodeParam);
                    deletionWarn.append("<div class=\"alert-callout alert-warning\" style=\"border-left-color:var(--red-danger);margin-bottom:20px;\">\n")
                            .append("  <div class=\"alert-icon\" style=\"color:var(--red-danger);font-size:1.4rem;\">&#9888;</div>\n")
                            .append("  <div style=\"width:100%;\">\n")
                            .append("    <div class=\"alert-title\" style=\"color:var(--red-danger);font-size:1.1rem;\">Cannot Delete ").append(escapeHtml(stopCodeParam)).append(": Active Student Assignments</div>\n")
                            .append("    <div class=\"alert-body\">\n")
                            .append("      <p style=\"margin-bottom:8px;\"><strong>").append(escapeHtml(stopCodeParam)).append("</strong> currently has <strong>")
                            .append(studentNames.size()).append(" active student assignment(s):</strong> ")
                            .append(escapeHtml(String.join(", ", studentNames))).append(".<br>")
                            .append("      To protect passenger safety and records integrity, select a replacement pickup stop below to reassign these students before removing this stop:</p>\n")
                            .append("      <form action=\"/reassign-and-delete-stop\" method=\"POST\" style=\"display:flex;flex-wrap:wrap;gap:12px;align-items:center;margin-top:10px;\">\n")
                            .append("        <input type=\"hidden\" name=\"stopCode\" value=\"").append(escapeHtml(stopCodeParam)).append("\">\n")
                            .append("        <label for=\"replacementSelect\" style=\"font-weight:600;\">Reassign Students To:</label>\n")
                            .append("        <select id=\"replacementSelect\" name=\"replacementStopCode\" required style=\"padding:8px 14px;border-radius:var(--radius-sm);border:1px solid var(--border-light);background:var(--bg-card);color:var(--text-body);\">\n");
                    for (RoutePlanner.ManagedStop ms : realRoute.allStops) {
                        if (!ms.stopCode.equalsIgnoreCase(stopCodeParam)) {
                            deletionWarn.append("          <option value=\"").append(escapeHtml(ms.stopCode)).append("\">")
                                    .append(escapeHtml(ms.stopCode)).append(" (").append(escapeHtml(ms.stopName)).append(")</option>\n");
                        }
                    }
                    deletionWarn.append("        </select>\n")
                            .append("        <button type=\"submit\" class=\"btn btn-danger\" style=\"padding:8px 18px;\">Safe Reassign &amp; Delete ").append(escapeHtml(stopCodeParam)).append("</button>\n")
                            .append("        <a href=\"/management-routes.html\" class=\"btn btn-secondary\" style=\"padding:8px 14px;\">Cancel</a>\n")
                            .append("      </form>\n")
                            .append("    </div>\n")
                            .append("  </div>\n")
                            .append("</div>\n");
                }

                // Interactive Leaflet map HTML
                String mapHtml = ROUTE_PLANNER.renderLeafletMapHtml(realRoute, "management", null);

                StringBuilder terminalHtml = new StringBuilder();

                // Stop Management Rows
                StringBuilder stopRows = new StringBuilder();
                StringBuilder stopOptions = new StringBuilder();
                for (RoutePlanner.ManagedStop st : realRoute.allStops) {
                    boolean isActive = st.status == RoutePlanner.StopStatus.REQUIRED;
                    boolean isSkipped = st.status == RoutePlanner.StopStatus.SKIPPED;
                    String statusBadge = isActive
                            ? "<span class=\"badge badge-success\">ACTIVE ON ROUTE</span>"
                            : (isSkipped ? "<span class=\"badge badge-warning\" style=\"background:#ef4444;color:#fff;\">SKIPPED</span>"
                            : "<span class=\"badge badge-neutral\">EXCLUDED (0 Students)</span>");

                    String studentsText = st.assignedStudentNames.isEmpty()
                            ? "<span class=\"text-muted\">0 Assigned</span>"
                            : "<span class=\"badge badge-info\" title=\"" + escapeHtml(String.join(", ", st.assignedStudentNames)) + "\">"
                            + st.assignedStudentNames.size() + " Student" + (st.assignedStudentNames.size() > 1 ? "s" : "") + "</span>";

                    stopRows.append("<tr>\n")
                            .append("  <td><strong>").append(escapeHtml(st.stopCode)).append("</strong></td>\n")
                            .append("  <td>").append(escapeHtml(st.stopName)).append("</td>\n")
                            .append("  <td><code>").append(String.format(Locale.ROOT, "%.6f, %.6f", st.latitude, st.longitude)).append("</code></td>\n")
                            .append("  <td><span class=\"badge badge-primary\">").append(escapeHtml(st.busId)).append("</span></td>\n")
                            .append("  <td>").append(studentsText).append("</td>\n")
                            .append("  <td>").append(statusBadge).append("<br><small style=\"color:var(--text-muted);\">").append(escapeHtml(st.statusReason)).append("</small></td>\n")
                            .append("  <td style=\"white-space:nowrap;\">\n")
                            .append("    <form action=\"/delete-stop\" method=\"POST\" style=\"display:inline-block;margin:0 4px 0 0;\">\n")
                            .append("      <input type=\"hidden\" name=\"stopCode\" value=\"").append(escapeHtml(st.stopCode)).append("\">\n")
                            .append("      <button type=\"submit\" class=\"btn btn-danger\" style=\"padding:4px 10px;font-size:0.8rem;\" onclick=\"return confirm('Delete ").append(escapeHtml(st.stopCode)).append("?');\">Delete</button>\n")
                            .append("    </form>\n");

                    if (st.assignedStudentNames.isEmpty()) {
                        stopRows.append("    <form action=\"/toggle-stop-activation\" method=\"POST\" style=\"display:inline-block;margin:0;\">\n")
                                .append("      <input type=\"hidden\" name=\"stopCode\" value=\"").append(escapeHtml(st.stopCode)).append("\">\n")
                                .append("      <button type=\"submit\" class=\"btn btn-secondary\" style=\"padding:4px 10px;font-size:0.8rem;\">")
                                .append(st.isActiveOverride ? "Deactivate" : "Activate").append("</button>\n")
                                .append("    </form>\n");
                    }

                    stopRows.append("  </td>\n</tr>\n");

                    stopOptions.append("<option value=\"").append(escapeHtml(st.stopCode)).append("\">")
                            .append(escapeHtml(st.stopCode)).append(" &mdash; ").append(escapeHtml(st.stopName))
                            .append(" (").append(String.format(Locale.ROOT, "%.4f, %.4f", st.latitude, st.longitude)).append(")</option>\n");
                }

                // Preload button (if stops < 10)
                String preloadBtn = realRoute.allStops.size() < 10
                        ? "<form action=\"/preload-stops\" method=\"POST\" style=\"display:inline-block;margin:0;\"><button type=\"submit\" class=\"btn btn-secondary\" style=\"padding:6px 14px;font-size:0.85rem;\">+ Preload Remaining Stops (Up to 10)</button></form>"
                        : "";

                // Student Assignments Rows & Options
                StringBuilder studentAssignmentRows = new StringBuilder();
                StringBuilder studentOptions = new StringBuilder();
                String studentListSql = "SELECT s.student_id, s.name, s.department, s.academic_year, s.stop_name, "
                        + "COALESCE(a.status, 'Unmarked') as att_status "
                        + "FROM students s LEFT JOIN attendance a ON s.student_id = a.student_id AND a.attendance_date = ? "
                        + "WHERE s.bus_id = ? ORDER BY s.student_id";
                try (Connection con = DatabaseConnection.getConnection();
                     PreparedStatement ps = con.prepareStatement(studentListSql)) {
                    ps.setDate(1, java.sql.Date.valueOf(date));
                    ps.setString(2, busId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            String sid = rs.getString("student_id");
                            String sname = rs.getString("name");
                            String sdept = rs.getString("department");
                            String syear = rs.getString("academic_year");
                            String sstop = rs.getString("stop_name");
                            String sstat = rs.getString("att_status");

                            String attPill = "Present".equalsIgnoreCase(sstat)
                                    ? "<span class=\"badge badge-success\">Present</span>"
                                    : ("Absent".equalsIgnoreCase(sstat)
                                    ? "<span class=\"badge badge-warning\" style=\"background:#ef4444;color:#fff;\">Absent</span>"
                                    : "<span class=\"badge badge-neutral\">Unmarked</span>");

                            studentAssignmentRows.append("<tr>\n")
                                    .append("  <td><strong>").append(escapeHtml(sid)).append("</strong></td>\n")
                                    .append("  <td>").append(escapeHtml(sname)).append("</td>\n")
                                    .append("  <td>").append(escapeHtml(sdept != null ? sdept : "Engineering"))
                                    .append(" <small style=\"color:var(--text-muted);\">(").append(escapeHtml(syear != null ? syear : ""))
                                    .append(")</small></td>\n")
                                    .append("  <td><span class=\"badge badge-primary\">").append(escapeHtml(RoutePlanner.normalizeStopCode(sstop))).append("</span></td>\n")
                                    .append("  <td>").append(attPill).append("</td>\n")
                                    .append("</tr>\n");

                            studentOptions.append("<option value=\"").append(escapeHtml(sid)).append("\">")
                                    .append(escapeHtml(sid)).append(" &mdash; ").append(escapeHtml(sname))
                                    .append(" (Current: ").append(escapeHtml(RoutePlanner.normalizeStopCode(sstop))).append(")</option>\n");
                        }
                    }
                }

                // Daily Attendance Breakdown Rows
                StringBuilder attBreakdown = new StringBuilder();
                for (RoutePlanner.ManagedStop st : realRoute.allStops) {
                    boolean isActive = st.status == RoutePlanner.StopStatus.REQUIRED;
                    String statusPill = isActive
                            ? "<span class=\"badge badge-success\">ACTIVE</span>"
                            : "<span class=\"badge badge-warning\" style=\"background:#ef4444;color:#fff;\">SKIPPED</span>";

                    attBreakdown.append("<tr>\n")
                            .append("  <td><strong>").append(escapeHtml(st.stopCode)).append("</strong> (").append(escapeHtml(st.stopName)).append(")</td>\n")
                            .append("  <td>").append(st.assignedStudentNames.size()).append("</td>\n")
                            .append("  <td><span style=\"color:var(--green-success);font-weight:700;\">").append(st.presentCount).append("</span></td>\n")
                            .append("  <td><span style=\"color:var(--red-danger);font-weight:700;\">").append(st.absentCount).append("</span></td>\n")
                            .append("  <td><span style=\"color:#f59e0b;font-weight:700;\">").append(st.unconfirmedCount).append("</span></td>\n")
                            .append("  <td>").append(statusPill).append("</td>\n")
                            .append("  <td>").append(escapeHtml(st.statusReason)).append("</td>\n")
                            .append("</tr>\n");
                }

                String distanceDisplay = String.format(Locale.ROOT, "%.1f km", realRoute.distanceMeters / 1000.0);
                String durationDisplay = String.valueOf(Math.round(realRoute.durationSeconds / 60.0));
                String lastUpdatedDisplay = realRoute.lastCalculatedAt != null
                        ? realRoute.lastCalculatedAt.toString().substring(0, 19).replace("T", " ")
                        : "Today at " + date;

                String template = new String(Files.readAllBytes(new File("management-routes.html").toPath()), StandardCharsets.UTF_8);
                String rendered = template.replace("{{statusMessage}}", statusMsg.toString())
                        .replace("{{deletionWarning}}", deletionWarn.toString())
                        .replace("{{busId}}", escapeHtml(busId))
                        .replace("{{busIdDisplay}}", escapeHtml(busId))
                        .replace("{{selectedDate}}", date.toString())
                        .replace("{{routeDistance}}", escapeHtml(distanceDisplay))
                        .replace("{{routeDuration}}", escapeHtml(durationDisplay))
                        .replace("{{lastUpdatedTime}}", escapeHtml(lastUpdatedDisplay))
                        .replace("{{interactiveMapHtml}}", mapHtml)
                        .replace("{{terminalDetailsHtml}}", "")
                        .replace("{{stopManagementRows}}", stopRows.toString())
                        .replace("{{preloadButton}}", preloadBtn)
                        .replace("{{studentAssignmentRows}}", studentAssignmentRows.toString())
                        .replace("{{studentOptionsHtml}}", studentOptions.toString())
                        .replace("{{stopOptionsHtml}}", stopOptions.toString())
                        .replace("{{attendanceBreakdownRows}}", attBreakdown.toString());

                sendHtml(exchange, rendered);
            } catch (SQLException exception) {
                exception.printStackTrace();
                sendError(exchange, 500, "Could not load route management details from database.");
            }
        }

        private void handleUpdateTerminals(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "POST method required.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }
            Map<String, String> form = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
            String busId = form.get("busId");
            if (busId == null || busId.trim().isEmpty()) busId = "BUS-01";
            try {
                double depotLat = Double.parseDouble(form.get("depotLatitude"));
                double depotLon = Double.parseDouble(form.get("depotLongitude"));
                double collegeLat = Double.parseDouble(form.get("collegeLatitude"));
                double collegeLon = Double.parseDouble(form.get("collegeLongitude"));

                if (!RoutePlanner.isValidCoordinate(depotLat, depotLon) || !RoutePlanner.isValidCoordinate(collegeLat, collegeLon)) {
                    redirect(exchange, "/management-routes.html?error=invalid_coords");
                    return;
                }
                ROUTE_PLANNER.updateTerminalLocations(busId, depotLat, depotLon, collegeLat, collegeLon);
                ROUTE_PLANNER.calculateRealRoadRoute(busId, LocalDate.now(), null);
                redirect(exchange, "/management-routes.html?result=terminals_updated");
            } catch (Exception e) {
                redirect(exchange, "/management-routes.html?error=invalid_coords");
            }
        }

        private void handleReassignStudent(HttpExchange exchange, AttendanceStore attendanceStore) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "POST method required.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }
            Map<String, String> form = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
            String studentId = form.get("studentId");
            String newStopCode = form.get("newStopCode");
            String busId = form.get("busId");
            if (busId == null || busId.trim().isEmpty()) busId = "BUS-01";

            if (studentId == null || studentId.trim().isEmpty() || newStopCode == null || newStopCode.trim().isEmpty()) {
                redirect(exchange, "/management-routes.html?error=missing_fields");
                return;
            }
            try {
                ROUTE_PLANNER.reassignStudent(studentId, newStopCode);
                loadStudentsFromDatabase();
                try (Connection feeCon = DatabaseConnection.getConnection()) {
                    TransportFeeService.calculateAndSaveStudentFee(feeCon, studentId);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
                recalculateRouteAfterAttendance(busId, LocalDate.now(), attendanceStore);
                redirect(exchange, "/management-routes.html?result=student_reassigned");
            } catch (Exception e) {
                e.printStackTrace();
                redirect(exchange, "/management-routes.html?error=reassign_failed");
            }
        }

        private void handleAddStop(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "POST method required.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }
            Map<String, String> form = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
            String stopCode = form.get("stopCode");
            String stopName = form.get("stopName");
            String busId = form.get("busId");
            String latStr = form.get("latitude");
            String lonStr = form.get("longitude");
            String orderStr = form.get("stopOrder");

            if (stopCode == null || stopCode.trim().isEmpty() || stopName == null || stopName.trim().isEmpty()) {
                redirect(exchange, "/management-routes.html?error=missing_fields");
                return;
            }
            try {
                double lat = Double.parseDouble(latStr);
                double lon = Double.parseDouble(lonStr);
                int order = (orderStr != null && !orderStr.trim().isEmpty()) ? Integer.parseInt(orderStr.trim()) : 1;
                if (!RoutePlanner.isValidCoordinate(lat, lon)) {
                    redirect(exchange, "/management-routes.html?error=invalid_coords");
                    return;
                }
                ROUTE_PLANNER.addManagedStop(stopCode, stopName, busId, lat, lon, order);
                redirect(exchange, "/management-routes.html?result=stop_added");
            } catch (Exception e) {
                redirect(exchange, "/management-routes.html?error=invalid_coords");
            }
        }

        private void handleEditStop(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "POST method required.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }
            Map<String, String> form = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
            String stopCode = form.get("stopCode");
            String stopName = form.get("stopName");
            String busId = form.get("busId");
            String latStr = form.get("latitude");
            String lonStr = form.get("longitude");
            String orderStr = form.get("stopOrder");
            try {
                double lat = Double.parseDouble(latStr);
                double lon = Double.parseDouble(lonStr);
                int order = (orderStr != null && !orderStr.trim().isEmpty()) ? Integer.parseInt(orderStr.trim()) : 1;
                if (!RoutePlanner.isValidCoordinate(lat, lon)) {
                    redirect(exchange, "/management-routes.html?error=invalid_coords");
                    return;
                }
                ROUTE_PLANNER.updateManagedStop(stopCode, stopName, busId, lat, lon, order);
                redirect(exchange, "/management-routes.html?result=stop_updated");
            } catch (Exception e) {
                redirect(exchange, "/management-routes.html?error=invalid_coords");
            }
        }

        private void handleDeleteStop(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "POST method required.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }
            Map<String, String> form = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
            String stopCode = form.get("stopCode");
            if (stopCode == null || stopCode.trim().isEmpty()) {
                redirect(exchange, "/management-routes.html");
                return;
            }
            try {
                if (ROUTE_PLANNER.stopHasAssignedStudents(stopCode)) {
                    redirect(exchange, "/management-routes.html?warning=has_students&stopCode=" + URLEncoder.encode(stopCode, StandardCharsets.UTF_8));
                    return;
                }
                ROUTE_PLANNER.deleteManagedStop(stopCode);
                redirect(exchange, "/management-routes.html?result=stop_deleted");
            } catch (SQLException e) {
                e.printStackTrace();
                sendError(exchange, 500, "Could not delete stop from database.");
            }
        }

        private void handleReassignAndDeleteStop(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "POST method required.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }
            Map<String, String> form = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
            String stopCode = form.get("stopCode");
            String replacementStopCode = form.get("replacementStopCode");
            if (stopCode == null || replacementStopCode == null || stopCode.equals(replacementStopCode)) {
                redirect(exchange, "/management-routes.html");
                return;
            }
            try {
                ROUTE_PLANNER.reassignStudentsAndDelete(stopCode, replacementStopCode);
                redirect(exchange, "/management-routes.html?result=reassigned_deleted");
            } catch (SQLException e) {
                e.printStackTrace();
                sendError(exchange, 500, "Could not reassign students and delete stop.");
            }
        }

        private void handleToggleStopActivation(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "POST method required.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }
            Map<String, String> form = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
            String stopCode = form.get("stopCode");
            if (stopCode != null && !stopCode.trim().isEmpty()) {
                try {
                    ROUTE_PLANNER.toggleStopOverride(stopCode);
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
            redirect(exchange, "/management-routes.html?result=override_toggled");
        }

        private void handlePreloadStops(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "POST method required.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }
            try {
                ROUTE_PLANNER.preloadStops3To10();
                redirect(exchange, "/management-routes.html?result=preloaded");
            } catch (SQLException e) {
                e.printStackTrace();
                sendError(exchange, 500, "Could not preload stops.");
            }
        }

        private void handleApiRouteData(HttpExchange exchange) throws IOException {
            String query = exchange.getRequestURI().getQuery();
            String busId = queryValue(query, "busId");
            if (busId == null || busId.trim().isEmpty()) busId = "BUS-01";
            String dateStr = queryValue(query, "date");
            LocalDate date = LocalDate.now();
            if (dateStr != null && !dateStr.trim().isEmpty()) {
                try { date = LocalDate.parse(dateStr.trim()); } catch (Exception ignored) {}
            }
            try {
                RoutePlanner.RealRouteResult r = ROUTE_PLANNER.calculateRealRoadRoute(busId, date, null);
                StringBuilder json = new StringBuilder("{");
                json.append("\"busId\":\"").append(r.busId).append("\",");
                json.append("\"date\":\"").append(r.date).append("\",");
                json.append("\"serviceAvailable\":").append(r.serviceAvailable).append(",");
                json.append("\"distanceMeters\":").append(r.distanceMeters).append(",");
                json.append("\"durationSeconds\":").append(r.durationSeconds).append(",");
                json.append("\"geojson\":").append(r.geojsonCoordinates != null ? r.geojsonCoordinates : "[]");
                json.append("}");
                byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            } catch (SQLException e) {
                sendError(exchange, 500, "Database error");
            }
        }

        private void handleRouteLocationSave(HttpExchange exchange) throws IOException {
            if (!"POST".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "POST method required for route location updates.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }
            Map<String, String> fields;
            try {
                fields = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException exception) {
                sendError(exchange, 400, "The route location form could not be read.");
                return;
            }
            String type = fields.get("locationType");
            String key = fields.get("locationKey");
            String busId = fields.get("busId");
            try {
                double latitude = Double.parseDouble(fields.get("latitude"));
                double longitude = Double.parseDouble(fields.get("longitude"));
                if (!validRouteLocation(type, key, busId)
                        || !RoutePlanner.isValidCoordinate(latitude, longitude)) {
                    redirect(exchange, "/management-routes.html?error=invalid");
                    return;
                }
                ROUTE_PLANNER.saveLocation(type, key, busId, latitude, longitude);
                redirect(exchange, "/management-routes.html?result=saved");
            } catch (IllegalArgumentException exception) {
                redirect(exchange, "/management-routes.html?error=invalid");
            } catch (SQLException exception) {
                exception.printStackTrace();
                redirect(exchange, "/management-routes.html?error=database");
            }
        }

        private boolean validRouteLocation(String type, String key, String busId)
                throws SQLException {
            if ("COLLEGE".equals(type)) {
                return "COLLEGE".equals(key) && (busId == null || busId.isEmpty());
            }
            if ("PICKUP".equals(type)) {
                if (key == null || key.trim().isEmpty() || key.length() > 150) {
                    return false;
                }
                try (Connection connection = DatabaseConnection.getConnection();
                     PreparedStatement statement = connection.prepareStatement(
                             "SELECT 1 FROM students WHERE stop_name = ? LIMIT 1")) {
                    statement.setString(1, key);
                    try (ResultSet result = statement.executeQuery()) {
                        return result.next() && (busId == null || busId.isEmpty());
                    }
                }
            }
            if ("BUS_START".equals(type) && key != null && key.equals(busId)) {
                try (Connection connection = DatabaseConnection.getConnection();
                     PreparedStatement statement = connection.prepareStatement(
                             "SELECT 1 FROM buses WHERE bus_id = ?")) {
                    statement.setString(1, key);
                    try (ResultSet result = statement.executeQuery()) {
                        return result.next();
                    }
                }
            }
            return false;
        }

        private void handleRecalculateRoute(
                HttpExchange exchange, AttendanceStore attendanceStore) throws IOException {
            UserSession session = currentSession(exchange);
            if (session == null) {
                redirect(exchange, "/index.html");
                return;
            }

            String busId = null;
            String dateStr = null;
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> form = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
                busId = form.get("busId");
                dateStr = form.get("date");
            } else {
                String query = exchange.getRequestURI().getQuery();
                busId = queryValue(query, "busId");
                dateStr = queryValue(query, "date");
            }

            LocalDate date = LocalDate.now();
            if (dateStr != null && !dateStr.trim().isEmpty()) {
                try {
                    date = LocalDate.parse(dateStr.trim());
                } catch (Exception ignored) {}
            }

            // Role-based validation & security: Drivers and students can ONLY recalculate their own bus
            if ("driver".equals(session.role)) {
                try (Connection con = DatabaseConnection.getConnection();
                     PreparedStatement ps = con.prepareStatement("SELECT bus_id FROM drivers WHERE driver_id = ?")) {
                    ps.setString(1, session.userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            busId = rs.getString("bus_id");
                        }
                    }
                } catch (SQLException ignore) {}
            } else if ("student".equals(session.role)) {
                try (Connection con = DatabaseConnection.getConnection();
                     PreparedStatement ps = con.prepareStatement("SELECT bus_id FROM students WHERE student_id = ?")) {
                    ps.setString(1, session.userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            busId = rs.getString("bus_id");
                        }
                    }
                } catch (SQLException ignore) {}
            }

            if (busId == null || busId.trim().isEmpty() || "ALL".equalsIgnoreCase(busId)) {
                busId = "BUS-01";
            }

            // Sync latest attendance from MySQL database
            try {
                attendanceStore.load();
            } catch (Exception e) {
                System.err.println("[WARN] Reloading attendance store during recalculation: " + e.getMessage());
            }

            // Perform route recalculation with latest attendance
            try {
                ROUTE_PLANNER.recalculateAndSaveSharedRoute(busId, date);
                // Also update legacy Dijkstra route for backwards compatibility
                ROUTE_PLANNER.calculate(RoutePlanner.normalizeBusId(busId), attendanceStatusesForToday(attendanceStore));
            } catch (SQLException e) {
                System.err.println("[ERROR] Route recalculation failed: " + e.getMessage());
            }

            // Redirect back to caller's route map with recalculation confirmation
            if ("driver".equals(session.role)) {
                redirect(exchange, "/driver-route.html?recalculated=1&date=" + date);
            } else if ("student".equals(session.role)) {
                redirect(exchange, "/student-route.html?recalculated=1&date=" + date);
            } else {
                redirect(exchange, "/management-routes.html?recalculated=1&busId=" + busId + "&date=" + date);
            }
        }

        private void handleDriverRoutePage(
                HttpExchange exchange, AttendanceStore attendanceStore) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET method required for the driver route page.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null || !"driver".equals(session.role)) {
                sendError(exchange, 403, "Driver login required.");
                return;
            }
            try {
                String query = exchange.getRequestURI().getQuery();
                String dateStr = queryValue(query, "date");
                LocalDate date = LocalDate.now();
                if (dateStr != null && !dateStr.trim().isEmpty()) {
                    try {
                        date = LocalDate.parse(dateStr.trim());
                    } catch (Exception ignored) {}
                }

                String driverName = "Driver";
                String busId = null;
                String busNumber = null;
                String routeName = "Standard Campus Route";
                String driverSql = "SELECT d.name, d.bus_id, b.bus_number, b.route_name "
                        + "FROM drivers d LEFT JOIN buses b ON b.bus_id = d.bus_id "
                        + "WHERE d.driver_id = ?";
                try (Connection con = DatabaseConnection.getConnection();
                     PreparedStatement statement = con.prepareStatement(driverSql)) {
                    statement.setString(1, session.userId);
                    try (ResultSet rs = statement.executeQuery()) {
                        if (rs.next()) {
                            driverName = defaultDisplay(rs.getString("name"));
                            busId = rs.getString("bus_id");
                            busNumber = rs.getString("bus_number");
                            String rName = rs.getString("route_name");
                            if (rName != null && !rName.trim().isEmpty()) {
                                routeName = rName.trim();
                            }
                        }
                    }
                }

                if (busId == null || busId.trim().isEmpty()) {
                    busId = "BUS-01";
                }

                int totalStudents = 0;
                String countSql = "SELECT COUNT(*) FROM students WHERE bus_id = ? AND transport_authorized = 1";
                try (Connection con = DatabaseConnection.getConnection();
                     PreparedStatement ps = con.prepareStatement(countSql)) {
                    ps.setString(1, busId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) totalStudents = rs.getInt(1);
                    }
                }

                // Calculate real road route for this driver's bus and date directly from MySQL attendance for the selected date
                RoutePlanner.RealRouteResult realRoute = ROUTE_PLANNER.recalculateAndSaveSharedRoute(busId, date);

                String busDisplay = defaultDisplay(busNumber != null ? busNumber : busId);
                String busIdDisplay = busId;

                String distanceDisplay = String.format(Locale.ROOT, "%.1f km", realRoute.distanceMeters / 1000.0);
                String durationDisplay = String.valueOf(Math.round(realRoute.durationSeconds / 60.0));
                String lastUpdatedDisplay = realRoute.lastCalculatedAt != null
                        ? realRoute.lastCalculatedAt.toString().substring(0, 19).replace("T", " ")
                        : "Today at " + date;

                String routeMessage = realRoute.serviceNotice != null
                        ? escapeHtml(realRoute.serviceNotice)
                        : (realRoute.skippedStops.isEmpty()
                        ? "All scheduled pickup stops are currently active and confirmed on today's route."
                        : realRoute.skippedStops.size() + " stop(s) skipped today due to confirmed student absence.");

                // Render Leaflet map HTML
                String mapHtml = ROUTE_PLANNER.renderLeafletMapHtml(realRoute, "driver", null);

                // Complete corridor route schedule (Starting Point -> Stop 1 to 10 -> College)
                StringBuilder completeRouteRows = new StringBuilder();
                completeRouteRows.append("<li class=\"driver-pickup-row route-required\" style=\"border-left: 4px solid #3b82f6;\"><strong>Starting Point: Gandhipuram Central Terminal (Depot)</strong><span>Origin &bull; Fleet Departure Point &bull; Coordinates: 11.0168, 76.9558</span></li>\n");
                for (RoutePlanner.ManagedStop s : realRoute.allStops) {
                    boolean isAct = s.status == RoutePlanner.StopStatus.REQUIRED;
                    String statusBadge = isAct
                            ? "<span class=\"badge badge-success\">ACTIVE</span>"
                            : "<span class=\"badge badge-warning\" style=\"background:#ef4444;color:#fff;\">SKIPPED</span>";
                    completeRouteRows.append("<li class=\"driver-pickup-row ")
                            .append(isAct ? "route-required" : "route-skipped").append("\">")
                            .append("<strong>").append(escapeHtml(s.stopCode)).append(" &mdash; ").append(escapeHtml(s.stopName)).append("</strong>")
                            .append("<span>Status: ").append(statusBadge)
                            .append(" &bull; Assigned Students: ").append(s.assignedStudentNames.size())
                            .append(" &bull; ").append(escapeHtml(s.statusReason)).append("</span></li>\n");
                }
                completeRouteRows.append("<li class=\"driver-pickup-row route-destination\" style=\"border-left: 4px solid #ffd700;\"><strong>Final Destination: CIET Central Campus</strong><span>College Arrival &bull; Final Destination &bull; Coordinates: 10.9632, 76.8142</span></li>\n");

                // Ordered pickup rows
                StringBuilder pickupRows = new StringBuilder();
                int order = 1;
                for (RoutePlanner.ManagedStop s : realRoute.orderedStops) {
                    pickupRows.append("<li class=\"driver-pickup-row route-required\"><strong>")
                            .append(order++).append(". ").append(escapeHtml(s.stopCode)).append(" &mdash; ")
                            .append(escapeHtml(s.stopName)).append("</strong><span>")
                            .append(s.assignedStudentNames.size()).append(" assigned student(s) &bull; Coordinates: ")
                            .append(String.format(Locale.ROOT, "%.4f, %.4f", s.latitude, s.longitude)).append("</span></li>\n");
                }
                pickupRows.append("<li class=\"driver-pickup-row route-destination\"><strong>Final Destination: CIET Central Campus</strong><span>College Arrival &bull; Coordinates: 10.9632, 76.8142</span></li>\n");

                // Skipped rows
                StringBuilder skippedRows = new StringBuilder();
                if (!realRoute.skippedStops.isEmpty()) {
                    for (RoutePlanner.ManagedStop s : realRoute.skippedStops) {
                        skippedRows.append("<li class=\"driver-pickup-row route-skipped\"><strong>")
                                .append(escapeHtml(s.stopCode)).append(" &mdash; ").append(escapeHtml(s.stopName)).append("</strong>")
                                .append("<span>Reason: ").append(escapeHtml(s.statusReason)).append("</span></li>\n");
                    }
                } else {
                    skippedRows.append("<li class=\"registration-empty\" style=\"color: var(--green-success);\">&#10004; No stops skipped today. All scheduled pickup stops are active.</li>\n");
                }

                // Unconfirmed safeguards rows
                StringBuilder unconfirmedRows = new StringBuilder();
                if (!realRoute.unconfirmedStops.isEmpty()) {
                    for (RoutePlanner.ManagedStop s : realRoute.unconfirmedStops) {
                        unconfirmedRows.append("<li class=\"driver-pickup-row route-unconfirmed\" style=\"border-left: 4px solid #f59e0b;\"><strong>")
                                .append(escapeHtml(s.stopCode)).append(" &mdash; ").append(escapeHtml(s.stopName)).append("</strong>")
                                .append("<span>").append(escapeHtml(s.statusReason)).append("</span></li>\n");
                    }
                } else {
                    unconfirmedRows.append("<li class=\"registration-empty\" style=\"color: var(--green-success);\">&#10004; All scheduled stops on this route have confirmed attendance.</li>\n");
                }

                String recalculationNotice = "1".equals(queryValue(query, "recalculated"))
                        ? "<div class=\"alert-callout alert-success\" style=\"margin-bottom: 20px;\"><div class=\"alert-icon\">&#10004;</div><div><div class=\"alert-title\">Route Recalculated Successfully</div><div class=\"alert-body\">Vehicle route has been recomputed using the latest saved MySQL attendance records for " + date + ".</div></div></div>"
                        : "";

                String template = new String(Files.readAllBytes(new File("driver-route.html").toPath()), StandardCharsets.UTF_8);
                String rendered = template.replace("{{driverName}}", escapeHtml(driverName))
                        .replace("{{driverId}}", escapeHtml(session.userId))
                        .replace("{{driverBus}}", escapeHtml(busDisplay))
                        .replace("{{busId}}", escapeHtml(busIdDisplay))
                        .replace("{{routeName}}", escapeHtml(routeName))
                        .replace("{{assignedStudentCount}}", String.valueOf(totalStudents))
                        .replace("{{routeDistance}}", escapeHtml(distanceDisplay))
                        .replace("{{routeDuration}}", escapeHtml(durationDisplay))
                        .replace("{{selectedDate}}", date.toString())
                        .replace("{{lastUpdatedTime}}", escapeHtml(lastUpdatedDisplay))
                        .replace("{{routeMessage}}", routeMessage)
                        .replace("{{interactiveMapHtml}}", mapHtml)
                        .replace("{{completeRouteRows}}", completeRouteRows.toString())
                        .replace("{{pickupRows}}", pickupRows.toString())
                        .replace("{{skippedRows}}", skippedRows.toString())
                        .replace("{{unconfirmedRows}}", unconfirmedRows.toString())
                        .replace("{{recalculationNotice}}", recalculationNotice);

                sendHtml(exchange, rendered);
            } catch (SQLException exception) {
                exception.printStackTrace();
                sendError(exchange, 500, "Could not load route details for the driver.");
            }
        }

        private void handleRouteMapPage(
                HttpExchange exchange, AttendanceStore attendanceStore) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "GET method required for the route map.");
                return;
            }
            UserSession session = currentSession(exchange);
            if (session == null) {
                redirect(exchange, "/index.html");
                return;
            }
            if ("driver".equals(session.role)) {
                handleDriverRoutePage(exchange, attendanceStore);
            } else if ("student".equals(session.role)) {
                handleStudentRouteMapPage(exchange, attendanceStore, session);
            } else if ("management".equals(session.role)) {
                handleManagementRoutesPage(exchange, attendanceStore);
            } else {
                sendError(exchange, 403, "Unauthorized role.");
            }
        }

        private void handleStudentRouteMapPage(
                HttpExchange exchange, AttendanceStore attendanceStore, UserSession session) throws IOException {
            try {
                String query = exchange.getRequestURI().getQuery();
                String dateStr = queryValue(query, "date");
                LocalDate date = LocalDate.now();
                if (dateStr != null && !dateStr.trim().isEmpty()) {
                    try {
                        date = LocalDate.parse(dateStr.trim());
                    } catch (Exception ignored) {}
                }

                String studentName = "Student";
                String studentId = session.userId;
                String busId = "BUS-01";
                String pickupStop = "Stop 1";
                String department = "Engineering";
                String academicYear = "II Year";

                String sql = "SELECT student_id, name, bus_id, stop_name, department, academic_year "
                        + "FROM students WHERE student_id = ?";
                try (Connection con = DatabaseConnection.getConnection();
                     PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setString(1, session.userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            studentName = defaultDisplay(rs.getString("name"));
                            String b = rs.getString("bus_id");
                            if (b != null && !b.trim().isEmpty()) busId = b.trim();
                            String s = rs.getString("stop_name");
                            if (s != null && !s.trim().isEmpty()) pickupStop = s.trim();
                            String d = rs.getString("department");
                            if (d != null && !d.trim().isEmpty()) department = d.trim();
                            String y = rs.getString("academic_year");
                            if (y != null && !y.trim().isEmpty()) academicYear = y.trim();
                        }
                    }
                }

                String normalizedBusId = RoutePlanner.normalizeBusId(busId);
                String normalizedStop = RoutePlanner.normalizeStopCode(pickupStop);

                // Calculate real road route with this student's pickup stop highlighted directly from MySQL attendance for the selected date
                RoutePlanner.RealRouteResult realRoute = ROUTE_PLANNER.calculateRealRoadRouteInternal(normalizedBusId, date, true, normalizedStop);

                // Determine this student's stop active/skipped status
                RoutePlanner.ManagedStop studentStopObj = null;
                for (RoutePlanner.ManagedStop ms : realRoute.allStops) {
                    if (ms.stopCode.equalsIgnoreCase(normalizedStop)) {
                        studentStopObj = ms;
                        break;
                    }
                }

                boolean isStopActive = studentStopObj != null && studentStopObj.status == RoutePlanner.StopStatus.REQUIRED;
                boolean isStopSkipped = studentStopObj != null && studentStopObj.status == RoutePlanner.StopStatus.SKIPPED;

                String stopStatusPill;
                String stopStatusNotice;
                if (isStopActive) {
                    stopStatusPill = "<span class=\"user-status-pill badge-success\"><svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"><polyline points=\"20 6 9 17 4 12\"></polyline></svg> Your Stop Active</span>";
                    stopStatusNotice = "<div class=\"alert-callout alert-success\" style=\"margin-bottom:20px;\"><div class=\"alert-icon\">&#10004;</div><div><div class=\"alert-title\">Your Pickup Stop is Active</div><div class=\"alert-body\">Bus " + escapeHtml(normalizedBusId) + " is scheduled to pick up passengers at <strong>" + escapeHtml(pickupStop) + "</strong> on " + date + " before proceeding to CIET Central Campus.</div></div></div>";
                } else if (isStopSkipped) {
                    stopStatusPill = "<span class=\"user-status-pill badge-warning\" style=\"background:#ef4444;color:#fff;\">&#9888; Your Stop Skipped Today</span>";
                    String reasonText = studentStopObj != null && studentStopObj.statusReason != null ? studentStopObj.statusReason : "All assigned passengers for this stop are marked Absent.";
                    stopStatusNotice = "<div class=\"alert-callout alert-warning\" style=\"border-left-color:var(--red-danger);margin-bottom:20px;\"><div class=\"alert-icon\" style=\"color:var(--red-danger);\">&#9888;</div><div><div class=\"alert-title\" style=\"color:var(--red-danger);\">Your Pickup Stop is Skipped Today (" + date + ")</div><div class=\"alert-body\"><strong>Reason:</strong> " + escapeHtml(reasonText) + "<br>The bus will bypass this stop today and proceed directly along the optimized road route to reduce transit time.</div></div></div>";
                } else {
                    stopStatusPill = "<span class=\"user-status-pill badge-neutral\">Stop Excluded</span>";
                    stopStatusNotice = "<div class=\"alert-callout alert-info\" style=\"margin-bottom:20px;\"><div class=\"alert-icon\">&#8505;</div><div><div class=\"alert-title\">Pickup Stop Status</div><div class=\"alert-body\">Your stop is currently not scheduled for pickup today.</div></div></div>";
                }

                // Render Leaflet map HTML with student stop highlighted
                String mapHtml = ROUTE_PLANNER.renderLeafletMapHtml(realRoute, "student", normalizedStop);

                // Complete corridor route schedule (Starting Point -> Stop 1 to 10 -> College)
                StringBuilder completeRouteRows = new StringBuilder();
                completeRouteRows.append("<li class=\"driver-pickup-row route-required\" style=\"border-left: 4px solid #3b82f6;\"><strong>Starting Point: Gandhipuram Central Terminal (Depot)</strong><span>Origin &bull; Departure point for Bus ").append(escapeHtml(normalizedBusId)).append("</span></li>\n");
                for (RoutePlanner.ManagedStop s : realRoute.allStops) {
                    boolean isMyStop = s.stopCode.equalsIgnoreCase(normalizedStop);
                    boolean isAct = s.status == RoutePlanner.StopStatus.REQUIRED;
                    String statusBadge = isAct
                            ? "<span class=\"badge badge-success\">ACTIVE</span>"
                            : "<span class=\"badge badge-warning\" style=\"background:#ef4444;color:#fff;\">SKIPPED</span>";
                    completeRouteRows.append("<li class=\"driver-pickup-row ")
                            .append(isAct ? "route-required" : "route-skipped")
                            .append(isMyStop ? " stop-highlighted\" style=\"border-left: 4px solid var(--navy-primary);background:rgba(212,175,55,0.08);\"" : "\"").append(">")
                            .append("<strong>").append(escapeHtml(s.stopCode)).append(" &mdash; ").append(escapeHtml(s.stopName))
                            .append(isMyStop ? " <span class=\"badge badge-info\" style=\"font-size:0.75rem;\">&#9733; Your Pickup Stop</span>" : "")
                            .append("</strong><span>Status: ").append(statusBadge)
                            .append(" &bull; ").append(escapeHtml(s.statusReason)).append("</span></li>\n");
                }
                completeRouteRows.append("<li class=\"driver-pickup-row route-destination\" style=\"border-left: 4px solid #ffd700;\"><strong>Final Destination: CIET Central Campus</strong><span>Arrival at CIET Central Campus</span></li>\n");

                // Active pickup rows leading to campus
                StringBuilder pickupRows = new StringBuilder();
                int order = 1;
                for (RoutePlanner.ManagedStop s : realRoute.orderedStops) {
                    boolean isMyStop = s.stopCode.equalsIgnoreCase(normalizedStop);
                    pickupRows.append("<li class=\"driver-pickup-row route-required").append(isMyStop ? " stop-highlighted\" style=\"border-left: 4px solid var(--navy-primary);background:rgba(212,175,55,0.08);\"" : "\"").append("><strong>")
                            .append(order++).append(". ").append(escapeHtml(s.stopCode)).append(" &mdash; ").append(escapeHtml(s.stopName)).append("</strong>")
                            .append("<span>").append(isMyStop ? "&#9733; Your Designated Pickup Stop" : "Active Route Stop").append("</span></li>\n");
                }
                pickupRows.append("<li class=\"driver-pickup-row route-destination\"><strong>Final Destination: CIET Central Campus</strong><span>Arrival at CIET Central Campus</span></li>\n");

                // Skipped stops rows (Privacy-preserving summary: do not show other students' personal info)
                StringBuilder skippedRows = new StringBuilder();
                if (!realRoute.skippedStops.isEmpty()) {
                    for (RoutePlanner.ManagedStop s : realRoute.skippedStops) {
                        boolean isMyStop = s.stopCode.equalsIgnoreCase(normalizedStop);
                        skippedRows.append("<li class=\"driver-pickup-row route-skipped\"").append(isMyStop ? " style=\"border-left:4px solid #ef4444;background:rgba(239,68,68,0.08);\"" : "").append("><strong>")
                                .append(escapeHtml(s.stopCode)).append(" &mdash; ").append(escapeHtml(s.stopName))
                                .append(isMyStop ? " (Your Stop)" : "").append("</strong>")
                                .append("<span>Reason: ").append(escapeHtml(s.statusReason)).append("</span></li>\n");
                    }
                } else {
                    skippedRows.append("<li class=\"registration-empty\" style=\"color: var(--green-success);\">&#10004; All stops on this bus route are currently active and required.</li>");
                }

                String distanceDisplay = String.format(Locale.ROOT, "%.1f km", realRoute.distanceMeters / 1000.0);
                String durationDisplay = String.valueOf(Math.round(realRoute.durationSeconds / 60.0));
                String lastUpdatedDisplay = realRoute.lastCalculatedAt != null
                        ? realRoute.lastCalculatedAt.toString().substring(0, 19).replace("T", " ")
                        : "Today at " + date;

                String recalculationNotice = "1".equals(queryValue(query, "recalculated"))
                        ? "<div class=\"alert-callout alert-success\" style=\"margin-bottom: 20px;\"><div class=\"alert-icon\">&#10004;</div><div><div class=\"alert-title\">Route Recalculated Successfully</div><div class=\"alert-body\">Bus route and stop status recomputed from latest attendance records for " + date + ".</div></div></div>"
                        : "";

                String template = new String(Files.readAllBytes(new File("student-route.html").toPath()), StandardCharsets.UTF_8);
                String rendered = template.replace("{{studentName}}", escapeHtml(studentName))
                        .replace("{{studentId}}", escapeHtml(studentId))
                        .replace("{{studentBus}}", escapeHtml(normalizedBusId))
                        .replace("{{busId}}", escapeHtml(normalizedBusId))
                        .replace("{{studentPickupStop}}", escapeHtml(pickupStop))
                        .replace("{{stopStatusPill}}", stopStatusPill)
                        .replace("{{stopStatusNotice}}", stopStatusNotice)
                        .replace("{{selectedDate}}", date.toString())
                        .replace("{{lastUpdatedTime}}", escapeHtml(lastUpdatedDisplay))
                        .replace("{{routeDistance}}", escapeHtml(distanceDisplay))
                        .replace("{{routeDuration}}", escapeHtml(durationDisplay))
                        .replace("{{interactiveMapHtml}}", mapHtml)
                        .replace("{{completeRouteRows}}", completeRouteRows.toString())
                        .replace("{{pickupRows}}", pickupRows.toString())
                        .replace("{{skippedRows}}", skippedRows.toString())
                        .replace("{{recalculationNotice}}", recalculationNotice);

                sendHtml(exchange, rendered);
            } catch (SQLException exception) {
                exception.printStackTrace();
                sendError(exchange, 500, "Could not load route map for the student.");
            }
        }

        private String driverRegistrationMessage(String query) {
            String result = queryValue(query, "result");
            String error = queryValue(query, "error");
            if ("driver-added".equals(result)) {
                return "<p class=\"registration-message registration-success\" role=\"status\">Driver registered successfully.</p>";
            }
            if ("duplicate".equals(error)) {
                return "<p class=\"registration-message registration-error\" role=\"alert\">That Driver ID is already registered.</p>";
            }
            if ("invalid".equals(error)) {
                return "<p class=\"registration-message registration-error\" role=\"alert\">Enter a unique Driver ID, a name, a valid phone number, a password of at least 8 characters, and a registered bus.</p>";
            }
            if ("database".equals(error)) {
                return "<p class=\"registration-message registration-error\" role=\"alert\">The driver could not be saved to MySQL. Please try again or check the server log.</p>";
            }
            return "";
        }

        private String departmentOptions() {
            StringBuilder options = new StringBuilder();
            for (String department : STUDENT_DEPARTMENTS) {
                options.append("<option value=\"").append(escapeHtml(department)).append("\">")
                        .append(escapeHtml(department)).append("</option>");
            }
            return options.toString();
        }

        private String registeredBusOptions() throws SQLException {
            StringBuilder options = new StringBuilder();
            String sql = "SELECT bus_id, bus_number FROM buses ORDER BY bus_id";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement statement = con.prepareStatement(sql);
                 ResultSet buses = statement.executeQuery()) {
                while (buses.next()) {
                    String busId = buses.getString("bus_id");
                    String busNumber = buses.getString("bus_number");
                    options.append("<option value=\"").append(escapeHtml(busId)).append("\">")
                            .append(escapeHtml(busNumber == null ? busId : busNumber))
                            .append("</option>");
                }
            }
            return options.toString();
        }

        private String registeredStudentRows() throws SQLException {
            StringBuilder rows = new StringBuilder();
            String sql = "SELECT s.student_id, s.name, s.department, s.academic_year, "
                    + "s.bus_id, b.bus_number, s.stop_name, s.transport_authorized, s.profile_photo "
                    + "FROM students s LEFT JOIN buses b ON b.bus_id = s.bus_id "
                    + "ORDER BY s.student_id";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement statement = con.prepareStatement(sql);
                 ResultSet students = statement.executeQuery()) {
                while (students.next()) {
                    String busNumber = students.getString("bus_number");
                    String busId = students.getString("bus_id");
                    String photo = students.getString("profile_photo");
                    rows.append("<tr><td><img src=\"").append(escapeHtml(resolvePhotoUrl(photo))).append("\" class=\"profile-avatar-sm\" alt=\"Student Photo\"></td>")
                            .append("<td><strong>").append(escapeHtml(students.getString("student_id"))).append("</strong></td>")
                            .append("<td>").append(escapeHtml(students.getString("name")))
                            .append("</td><td>").append(escapeHtml(defaultDisplay(students.getString("department"))))
                            .append("</td><td>").append(escapeHtml(defaultDisplay(students.getString("academic_year"))))
                            .append("</td><td>").append(escapeHtml(defaultDisplay(
                                    busNumber == null ? busId : busNumber)))
                            .append("</td><td>").append(escapeHtml(defaultDisplay(students.getString("stop_name"))))
                            .append("</td><td>").append(students.getBoolean("transport_authorized")
                                    ? "<span class=\"badge badge-success\">Authorized</span>"
                                    : "<span class=\"badge badge-warning\">Not Authorized</span>")
                            .append("</td><td><a class=\"btn btn-secondary\" style=\"padding: 3px 10px; font-size: 0.78rem; text-decoration: none; white-space: nowrap;\" href=\"management-students.html?resetId=")
                            .append(escapeHtml(students.getString("student_id"))).append("#card-reset-password\">&#128273; Reset Password</a></td>")
                            .append("</tr>");
                }
            }
            if (rows.length() == 0) {
                rows.append("<tr><td colspan=\"9\" class=\"registration-empty\">No students registered yet.</td></tr>");
            }
            return rows.toString();
        }

        private String studentResetOptions(String query) throws SQLException {
            String selectedId = queryValue(query, "studentId");
            if (selectedId == null) {
                selectedId = queryValue(query, "resetId");
            }
            StringBuilder options = new StringBuilder();
            String sql = "SELECT student_id, name, department FROM students ORDER BY student_id";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String sid = rs.getString("student_id");
                    String name = rs.getString("name");
                    String dept = rs.getString("department");
                    boolean isSelected = selectedId != null && selectedId.equalsIgnoreCase(sid);
                    options.append("<option value=\"").append(escapeHtml(sid)).append("\"")
                            .append(isSelected ? " selected" : "")
                            .append(">")
                            .append(escapeHtml(sid)).append(" &mdash; ").append(escapeHtml(name != null ? name : ""))
                            .append(dept != null && !dept.isEmpty() ? " (" + escapeHtml(dept) + ")" : "")
                            .append("</option>\n");
                }
            }
            return options.toString();
        }

        private String defaultDisplay(String value) {
            return value == null || value.trim().isEmpty() ? "Not specified" : value;
        }

        private void sendHtml(HttpExchange exchange, String html) throws IOException {
            byte[] response = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        }

private void handleLogin(HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendError(exchange, 405, "Login requires a form submission.");
        return;
    }

    Map<String, String> fields;

    try {
        fields = parseFormData(
            new String(readRequestBody(exchange), StandardCharsets.UTF_8)
        );
    } catch (Exception e) {
        sendError(exchange, 400, "The login form could not be read.");
        return;
    }

    String role = fields.get("role");
    String userId = fields.get("userId");
    if (userId == null || userId.trim().isEmpty()) {
        userId = fields.get("studentId");
    }
    if (userId == null || userId.trim().isEmpty()) {
        userId = fields.get("driverId");
    }
    if (userId == null || userId.trim().isEmpty()) {
        userId = fields.get("managementId");
    }
    if (userId == null || userId.trim().isEmpty()) {
        userId = fields.get("username");
    }
    if (userId == null || userId.trim().isEmpty()) {
        userId = fields.get("rollNo");
    }
    if (userId == null || userId.trim().isEmpty()) {
        userId = fields.get("rollNumber");
    }
    if (userId == null || userId.trim().isEmpty()) {
        userId = fields.get("id");
    }

    if (role == null || role.trim().isEmpty()) {
        if (fields.containsKey("studentId") || fields.containsKey("rollNo") || fields.containsKey("rollNumber")) {
            role = "student";
        } else if (fields.containsKey("driverId")) {
            role = "driver";
        } else if (fields.containsKey("managementId")) {
            role = "management";
        } else {
            String reqPath = exchange.getRequestURI().getPath();
            if (reqPath.contains("driver")) {
                role = "driver";
            } else if (reqPath.contains("management")) {
                role = "management";
            } else {
                role = "student";
            }
        }
    }

    String password = fields.get("password");

    System.out.println("[DIAGNOSTIC-LOGIN] Stage 1 - Request received: role=" + role + ", submittedId=" + userId + ", passwordProvided=" + (password != null && !password.isEmpty()));

    if (userId == null || password == null
            || userId.trim().isEmpty() || password.isEmpty()) {
        System.out.println("[DIAGNOSTIC-LOGIN] Stage 1 REJECTED: User ID or password was empty.");
        sendError(exchange, 400, "Enter your ID and password.");
        return;
    }

    userId = userId.trim();

    String dashboard;
    boolean authenticated = false;

    try {
        if ("student".equals(role)) {
            dashboard = "student-dashboard.html";
            System.out.println("[DIAGNOSTIC-LOGIN] Stage 2 - Dispatched to student MySQL auth for ID: " + userId);
            String authId = authenticateDatabaseUser(
                "students", "student_id", userId, password
            );
            if (authId != null) {
                authenticated = true;
                userId = authId;
                System.out.println("[DIAGNOSTIC-LOGIN] Stage 2 SUCCESS - Student successfully verified with canonical ID: " + userId);
                if (!STUDENTS.containsKey(userId)) {
                    loadStudentsFromDatabase();
                }
            } else {
                System.out.println("[DIAGNOSTIC-LOGIN] Stage 2 FAILED - Student verification returned null for ID: " + userId);
            }

        } else if ("driver".equals(role)) {
            dashboard = "driver-dashboard.html";
            String authId = authenticateDatabaseUser(
                "drivers", "driver_id", userId, password
            );
            if (authId != null) {
                authenticated = true;
                userId = authId;
                if (!DRIVER_BUSES.containsKey(userId)) {
                    loadDriversFromDatabase();
                }
            }

        } else if ("management".equals(role)) {
            dashboard = "management-dashboard.html";
            authenticated = authenticateManagementUser(userId, password);
            if (authenticated) {
                userId = (userId != null && !userId.trim().isEmpty()) ? userId.trim().toUpperCase(Locale.ROOT) : "ADMIN-01";
            }

        } else {
            sendError(exchange, 400, "Unknown login role.");
            return;
        }
    } catch (SQLException e) {
        System.err.println("[ERROR] Database failure during authentication for user " + userId + ": " + e.getMessage());
        sendError(exchange, 500, "Database connection error. Please try again later.");
        return;
    }

    if (!authenticated) {
        System.out.println("[DIAGNOSTIC-LOGIN] Stage 3 - Final authentication status: REJECTED (401 sent)");
        String loginPage = "student".equals(role) ? "student-login.html" : "driver".equals(role) ? "driver-login.html" : "management-login.html";
        String errorMsg = "<div class=\"registration-message registration-error\" role=\"alert\">&#9888; Incorrect ID or password. Please verify your credentials and try again.</div>";
        try {
            File loginFile = new File(loginPage);
            if (loginFile.exists() && !loginFile.isDirectory()) {
                String html = new String(Files.readAllBytes(loginFile.toPath()), StandardCharsets.UTF_8);
                html = html.replace("{{loginMessage}}", errorMsg)
                           .replace("<!-- LOGIN_MESSAGE -->", errorMsg);
                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                exchange.sendResponseHeaders(401, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }
        } catch (Exception ignored) {}
        sendError(exchange, 401, "Invalid ID or password.");
        return;
    }

    System.out.println("[DIAGNOSTIC-LOGIN] Stage 3 - Final authentication status: ACCEPTED (redirecting to " + dashboard + ")");

    String token = UUID.randomUUID().toString();
    SESSIONS.put(token, new UserSession(role, userId));

    exchange.getResponseHeaders().add(
        "Set-Cookie",
        "ciet_session=" + token
            + "; Path=/; HttpOnly; SameSite=Strict"
    );

    redirect(exchange, "/" + dashboard);
}

private String authenticateDatabaseUser(
        String table, String idColumn,
        String userId, String password) throws SQLException {

    // Only these two database tables and ID columns are permitted.
    if (!("students".equals(table) && "student_id".equals(idColumn))
            && !("drivers".equals(table) && "driver_id".equals(idColumn))) {
        System.err.println("[DIAGNOSTIC-AUTH] Invalid table/column requested: " + table + "." + idColumn);
        return null;
    }

    String sql = "SELECT " + idColumn + ", password_hash FROM " + table
            + " WHERE " + idColumn + " = ?";

    System.out.println("[DIAGNOSTIC-AUTH] Querying MySQL: table=" + table + ", idColumn=" + idColumn + ", queryId=" + userId);

    try (Connection con = DatabaseConnection.getConnection();
         PreparedStatement ps = con.prepareStatement(sql)) {

        ps.setString(1, userId);

        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                String canonicalId = rs.getString(idColumn);
                String storedHash = rs.getString("password_hash");

                System.out.println("[DIAGNOSTIC-AUTH] Student record FOUND in MySQL: canonicalId=" + canonicalId + ", hasStoredHash=" + (storedHash != null && !storedHash.trim().isEmpty()));

                if (storedHash == null || storedHash.trim().isEmpty()) {
                    System.out.println("[DIAGNOSTIC-AUTH] REJECTED: Stored password_hash is NULL or empty for user: " + canonicalId);
                    return null;
                }
                storedHash = storedHash.trim();

                boolean matched = false;
                if (storedHash.startsWith("PBKDF2$")) {
                    System.out.println("[DIAGNOSTIC-AUTH] Hash format: PBKDF2. Verifying password...");
                    matched = verifyPassword(password, storedHash);
                    System.out.println("[DIAGNOSTIC-AUTH] PBKDF2 verification result: " + (matched ? "MATCHED (credentials valid)" : "FAILED (credentials mismatch)"));
                } else if (storedHash.equals(password)) {
                    System.out.println("[DIAGNOSTIC-AUTH] Plaintext stored password match; upgrading to PBKDF2.");
                    matched = true;
                    try {
                        String newHash = hashPassword(password);
                        String updateSql = "UPDATE " + table + " SET password_hash = ? WHERE " + idColumn + " = ?";
                        try (PreparedStatement updatePs = con.prepareStatement(updateSql)) {
                            updatePs.setString(1, newHash);
                            updatePs.setString(2, canonicalId);
                            updatePs.executeUpdate();
                        }
                    } catch (Exception ex) {
                        System.err.println("[WARN] Could not upgrade plain text password to hash: " + ex.getMessage());
                    }
                } else {
                    System.out.println("[DIAGNOSTIC-AUTH] REJECTED: Unknown hash format and not plaintext.");
                }

                // Fallback for demo driver DRV-01
                if (!matched && "drivers".equals(table) && "DRV-01".equalsIgnoreCase(canonicalId) && "driver123".equals(password)) {
                    matched = true;
                }

                if (matched) {
                    return canonicalId;
                }
            } else {
                System.out.println("[DIAGNOSTIC-AUTH] REJECTED: No student record found in MySQL for ID: " + userId);
            }
        }
    }

    return null;
}

private boolean authenticateManagementUser(String userId, String password) {
    if (userId == null || password == null) {
        return false;
    }
    String sql = "SELECT admin_id, password_hash FROM management WHERE admin_id = ?";
    try (Connection con = DatabaseConnection.getConnection();
         PreparedStatement ps = con.prepareStatement(sql)) {
        ps.setString(1, userId.trim());
        try (ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                String storedHash = rs.getString("password_hash");
                if (storedHash != null && !storedHash.trim().isEmpty()) {
                    storedHash = storedHash.trim();
                    if (storedHash.startsWith("PBKDF2$")) {
                        return verifyPassword(password, storedHash);
                    } else if (storedHash.equals(password)) {
                        try {
                            String newHash = hashPassword(password);
                            try (PreparedStatement updatePs = con.prepareStatement("UPDATE management SET password_hash = ? WHERE admin_id = ?")) {
                                updatePs.setString(1, newHash);
                                updatePs.setString(2, rs.getString("admin_id"));
                                updatePs.executeUpdate();
                            }
                        } catch (Exception ignored) {}
                        return true;
                    }
                } else {
                    if ("ADMIN-01".equalsIgnoreCase(userId.trim()) && "admin123".equals(password)) {
                        return true;
                    }
                }
            } else {
                if ("ADMIN-01".equalsIgnoreCase(userId.trim()) && "admin123".equals(password)) {
                    return true;
                }
            }
        }
    } catch (SQLException e) {
        if ("ADMIN-01".equalsIgnoreCase(userId.trim()) && "admin123".equals(password)) {
            return true;
        }
    }
    return false;
}

private String hashPassword(String password) {
    try {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);

        PBEKeySpec spec = new PBEKeySpec(
            password.toCharArray(), salt, 210000, 256
        );

        byte[] hash = SecretKeyFactory
            .getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .getEncoded();

        spec.clearPassword();

        return "PBKDF2$210000$"
            + Base64.getEncoder().encodeToString(salt)
            + "$"
            + Base64.getEncoder().encodeToString(hash);

    } catch (Exception e) {
        throw new IllegalStateException("Password hashing failed.", e);
    }
}

private boolean verifyPassword(String password, String storedHash) {
    try {
        if (storedHash == null || storedHash.isEmpty()) {
            System.out.println("[DIAGNOSTIC-VERIFY] Stored hash is null or empty");
            return false;
        }

        String[] parts = storedHash.split("\\$");

        if (parts.length != 4 || !"PBKDF2".equals(parts[0])) {
            System.out.println("[DIAGNOSTIC-VERIFY] Invalid hash structure: partsCount=" + parts.length + ", scheme=" + (parts.length > 0 ? parts[0] : "none"));
            return false;
        }

        int iterations = Integer.parseInt(parts[1]);
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] expectedHash = Base64.getDecoder().decode(parts[3]);

        System.out.println("[DIAGNOSTIC-VERIFY] Hash structure validated: iters=" + iterations + ", saltBytes=" + salt.length + ", expectedHashBytes=" + expectedHash.length + " (keyLength=" + (expectedHash.length * 8) + " bits)");

        PBEKeySpec spec = new PBEKeySpec(
            password.toCharArray(), salt, iterations, expectedHash.length * 8
        );

        byte[] actualHash = SecretKeyFactory
            .getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .getEncoded();

        spec.clearPassword();

        boolean matched = MessageDigest.isEqual(expectedHash, actualHash);
        System.out.println("[DIAGNOSTIC-VERIFY] PBKDF2 comparison completed: matched=" + matched);
        return matched;

    } catch (Exception e) {
        System.err.println("[WARN] Password verification exception: " + e.getMessage());
        return false;
    }
}

private void handleAddStudent(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
        sendError(exchange, 405, "POST method required.");
        return;
    }
    UserSession session = currentSession(exchange);
    if (session == null || !"management".equals(session.role)) {
        sendError(exchange, 403, "Management login required.");
        return;
    }

    Map<String, String> fields;

    try {
        fields = parseFormData(
            new String(readRequestBody(exchange), StandardCharsets.UTF_8)
        );
    } catch (Exception e) {
        sendError(exchange, 400, "Invalid student form.");
        return;
    }

    String id = fields.get("studentId");
    String name = fields.get("studentName");
    String password = fields.get("password");
    String department = fields.get("department");
    String academicYear = fields.get("academicYear");
    String busId = fields.get("busId");
    String stop = fields.get("pickupStop");
    String authorized = fields.get("authorized");

    if (id == null || name == null || password == null
            || department == null || academicYear == null
            || busId == null || stop == null || authorized == null
            || id.trim().isEmpty() || id.trim().length() > 30
            || name.trim().isEmpty() || name.trim().length() > 100
            || password.length() < 8 || password.length() > 128
            || !STUDENT_DEPARTMENTS.contains(department)
            || !Arrays.asList("I Year", "II Year", "III Year", "IV Year").contains(academicYear)
            || stop.trim().isEmpty() || stop.trim().length() > 100
            || !("true".equals(authorized)
                 || "false".equals(authorized))) {
        redirect(exchange, "/management-students.html?error=invalid");
        return;
    }

    try {
        ensureStudentAcademicColumns();
        String sql = "INSERT INTO students "
                + "(student_id, name, bus_id, stop_name, transport_authorized, password_hash, department, academic_year) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection con = DatabaseConnection.getConnection()) {
            if (!registeredBusExists(con, busId)) {
                redirect(exchange, "/management-students.html?error=invalid");
                return;
            }
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, id.trim());
                ps.setString(2, name.trim());
                ps.setString(3, busId);
                ps.setString(4, stop.trim());
                ps.setBoolean(5, Boolean.parseBoolean(authorized));
                ps.setString(6, hashPassword(password));
                ps.setString(7, department);
                ps.setString(8, academicYear);

                ps.executeUpdate();
                TransportFeeService.calculateAndSaveStudentFee(con, id.trim());
            }
        }

        loadStudentsFromDatabase();
        redirect(exchange, "/management-students.html?result=student-added");
    } catch (SQLException e) {
        if ("23000".equals(e.getSQLState())) {
            redirect(exchange, "/management-students.html?error=duplicate");
        } else {
            e.printStackTrace();
            redirect(exchange, "/management-students.html?error=database");
        }
    }
}

private boolean registeredBusExists(Connection con, String busId) throws SQLException {
    try (PreparedStatement statement = con.prepareStatement(
            "SELECT 1 FROM buses WHERE bus_id = ?")) {
        statement.setString(1, busId);
        try (ResultSet result = statement.executeQuery()) {
            return result.next();
        }
    }
}

private void handleResetStudentPassword(HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendError(exchange, 405, "POST method required for password reset.");
        return;
    }
    UserSession session = currentSession(exchange);
    if (session == null || !"management".equals(session.role)) {
        sendError(exchange, 403, "Management login required to reset student passwords.");
        return;
    }

    Map<String, String> fields;
    try {
        fields = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
    } catch (Exception e) {
        sendError(exchange, 400, "Invalid form data.");
        return;
    }

    String studentId = fields.get("studentId");
    String newPassword = fields.get("newPassword");
    String confirmPassword = fields.get("confirmPassword");

    if (studentId == null || studentId.trim().isEmpty() || studentId.trim().length() > 30) {
        redirect(exchange, "/management-students.html?error=invalid-student");
        return;
    }
    studentId = studentId.trim();

    if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 128) {
        redirect(exchange, "/management-students.html?error=invalid-password&studentId=" + URLEncoder.encode(studentId, StandardCharsets.UTF_8));
        return;
    }

    if (confirmPassword == null || !newPassword.equals(confirmPassword)) {
        redirect(exchange, "/management-students.html?error=mismatch&studentId=" + URLEncoder.encode(studentId, StandardCharsets.UTF_8));
        return;
    }

    try (Connection con = DatabaseConnection.getConnection()) {
        // 1. Verify student exists in the database
        String checkSql = "SELECT student_id, name FROM students WHERE student_id = ?";
        boolean exists = false;
        try (PreparedStatement checkPs = con.prepareStatement(checkSql)) {
            checkPs.setString(1, studentId);
            try (ResultSet rs = checkPs.executeQuery()) {
                if (rs.next()) {
                    exists = true;
                }
            }
        }

        if (!exists) {
            redirect(exchange, "/management-students.html?error=student-not-found");
            return;
        }

        // 2. Hash new password using exact existing PBKDF2 algorithm
        String newHash = hashPassword(newPassword);

        // 3. Update ONLY password_hash. No other student details, records, or assignments are changed or deleted!
        String updateSql = "UPDATE students SET password_hash = ? WHERE student_id = ?";
        try (PreparedStatement updatePs = con.prepareStatement(updateSql)) {
            updatePs.setString(1, newHash);
            updatePs.setString(2, studentId);
            updatePs.executeUpdate();
        }

        System.out.println("[AUDIT] Management user " + session.userId + " successfully reset password for student: " + studentId);
        redirect(exchange, "/management-students.html?result=password-reset&studentId=" + URLEncoder.encode(studentId, StandardCharsets.UTF_8));

    } catch (SQLException e) {
        System.err.println("[ERROR] Database failure during password reset: " + e.getMessage());
        redirect(exchange, "/management-students.html?error=database");
    }
}

private void handleResetManagementPassword(HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendError(exchange, 405, "POST method required for management password reset.");
        return;
    }
    UserSession session = currentSession(exchange);
    if (session == null || !"management".equals(session.role)) {
        sendError(exchange, 403, "Management login required to reset management password.");
        return;
    }

    Map<String, String> fields;
    try {
        fields = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
    } catch (Exception e) {
        sendError(exchange, 400, "Invalid form data.");
        return;
    }

    String currentPassword = fields.get("currentPassword");
    String newPassword = fields.get("newPassword");
    String confirmPassword = fields.get("confirmPassword");
    String adminId = session.userId != null && !session.userId.trim().isEmpty() ? session.userId.trim() : "ADMIN-01";

    if (currentPassword == null || currentPassword.isEmpty()) {
        redirect(exchange, "/management-profile.html?error=invalid-current");
        return;
    }

    if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 128) {
        redirect(exchange, "/management-profile.html?error=invalid-password");
        return;
    }

    if (confirmPassword == null || !newPassword.equals(confirmPassword)) {
        redirect(exchange, "/management-profile.html?error=mismatch");
        return;
    }

    try (Connection con = DatabaseConnection.getConnection()) {
        boolean currentValid = false;
        String selectSql = "SELECT admin_id, password_hash FROM management WHERE admin_id = ?";
        try (PreparedStatement ps = con.prepareStatement(selectSql)) {
            ps.setString(1, adminId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String storedHash = rs.getString("password_hash");
                    if (storedHash != null && !storedHash.trim().isEmpty()) {
                        storedHash = storedHash.trim();
                        if (storedHash.startsWith("PBKDF2$")) {
                            currentValid = verifyPassword(currentPassword, storedHash);
                        } else if (storedHash.equals(currentPassword)) {
                            currentValid = true;
                        }
                    } else {
                        if ("ADMIN-01".equalsIgnoreCase(adminId) && "admin123".equals(currentPassword)) {
                            currentValid = true;
                        }
                    }
                } else {
                    if ("ADMIN-01".equalsIgnoreCase(adminId) && "admin123".equals(currentPassword)) {
                        currentValid = true;
                    }
                }
            }
        }

        if (!currentValid) {
            redirect(exchange, "/management-profile.html?error=wrong-current");
            return;
        }

        String newHash = hashPassword(newPassword);
        String updateSql = "UPDATE management SET password_hash = ? WHERE admin_id = ?";
        int updated = 0;
        try (PreparedStatement updatePs = con.prepareStatement(updateSql)) {
            updatePs.setString(1, newHash);
            updatePs.setString(2, adminId);
            updated = updatePs.executeUpdate();
        }
        if (updated == 0) {
            String insertSql = "INSERT INTO management (admin_id, name, role, email, phone, organization, duty, password_hash) "
                    + "VALUES (?, 'System Administrator', 'Transport Manager', 'admin@ciet.edu', '080-2699-0011', 'CIET Main Campus', 'Full Time', ?) "
                    + "ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash)";
            try (PreparedStatement insertPs = con.prepareStatement(insertSql)) {
                insertPs.setString(1, adminId);
                insertPs.setString(2, newHash);
                insertPs.executeUpdate();
            }
        }

        System.out.println("[AUDIT] Management user " + adminId + " successfully updated password.");
        redirect(exchange, "/management-profile.html?result=password-reset");

    } catch (SQLException e) {
        System.err.println("[ERROR] Database failure during management password reset: " + e.getMessage());
        redirect(exchange, "/management-profile.html?error=database");
    }
}

private void handleLoginPage(HttpExchange exchange, String fileName) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
        sendError(exchange, 405, "GET method required.");
        return;
    }
    File file = new File(fileName);
    if (!file.exists() || file.isDirectory()) {
        sendError(exchange, 404, "Login page not found.");
        return;
    }
    String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    String query = exchange.getRequestURI().getQuery();
    String msg = "";
    if (query != null) {
        String error = getQueryValue(query, "error");
        String reset = getQueryValue(query, "reset");
        String userId = getQueryValue(query, "userId");
        if ("invalid-credentials".equalsIgnoreCase(error) || "invalid".equalsIgnoreCase(error)) {
            msg = "<div class=\"registration-message registration-error\" role=\"alert\">&#9888; Incorrect ID or password. Please verify your credentials and try again.</div>";
        } else if ("success".equalsIgnoreCase(reset)) {
            String who = (userId != null && !userId.trim().isEmpty()) ? "for " + escapeHtml(userId) + " " : "";
            msg = "<div class=\"registration-message registration-success\" role=\"status\">&#10004; Password " + who + "was successfully reset using secure PBKDF2 hash. You can now log in with your new password.</div>";
        }
    }
    html = html.replace("{{loginMessage}}", msg).replace("<!-- LOGIN_MESSAGE -->", msg);
    sendHtml(exchange, html);
}

private void handleResetPassword(HttpExchange exchange) throws IOException {
    String method = exchange.getRequestMethod();
    if ("GET".equalsIgnoreCase(method)) {
        handleResetPasswordGet(exchange);
    } else if ("POST".equalsIgnoreCase(method)) {
        handleResetPasswordPost(exchange);
    } else {
        sendError(exchange, 405, "GET and POST supported for password reset.");
    }
}

private void handleResetPasswordGet(HttpExchange exchange) throws IOException {
    File file = new File("reset-password.html");
    if (!file.exists() || file.isDirectory()) {
        sendError(exchange, 404, "Password reset page not found.");
        return;
    }

    String query = exchange.getRequestURI().getQuery();
    String role = "student";
    String userId = "";
    String error = null;
    String success = null;

    if (query != null) {
        String r = getQueryValue(query, "role");
        if ("driver".equalsIgnoreCase(r)) {
            role = "driver";
        } else if ("management".equalsIgnoreCase(r)) {
            role = "management";
        }
        String u = getQueryValue(query, "userId");
        if (u != null) userId = u.trim();
        error = getQueryValue(query, "error");
        success = getQueryValue(query, "success");
    }

    String studentTab = "student".equals(role) ? "active" : "";
    String driverTab = "driver".equals(role) ? "active" : "";
    String mgmtTab = "management".equals(role) ? "active" : "";

    String userLabel;
    String userPlaceholder;
    String backLink;
    String backLabel;
    StringBuilder authBox = new StringBuilder();

    if ("student".equals(role)) {
        userLabel = "Student ID / Roll No";
        userPlaceholder = "e.g. 25CSR002";
        backLink = "student-login.html";
        backLabel = "Student Login";

        authBox.append("<div class=\"student-verification-box\">")
                .append("<h4><svg width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"><path d=\"M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2\"></path><circle cx=\"12\" cy=\"7\" r=\"4\"></circle></svg> Student Identity Verification</h4>")
                .append("<p>Enter your registered student details to securely verify your identity before resetting your password.</p>")
                .append("<div class=\"form-group\" style=\"margin-bottom: 0;\">")
                .append("<label class=\"form-label\" for=\"studentName\">Registered Full Name</label>")
                .append("<input type=\"text\" id=\"studentName\" name=\"studentName\" class=\"form-control\" placeholder=\"e.g. Akshay\" required autocomplete=\"name\">")
                .append("</div>")
                .append("</div>");

    } else if ("driver".equals(role)) {
        userLabel = "Driver ID / Badge No";
        userPlaceholder = "e.g. DRV-01";
        backLink = "driver-login.html";
        backLabel = "Driver Login";

        authBox.append("<div class=\"driver-verification-box\">")
                .append("<h4><svg width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"><circle cx=\"12\" cy=\"12\" r=\"10\"></circle><circle cx=\"12\" cy=\"12\" r=\"3\"></circle><line x1=\"12\" y1=\"2\" x2=\"12\" y2=\"9\"></line><line x1=\"4.93\" y1=\"19.07\" x2=\"9.88\" y2=\"14.12\"></line><line x1=\"19.07\" y1=\"19.07\" x2=\"14.12\" y2=\"14.12\"></line></svg> Driver Staff Verification</h4>")
                .append("<p>Enter your registered driver details to verify your staff account before resetting your password.</p>")
                .append("<div class=\"form-group\" style=\"margin-bottom: 12px;\">")
                .append("<label class=\"form-label\" for=\"driverName\">Registered Driver Full Name</label>")
                .append("<input type=\"text\" id=\"driverName\" name=\"driverName\" class=\"form-control\" placeholder=\"e.g. Ravi Shankar\" required autocomplete=\"name\">")
                .append("</div>")
                .append("<div class=\"form-group\" style=\"margin-bottom: 0;\">")
                .append("<label class=\"form-label\" for=\"driverPhone\">Registered Phone Number</label>")
                .append("<input type=\"tel\" id=\"driverPhone\" name=\"phoneNumber\" class=\"form-control\" placeholder=\"e.g. 9876543210\" required autocomplete=\"tel\">")
                .append("</div>")
                .append("</div>");

    } else {
        userLabel = "Management Admin ID";
        userPlaceholder = "e.g. ADMIN-01";
        backLink = "management-login.html";
        backLabel = "Management Login";

        authBox.append("<div class=\"management-auth-box\">")
                .append("<h4><svg width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"><rect x=\"3\" y=\"11\" width=\"18\" height=\"11\" rx=\"2\" ry=\"2\"></rect><path d=\"M7 11V7a5 5 0 0 1 10 0v4\"></path></svg> Current Management Password Verification</h4>")
                .append("<p>Please verify your current management credentials to authenticate and update your master password.</p>")
                .append("<div class=\"form-group password-field-group\" style=\"margin-bottom: 0;\">")
                .append("<label class=\"form-label\" for=\"currentPassword\">Current Password</label>")
                .append("<div class=\"password-input-wrapper\">")
                .append("<input type=\"checkbox\" id=\"toggle-current-password\" class=\"password-toggle-checkbox\" tabindex=\"-1\" aria-label=\"Toggle password visibility\">")
                .append("<input type=\"text\" id=\"currentPassword\" name=\"currentPassword\" class=\"form-control password-toggle-input\" placeholder=\"Enter current admin password\" required autocomplete=\"current-password\">")
                .append("<label for=\"toggle-current-password\" class=\"password-toggle-label\" title=\"Toggle password visibility\">")
                .append("<svg class=\"eye-icon eye-icon-open\" width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"><path d=\"M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8z\"></path><circle cx=\"12\" cy=\"12\" r=\"3\"></circle></svg>")
                .append("<svg class=\"eye-icon eye-icon-closed\" width=\"20\" height=\"20\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\"><path d=\"M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24\"></path><line x1=\"1\" y1=\"1\" x2=\"23\" y2=\"23\"></line></svg>")
                .append("</label></div></div></div>");
    }

    String msg = "";
    if (error != null) {
        if ("mismatch".equalsIgnoreCase(error)) {
            msg = "<div class=\"registration-message registration-error\" role=\"alert\">&#9888; Passwords do not match. Please re-enter identical passwords.</div>";
        } else if ("short-password".equalsIgnoreCase(error)) {
            msg = "<div class=\"registration-message registration-error\" role=\"alert\">&#9888; Password must be at least 8 characters in length.</div>";
        } else if ("user-not-found".equalsIgnoreCase(error)) {
            msg = "<div class=\"registration-message registration-error\" role=\"alert\">&#9888; The specified account ID was not found in the database.</div>";
        } else if ("verification-failed".equalsIgnoreCase(error)) {
            if ("driver".equals(role)) {
                msg = "<div class=\"registration-message registration-error\" role=\"alert\">&#9888; Verification failed: The registered driver details do not match staff records.</div>";
            } else {
                msg = "<div class=\"registration-message registration-error\" role=\"alert\">&#9888; Verification failed: The registered student name does not match college records.</div>";
            }
        } else if ("unauthorized".equalsIgnoreCase(error)) {
            msg = "<div class=\"registration-message registration-error\" role=\"alert\">&#9888; Unauthorized: Management authorization failed. Please check administrator credentials.</div>";
        } else if ("invalid-current".equalsIgnoreCase(error)) {
            msg = "<div class=\"registration-message registration-error\" role=\"alert\">&#9888; Current password verification failed. Please enter your correct current password.</div>";
        } else {
            msg = "<div class=\"registration-message registration-error\" role=\"alert\">&#9888; Could not process password reset. Please try again.</div>";
        }
    } else if ("1".equals(success)) {
        msg = "<div class=\"registration-message registration-success\" role=\"status\">&#10004; Password successfully reset using secure PBKDF2 hash.</div>";
    }

    String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
            .replace("{{studentTabActive}}", studentTab)
            .replace("{{driverTabActive}}", driverTab)
            .replace("{{managementTabActive}}", mgmtTab)
            .replace("{{currentRole}}", escapeHtml(role))
            .replace("{{userLabel}}", userLabel)
            .replace("{{userPlaceholder}}", userPlaceholder)
            .replace("{{submittedUserId}}", escapeHtml(userId))
            .replace("{{authorizationFields}}", authBox.toString())
            .replace("{{backLink}}", backLink)
            .replace("{{backLabel}}", backLabel)
            .replace("{{resetMessage}}", msg);

    sendHtml(exchange, html);
}

private void handleResetPasswordPost(HttpExchange exchange) throws IOException {
    Map<String, String> fields;
    try {
        fields = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
    } catch (Exception e) {
        redirect(exchange, "/reset-password.html?error=invalid");
        return;
    }

    String role = fields.get("role");
    if (role == null || role.trim().isEmpty()) role = "student";
    role = role.trim().toLowerCase(Locale.ROOT);

    String userId = fields.get("userId");
    if (userId == null || userId.trim().isEmpty()) {
        redirect(exchange, "/reset-password.html?role=" + role + "&error=user-not-found");
        return;
    }
    userId = userId.trim();

    String newPassword = fields.get("newPassword");
    String confirmPassword = fields.get("confirmPassword");

    if (newPassword == null || newPassword.length() < 8) {
        redirect(exchange, "/reset-password.html?role=" + role + "&error=short-password&userId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8));
        return;
    }

    if (confirmPassword == null || !newPassword.equals(confirmPassword)) {
        redirect(exchange, "/reset-password.html?role=" + role + "&error=mismatch&userId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8));
        return;
    }

    try (Connection con = DatabaseConnection.getConnection()) {
        if ("student".equals(role)) {
            // 1. Validate student exists in MySQL and retrieve registered verification attributes
            String checkSql = "SELECT student_id, name FROM students WHERE student_id = ?";
            String dbStudentName = null;
            boolean exists = false;
            try (PreparedStatement checkPs = con.prepareStatement(checkSql)) {
                checkPs.setString(1, userId);
                try (ResultSet rs = checkPs.executeQuery()) {
                    if (rs.next()) {
                        exists = true;
                        dbStudentName = rs.getString("name");
                    }
                }
            }
            if (!exists) {
                redirect(exchange, "/reset-password.html?role=student&error=user-not-found&userId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8));
                return;
            }

            // 2. Validate student identity verification details (prevent arbitrary resets merely by entering student ID)
            String studentName = fields.get("studentName");

            boolean verified = false;
            if (studentName != null && !studentName.trim().isEmpty() && dbStudentName != null) {
                if (dbStudentName.trim().equalsIgnoreCase(studentName.trim())) {
                    verified = true;
                }
            }

            // Fallback for signed-in management admin performing an in-session student reset
            UserSession session = currentSession(exchange);
            if (!verified && session != null && "management".equals(session.role)) {
                verified = true;
            }

            if (!verified) {
                System.out.println("[SECURITY] Unauthorized/unverified student password reset rejected for student: " + userId);
                redirect(exchange, "/reset-password.html?role=student&error=verification-failed&userId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8));
                return;
            }

            // 3. Update ONLY student's password_hash. Management and driver passwords remain 100% UNCHANGED.
            String newHash = hashPassword(newPassword);
            String updateSql = "UPDATE students SET password_hash = ? WHERE student_id = ?";
            try (PreparedStatement ps = con.prepareStatement(updateSql)) {
                ps.setString(1, newHash);
                ps.setString(2, userId);
                ps.executeUpdate();
            }

            System.out.println("[AUDIT] Student password reset successfully for " + userId + ". Management password untouched.");
            redirect(exchange, "/student-login.html?reset=success&userId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8));
            return;

        } else if ("driver".equals(role)) {
            // 1. Validate driver exists in MySQL and retrieve registered verification attributes
            String checkSql = "SELECT driver_id, name, phone_number, license_number FROM drivers WHERE driver_id = ?";
            String dbDriverName = null;
            String dbPhoneNumber = null;
            String dbLicenseNumber = null;
            boolean exists = false;
            try (PreparedStatement checkPs = con.prepareStatement(checkSql)) {
                checkPs.setString(1, userId);
                try (ResultSet rs = checkPs.executeQuery()) {
                    if (rs.next()) {
                        exists = true;
                        dbDriverName = rs.getString("name");
                        dbPhoneNumber = rs.getString("phone_number");
                        dbLicenseNumber = rs.getString("license_number");
                    }
                }
            }
            if (!exists) {
                redirect(exchange, "/reset-password.html?role=driver&error=user-not-found&userId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8));
                return;
            }

            // 2. Validate driver verification details (do not allow unrestricted resets merely by entering driver ID)
            String driverName = fields.get("driverName");
            String phoneNumber = fields.get("phoneNumber");
            String licenseNumber = fields.get("licenseNumber");

            boolean verified = false;
            if (driverName != null && !driverName.trim().isEmpty() && dbDriverName != null) {
                boolean nameMatches = dbDriverName.trim().equalsIgnoreCase(driverName.trim());
                boolean contactMatches = false;
                if (phoneNumber != null && !phoneNumber.trim().isEmpty() && dbPhoneNumber != null) {
                    String cleanInputPhone = phoneNumber.replaceAll("[^0-9]", "");
                    String cleanDbPhone = dbPhoneNumber.replaceAll("[^0-9]", "");
                    if (!cleanInputPhone.isEmpty() && (cleanInputPhone.equals(cleanDbPhone)
                            || cleanInputPhone.endsWith(cleanDbPhone) || cleanDbPhone.endsWith(cleanInputPhone))) {
                        contactMatches = true;
                    }
                }
                if (!contactMatches && licenseNumber != null && !licenseNumber.trim().isEmpty() && dbLicenseNumber != null) {
                    if (dbLicenseNumber.trim().equalsIgnoreCase(licenseNumber.trim())) {
                        contactMatches = true;
                    }
                }
                if (nameMatches && contactMatches) {
                    verified = true;
                }
            }

            // Fallback for signed-in management admin performing an in-session driver reset
            UserSession session = currentSession(exchange);
            if (!verified && session != null && "management".equals(session.role)) {
                verified = true;
            }

            if (!verified) {
                System.out.println("[SECURITY] Unauthorized/unverified driver password reset rejected for driver: " + userId);
                redirect(exchange, "/reset-password.html?role=driver&error=verification-failed&userId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8));
                return;
            }

            // 3. Update ONLY driver's password_hash. Management and student passwords remain 100% UNCHANGED.
            String newHash = hashPassword(newPassword);
            String updateSql = "UPDATE drivers SET password_hash = ? WHERE driver_id = ?";
            try (PreparedStatement ps = con.prepareStatement(updateSql)) {
                ps.setString(1, newHash);
                ps.setString(2, userId);
                ps.executeUpdate();
            }

            System.out.println("[AUDIT] Driver password reset successfully for " + userId + ". Management password untouched.");
            redirect(exchange, "/driver-login.html?reset=success&userId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8));
            return;

        } else if ("management".equals(role)) {
            // Validate management credentials using current password
            String currentPassword = fields.get("currentPassword");
            boolean authorized = false;
            if (currentPassword != null && !currentPassword.isEmpty()) {
                authorized = authenticateManagementUser(userId, currentPassword);
            }
            UserSession session = currentSession(exchange);
            if (!authorized && session != null && "management".equals(session.role) && userId.equalsIgnoreCase(session.userId)) {
                authorized = true;
            }

            if (!authorized) {
                System.out.println("[SECURITY] Unauthorized management password reset rejected for admin: " + userId);
                redirect(exchange, "/reset-password.html?role=management&error=invalid-current&userId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8));
                return;
            }

            String newHash = hashPassword(newPassword);
            String updateSql = "UPDATE management SET password_hash = ? WHERE admin_id = ?";
            int updated = 0;
            try (PreparedStatement ps = con.prepareStatement(updateSql)) {
                ps.setString(1, newHash);
                ps.setString(2, userId);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                String insertSql = "INSERT INTO management (admin_id, name, role, email, phone, organization, duty, password_hash) "
                        + "VALUES (?, 'System Administrator', 'Transport Manager', 'admin@ciet.edu', '080-2699-0011', 'CIET Main Campus', 'Full Time', ?) "
                        + "ON DUPLICATE KEY UPDATE password_hash = VALUES(password_hash)";
                try (PreparedStatement insertPs = con.prepareStatement(insertSql)) {
                    insertPs.setString(1, userId);
                    insertPs.setString(2, newHash);
                    insertPs.executeUpdate();
                }
            }

            System.out.println("[AUDIT] Management password updated successfully for " + userId + ". Student/driver passwords untouched.");
            redirect(exchange, "/management-login.html?reset=success&userId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8));
            return;

        } else {
            redirect(exchange, "/reset-password.html?error=invalid");
        }
    } catch (SQLException e) {
        System.err.println("[ERROR] Database failure during password reset: " + e.getMessage());
        redirect(exchange, "/reset-password.html?role=" + role + "&error=database&userId=" + URLEncoder.encode(userId, StandardCharsets.UTF_8));
    }
}

private void handleAddDriver(HttpExchange exchange) throws IOException {
    if (!"POST".equals(exchange.getRequestMethod())) {
        sendError(exchange, 405, "POST method required.");
        return;
    }

    UserSession session = currentSession(exchange);

    if (session == null || (!"management".equals(session.role)
            && !"driver".equals(session.role))) {
        sendError(exchange, 403, "A signed-in driver or management account is required to register a driver.");
        return;
    }

    Map<String, String> fields;

    try {
        fields = parseFormData(
            new String(readRequestBody(exchange), StandardCharsets.UTF_8)
        );
    } catch (Exception e) {
        sendError(exchange, 400, "Invalid driver form.");
        return;
    }

    String id = fields.get("driverId");
    String name = fields.get("driverName");
    if (name == null || name.trim().isEmpty()) {
        name = fields.get("name");
    }
    String password = fields.get("password");
    String phone = fields.get("phoneNumber");
    if (phone == null || phone.trim().isEmpty()) {
        phone = fields.get("phone");
    }
    String busId = fields.get("busId");
    String email = fields.get("email");
    String address = fields.get("address");
    String licenseNumber = fields.get("licenseNumber");
    if (licenseNumber == null || licenseNumber.trim().isEmpty()) {
        licenseNumber = fields.get("license_number");
    }
    String licenseClass = fields.get("licenseClass");
    if (licenseClass == null || licenseClass.trim().isEmpty()) {
        licenseClass = fields.get("license_class");
    }
    String licenseExpiry = fields.get("licenseExpiry");
    if (licenseExpiry == null || licenseExpiry.trim().isEmpty()) {
        licenseExpiry = fields.get("license_expiry");
    }
    String normalizedPhone = phone == null ? null : phone.trim();
    boolean phoneIsValid = normalizedPhone == null || normalizedPhone.isEmpty()
            ? !"driver".equals(session.role)
            : normalizedPhone.matches("[+0-9() -]{7,32}")
                    && normalizedPhone.replaceAll("[^0-9]", "").length() >= 7;

    String dashboard = "driver".equals(session.role)
            ? "/driver-dashboard.html" : "/management-drivers.html";
    if (id == null || name == null || password == null || busId == null
            || id.trim().isEmpty() || id.trim().length() > 30
            || name.trim().isEmpty() || name.trim().length() > 100
            || password.length() < 8 || password.length() > 128
            || !phoneIsValid) {
        redirect(exchange, dashboard + "?error=invalid");
        return;
    }

    try {
        ensureDriverPhoneColumn();
    } catch (SQLException e) {
        e.printStackTrace();
        redirect(exchange, dashboard + "?error=database");
        return;
    }

    try (Connection con = DatabaseConnection.getConnection()) {
        if (!registeredBusExists(con, busId)) {
            redirect(exchange, dashboard + "?error=invalid");
            return;
        }
        String sql = "INSERT INTO drivers "
                + "(driver_id, name, bus_id, password_hash, phone_number, email, address, license_number, license_class, license_expiry) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, id.trim());
            ps.setString(2, name.trim());
            ps.setString(3, busId);
            ps.setString(4, hashPassword(password));
            if (normalizedPhone == null || normalizedPhone.isEmpty()) {
                ps.setNull(5, java.sql.Types.VARCHAR);
            } else {
                ps.setString(5, normalizedPhone);
            }
            ps.setString(6, email != null && !email.trim().isEmpty() ? email.trim() : null);
            ps.setString(7, address != null && !address.trim().isEmpty() ? address.trim() : null);
            ps.setString(8, licenseNumber != null && !licenseNumber.trim().isEmpty() ? licenseNumber.trim() : null);
            ps.setString(9, licenseClass != null && !licenseClass.trim().isEmpty() ? licenseClass.trim() : null);
            ps.setString(10, licenseExpiry != null && !licenseExpiry.trim().isEmpty() ? licenseExpiry.trim() : null);
            ps.executeUpdate();
        }
        loadDriversFromDatabase();
        redirect(exchange, dashboard + "?result=driver-added");
    } catch (SQLException e) {
        if ("23000".equals(e.getSQLState())) {
            redirect(exchange, dashboard + "?error=duplicate");
        } else {
            e.printStackTrace();
            redirect(exchange, dashboard + "?error=database");
        }
    }
}

        private void clearSession(HttpExchange exchange) throws IOException {
            String token = sessionToken(exchange);
            if (token != null) {
                SESSIONS.remove(token);
            }
            exchange.getResponseHeaders().add("Set-Cookie",
                    "ciet_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
        }

        private String sessionToken(HttpExchange exchange) {
            String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
            if (cookieHeader == null) {
                return null;
            }
            for (String cookie : cookieHeader.split(";")) {
                String[] pair = cookie.trim().split("=", 2);
                if (pair.length == 2 && "ciet_session".equals(pair[0])) {
                    return pair[1];
                }
            }
            return null;
        }

        private UserSession currentSession(HttpExchange exchange) {
            String token = sessionToken(exchange);
            return token == null ? null : SESSIONS.get(token);
        }

        private void redirect(HttpExchange exchange, String location) throws IOException {
            exchange.getResponseHeaders().set("Location", location);
            exchange.sendResponseHeaders(303, -1);
            exchange.close();
        }

        private void handleManagementProfilePage(HttpExchange exchange) throws IOException {
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }

            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                Map<String, String> fields;
                try {
                    fields = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
                    String name = fields.get("name");
                    String email = fields.get("email");
                    String phone = fields.get("phone");
                    String organization = fields.get("organization");
                    String duty = fields.get("duty");
                    String sql = "UPDATE management SET name = ?, email = ?, phone = ?, organization = ?, duty = ? WHERE admin_id = ?";
                    try (Connection con = DatabaseConnection.getConnection();
                         PreparedStatement ps = con.prepareStatement(sql)) {
                        ps.setString(1, name != null && !name.trim().isEmpty() ? name.trim() : "Transport Administrator");
                        ps.setString(2, email != null && !email.trim().isEmpty() ? email.trim() : null);
                        ps.setString(3, phone != null && !phone.trim().isEmpty() ? phone.trim() : null);
                        ps.setString(4, organization != null && !organization.trim().isEmpty() ? organization.trim() : null);
                        ps.setString(5, duty != null && !duty.trim().isEmpty() ? duty.trim() : null);
                        ps.setString(6, session.userId);
                        ps.executeUpdate();
                    }
                    redirect(exchange, "/management-profile.html?saved=1");
                    return;
                } catch (Exception e) {
                    redirect(exchange, "/management-profile.html?error=invalid");
                    return;
                }
            }

            File file = new File("management-profile.html");
            if (!file.exists() || file.isDirectory()) {
                sendError(exchange, 404, "Management profile page not found.");
                return;
            }

            String name = "Transport Directorate";
            String role = "Transport Administrator";
            String email = "admin@ciet.edu";
            String phone = "080-2699-0011";
            String organization = "CIET Main Campus";
            String duty = "Morning Shift";
            String photo = null;

            String sql = "SELECT admin_id, name, role, email, phone, organization, duty, profile_photo FROM management WHERE admin_id = ?";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, session.userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String n = rs.getString("name");
                        if (n != null && !n.trim().isEmpty()) name = n.trim();
                        String r = rs.getString("role");
                        if (r != null && !r.trim().isEmpty()) role = r.trim();
                        String e = rs.getString("email");
                        if (e != null && !e.trim().isEmpty()) email = e.trim();
                        String p = rs.getString("phone");
                        if (p != null && !p.trim().isEmpty()) phone = p.trim();
                        String org = rs.getString("organization");
                        if (org != null && !org.trim().isEmpty()) organization = org.trim();
                        String d = rs.getString("duty");
                        if (d != null && !d.trim().isEmpty()) duty = d.trim();
                        photo = rs.getString("profile_photo");
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }

            String query = exchange.getRequestURI().getQuery();
            String result = getQueryValue(query, "result");
            String error = getQueryValue(query, "error");
            String msg = "";
            if ("password-reset".equals(result)) {
                msg = "<p class=\"registration-message registration-success\" role=\"status\">&#10004; Management password updated successfully with PBKDF2 hash.</p>";
            } else if ("wrong-current".equals(error)) {
                msg = "<p class=\"registration-message registration-error\" role=\"alert\">&#9888; Current password does not match. Please verify your current password.</p>";
            } else if ("mismatch".equals(error)) {
                msg = "<p class=\"registration-message registration-error\" role=\"alert\">&#9888; New password and confirmation do not match.</p>";
            } else if ("invalid-password".equals(error)) {
                msg = "<p class=\"registration-message registration-error\" role=\"alert\">&#9888; New password must be at least 8 characters long.</p>";
            } else if ("invalid-current".equals(error)) {
                msg = "<p class=\"registration-message registration-error\" role=\"alert\">&#9888; Current password is required.</p>";
            } else if ("database".equals(error)) {
                msg = "<p class=\"registration-message registration-error\" role=\"alert\">&#9888; A database error occurred while updating the password.</p>";
            } else if ("1".equals(getQueryValue(query, "saved"))) {
                msg = "<p class=\"registration-message registration-success\" role=\"status\">Profile details saved successfully.</p>";
            }

            String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                    .replace("{{adminId}}", escapeHtml(session.userId))
                    .replace("{{name}}", escapeHtml(name))
                    .replace("{{role}}", escapeHtml(role))
                    .replace("{{email}}", escapeHtml(email))
                    .replace("{{phone}}", escapeHtml(phone))
                    .replace("{{organization}}", escapeHtml(organization))
                    .replace("{{duty}}", escapeHtml(duty))
                    .replace("{{profilePhotoUrl}}", resolvePhotoUrl(photo))
                    .replace("{{saveMessage}}", msg)
                    .replace("<!-- SAVE_MESSAGE -->", "");

            sendHtml(exchange, html);
        }

        private void handleProfileRequest(HttpExchange exchange, String fileName) throws IOException {
            UserSession session = currentSession(exchange);
            if ("driver-profile.html".equals(fileName)) {
                if (session == null || !"driver".equals(session.role)) {
                    redirect(exchange, "/driver-login.html");
                    return;
                }
                if ("POST".equals(exchange.getRequestMethod())) {
                    saveDriverProfile(exchange, session);
                    return;
                }
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Only GET and POST are supported for profile pages.");
                    return;
                }
                renderDriverProfilePage(exchange, session);
                return;
            } else if ("student-profile.html".equals(fileName)) {
                if (session == null || !"student".equals(session.role)) {
                    redirect(exchange, "/student-login.html");
                    return;
                }
                if ("POST".equals(exchange.getRequestMethod())) {
                    saveStudentProfile(exchange, session);
                    return;
                }
                if (!"GET".equals(exchange.getRequestMethod())) {
                    sendError(exchange, 405, "Only GET and POST are supported for profile pages.");
                    return;
                }
                renderStudentProfilePage(exchange, session);
                return;
            }
            sendError(exchange, 404, "Profile page not found.");
        }

        private void renderDriverProfilePage(HttpExchange exchange, UserSession session) throws IOException {
            File file = new File("driver-profile.html");
            if (!file.exists() || file.isDirectory()) {
                sendError(exchange, 404, "Driver profile page not found.");
                return;
            }

            String driverId = session.userId;
            String name = "Not provided";
            String phone = "Not provided";
            String email = "Not provided";
            String address = "Not provided";
            String licenseNumber = "Not provided";
            String licenseClass = "Not provided";
            String licenseExpiry = "Not provided";
            String busId = null;
            String busNumber = null;
            String photo = null;

            String sql = "SELECT d.driver_id, d.name, d.phone_number, d.email, d.address, d.license_number, d.license_class, d.license_expiry, d.profile_photo, d.bus_id, b.bus_number "
                    + "FROM drivers d LEFT JOIN buses b ON b.bus_id = d.bus_id "
                    + "WHERE d.driver_id = ?";

            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, session.userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String n = rs.getString("name");
                        if (n != null && !n.trim().isEmpty()) name = n.trim();
                        String p = rs.getString("phone_number");
                        if (p != null && !p.trim().isEmpty()) phone = p.trim();
                        String em = rs.getString("email");
                        if (em != null && !em.trim().isEmpty()) email = em.trim();
                        String addr = rs.getString("address");
                        if (addr != null && !addr.trim().isEmpty()) address = addr.trim();
                        String ln = rs.getString("license_number");
                        if (ln != null && !ln.trim().isEmpty()) licenseNumber = ln.trim();
                        String lc = rs.getString("license_class");
                        if (lc != null && !lc.trim().isEmpty()) licenseClass = lc.trim();
                        String le = rs.getString("license_expiry");
                        if (le != null && !le.trim().isEmpty()) licenseExpiry = le.trim();
                        photo = rs.getString("profile_photo");
                        busId = rs.getString("bus_id");
                        busNumber = rs.getString("bus_number");
                    } else {
                        sendError(exchange, 404, "Driver profile not found in database.");
                        return;
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
                sendError(exchange, 500, "Database error loading driver profile.");
                return;
            }

            String assignedBus;
            if (busNumber != null && !busNumber.trim().isEmpty()) {
                assignedBus = busNumber.trim();
            } else if (busId != null && !busId.trim().isEmpty()) {
                assignedBus = busId.trim();
            } else {
                assignedBus = "Not assigned";
            }

            String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                    .replace("{{driverId}}", escapeHtml(driverId))
                    .replace("{{staffId}}", escapeHtml(driverId))
                    .replace("{{name}}", escapeHtml(name))
                    .replace("{{phone}}", escapeHtml(phone))
                    .replace("{{email}}", escapeHtml(email))
                    .replace("{{address}}", escapeHtml(address))
                    .replace("{{assignedBus}}", escapeHtml(assignedBus))
                    .replace("{{licenseNumber}}", escapeHtml(licenseNumber))
                    .replace("{{licenseClass}}", escapeHtml(licenseClass))
                    .replace("{{licenseExpiry}}", escapeHtml(licenseExpiry))
                    .replace("{{profilePhotoUrl}}", resolvePhotoUrl(photo))
                    .replace("{{nameInput}}", escapeHtml("Not provided".equals(name) ? "" : name))
                    .replace("{{phoneInput}}", escapeHtml("Not provided".equals(phone) ? "" : phone))
                    .replace("{{emailInput}}", escapeHtml("Not provided".equals(email) ? "" : email))
                    .replace("{{addressInput}}", escapeHtml("Not provided".equals(address) ? "" : address))
                    .replace("{{licenseNumberInput}}", escapeHtml("Not provided".equals(licenseNumber) ? "" : licenseNumber))
                    .replace("{{licenseClassInput}}", escapeHtml("Not provided".equals(licenseClass) ? "" : licenseClass))
                    .replace("{{licenseExpiryInput}}", escapeHtml("Not provided".equals(licenseExpiry) ? "" : licenseExpiry));

            String query = exchange.getRequestURI().getQuery();
            String msg = "1".equals(getQueryValue(query, "saved"))
                    ? "<p class=\"registration-message registration-success\" role=\"status\">Profile details saved successfully.</p>"
                    : "";
            html = html.replace("{{saveMessage}}", msg)
                    .replace("<!-- SAVE_MESSAGE -->", msg);

            sendHtml(exchange, html);
        }

        private void saveDriverProfile(HttpExchange exchange, UserSession session) throws IOException {
            Map<String, String> fields;
            try {
                fields = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
            } catch (Exception e) {
                sendError(exchange, 400, "The profile form could not be read.");
                return;
            }

            String name = fields.get("name");
            if (name == null || name.trim().isEmpty()) {
                name = fields.get("driverName");
            }
            String phone = fields.get("phone");
            if (phone == null || phone.trim().isEmpty()) {
                phone = fields.get("phoneNumber");
            }
            String email = fields.get("email");
            String address = fields.get("address");
            String licenseNumber = fields.get("licenseNumber");
            if (licenseNumber == null || licenseNumber.trim().isEmpty()) {
                licenseNumber = fields.get("license_number");
            }
            String licenseClass = fields.get("licenseClass");
            if (licenseClass == null || licenseClass.trim().isEmpty()) {
                licenseClass = fields.get("license_class");
            }
            String licenseExpiry = fields.get("licenseExpiry");
            if (licenseExpiry == null || licenseExpiry.trim().isEmpty()) {
                licenseExpiry = fields.get("license_expiry");
            }

            if (name == null || name.trim().isEmpty()) {
                sendError(exchange, 400, "Driver name is required.");
                return;
            }

            String sql = "UPDATE drivers SET name = ?, phone_number = ?, email = ?, address = ?, license_number = ?, license_class = ?, license_expiry = ? WHERE driver_id = ?";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, name.trim());
                ps.setString(2, phone != null && !phone.trim().isEmpty() ? phone.trim() : null);
                ps.setString(3, email != null && !email.trim().isEmpty() ? email.trim() : null);
                ps.setString(4, address != null && !address.trim().isEmpty() ? address.trim() : null);
                ps.setString(5, licenseNumber != null && !licenseNumber.trim().isEmpty() ? licenseNumber.trim() : null);
                ps.setString(6, licenseClass != null && !licenseClass.trim().isEmpty() ? licenseClass.trim() : null);
                ps.setString(7, licenseExpiry != null && !licenseExpiry.trim().isEmpty() ? licenseExpiry.trim() : null);
                ps.setString(8, session.userId);
                ps.executeUpdate();
            } catch (SQLException e) {
                e.printStackTrace();
                sendError(exchange, 500, "Could not update driver profile.");
                return;
            }

            redirect(exchange, "/driver-profile.html?saved=1");
        }

        private void renderStudentProfilePage(HttpExchange exchange, UserSession session) throws IOException {
            File file = new File("student-profile.html");
            if (!file.exists() || file.isDirectory()) {
                sendError(exchange, 404, "Student profile page not found.");
                return;
            }

            String studentId = session.userId;
            String name = "Not provided";
            String busId = null;
            String busNumber = null;
            String pickupStop = "Not provided";
            String department = "Not provided";
            String academicYear = "Not provided";
            boolean transportAuthorized = false;
            String photo = null;

            String sql = "SELECT s.student_id, s.name, s.bus_id, s.stop_name, s.transport_authorized, "
                    + "s.department, s.academic_year, s.profile_photo, b.bus_number "
                    + "FROM students s LEFT JOIN buses b ON b.bus_id = s.bus_id "
                    + "WHERE s.student_id = ?";

            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, session.userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String n = rs.getString("name");
                        if (n != null && !n.trim().isEmpty()) name = n.trim();
                        busId = rs.getString("bus_id");
                        busNumber = rs.getString("bus_number");
                        String stop = rs.getString("stop_name");
                        if (stop != null && !stop.trim().isEmpty()) pickupStop = stop.trim();
                        String d = rs.getString("department");
                        if (d != null && !d.trim().isEmpty()) department = d.trim();
                        String y = rs.getString("academic_year");
                        if (y != null && !y.trim().isEmpty()) academicYear = y.trim();
                        transportAuthorized = rs.getBoolean("transport_authorized");
                        photo = rs.getString("profile_photo");
                    } else {
                        sendError(exchange, 404, "Student profile not found in database.");
                        return;
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
                sendError(exchange, 500, "Database error loading student profile.");
                return;
            }

            String assignedBus;
            if (busNumber != null && !busNumber.trim().isEmpty()) {
                assignedBus = busNumber.trim();
            } else if (busId != null && !busId.trim().isEmpty()) {
                assignedBus = busId.trim();
            } else {
                assignedBus = "Not assigned";
            }

            String role = "Student" + (!"Not provided".equals(academicYear) ? ", " + academicYear : "");
            String transportStatus = transportAuthorized ? "Active - Authorized Pass" : "Not Authorized";
            String phone = "Not provided";
            String email = "Not provided";
            String address = "Not provided";

            String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
                    .replace("{{studentId}}", escapeHtml(studentId))
                    .replace("{{name}}", escapeHtml(name))
                    .replace("{{role}}", escapeHtml(role))
                    .replace("{{department}}", escapeHtml(department))
                    .replace("{{assignedBus}}", escapeHtml(assignedBus))
                    .replace("{{pickupStop}}", escapeHtml(pickupStop))
                    .replace("{{transportStatus}}", escapeHtml(transportStatus))
                    .replace("{{phone}}", escapeHtml(phone))
                    .replace("{{email}}", escapeHtml(email))
                    .replace("{{address}}", escapeHtml(address))
                    .replace("{{profilePhotoUrl}}", resolvePhotoUrl(photo));

            String query = exchange.getRequestURI().getQuery();
            String msg = "1".equals(getQueryValue(query, "saved"))
                    ? "<p class=\"registration-message registration-success\" role=\"status\">Profile details saved successfully.</p>"
                    : "";
            html = html.replace("{{saveMessage}}", msg)
                    .replace("<!-- SAVE_MESSAGE -->", msg);

            sendHtml(exchange, html);
        }

        private void saveStudentProfile(HttpExchange exchange, UserSession session) throws IOException {
            Map<String, String> fields;
            try {
                fields = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
            } catch (Exception e) {
                sendError(exchange, 400, "The profile form could not be read.");
                return;
            }

            String name = fields.get("name");
            String pickupStop = fields.get("pickupStop");

            if (name == null || name.trim().isEmpty()) {
                sendError(exchange, 400, "Student name is required.");
                return;
            }

            String sql = "UPDATE students SET name = ?, stop_name = ? WHERE student_id = ?";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, name.trim());
                ps.setString(2, pickupStop != null && !pickupStop.trim().isEmpty() ? pickupStop.trim() : null);
                ps.setString(3, session.userId);
                ps.executeUpdate();
                TransportFeeService.calculateAndSaveStudentFee(con, session.userId);
            } catch (SQLException e) {
                e.printStackTrace();
                sendError(exchange, 500, "Could not update student profile.");
                return;
            }

            redirect(exchange, "/student-profile.html?saved=1");
        }

        private void handleDriverComplaintsPage(HttpExchange exchange) throws IOException {
            UserSession session = currentSession(exchange);
            if (session == null || !"driver".equals(session.role)) {
                redirect(exchange, "/driver-login.html");
                return;
            }

            String method = exchange.getRequestMethod();
            if ("POST".equals(method)) {
                handleDriverComplaintSubmission(exchange, session);
                return;
            }
            if (!"GET".equals(method)) {
                sendError(exchange, 405, "Method not allowed.");
                return;
            }

            File file = new File("driver-complaints.html");
            if (!file.exists() || file.isDirectory()) {
                sendError(exchange, 404, "Complaints page not found.");
                return;
            }

            String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            StringBuilder complaintsHtml = new StringBuilder();
            String sql = "SELECT complaint_id, subject, description, status, management_response, created_at "
                    + "FROM complaints WHERE submitted_by = ? AND role = 'driver' "
                    + "ORDER BY created_at DESC, complaint_id DESC";

            int count = 0;
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, session.userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        count++;
                        int id = rs.getInt("complaint_id");
                        String subject = defaultDisplay(rs.getString("subject"));
                        String description = defaultDisplay(rs.getString("description"));
                        String status = rs.getString("status");
                        if (status == null || status.trim().isEmpty()) {
                            status = "Pending";
                        }
                        String statusClass;
                        if ("Resolved".equalsIgnoreCase(status)) {
                            statusClass = "status-resolved";
                        } else if ("In Progress".equalsIgnoreCase(status) || "Under Review".equalsIgnoreCase(status)) {
                            statusClass = "status-review";
                        } else {
                            statusClass = "status-pending";
                        }
                        String response = rs.getString("management_response");
                        Timestamp createdAt = rs.getTimestamp("created_at");
                        String dateStr = createdAt != null ? createdAt.toLocalDateTime().toLocalDate().toString() : "";

                        complaintsHtml.append("<article class=\"complaint-item\">")
                                .append("<div class=\"complaint-item-header\">")
                                .append("<strong class=\"complaint-item-title\">").append(escapeHtml(subject)).append("</strong>")
                                .append("<span class=\"complaint-status ").append(statusClass).append("\">").append(escapeHtml(status)).append("</span>")
                                .append("</div>")
                                .append("<div class=\"complaint-item-meta\">CMP-").append(id)
                                .append(dateStr.isEmpty() ? "" : " &bull; Submitted " + escapeHtml(dateStr)).append("</div>")
                                .append("<p>").append(escapeHtml(description)).append("</p>");

                        if (response != null && !response.trim().isEmpty()) {
                            complaintsHtml.append("<div class=\"management-response\">")
                                    .append("<strong>Management Response:</strong> ")
                                    .append(escapeHtml(response))
                                    .append("</div>");
                        }
                        complaintsHtml.append("</article>\n");
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
                sendError(exchange, 500, "Database error loading complaints.");
                return;
            }

            String listContent;
            if (count == 0) {
                listContent = "<p class=\"registration-empty\">No complaints available.</p>";
            } else {
                listContent = complaintsHtml.toString();
            }

            String query = exchange.getRequestURI().getQuery();
            String msg = "";
            if ("1".equals(getQueryValue(query, "submitted"))) {
                msg = "<p class=\"registration-message registration-success\" role=\"status\">Complaint submitted successfully.</p>";
            } else if ("invalid".equals(getQueryValue(query, "error"))) {
                msg = "<p class=\"registration-message registration-error\" role=\"alert\">Please enter both subject and description.</p>";
            } else if ("database".equals(getQueryValue(query, "error"))) {
                msg = "<p class=\"registration-message registration-error\" role=\"alert\">Could not save complaint. Please try again.</p>";
            }

            html = html.replace("{{complaintItems}}", listContent)
                    .replace("<!-- COMPLAINT_ITEMS -->", "")
                    .replace("{{submissionMessage}}", msg)
                    .replace("<!-- SUBMISSION_MESSAGE -->", "");

            sendHtml(exchange, html);
        }

        private void handleDriverComplaintSubmission(HttpExchange exchange, UserSession session) throws IOException {
            Map<String, String> fields;
            try {
                fields = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
            } catch (Exception e) {
                redirect(exchange, "/driver-complaints.html?error=invalid");
                return;
            }

            String subject = fields.get("subject");
            String description = fields.get("description");

            if (subject == null || subject.trim().isEmpty() || description == null || description.trim().isEmpty()) {
                redirect(exchange, "/driver-complaints.html?error=invalid");
                return;
            }

            subject = subject.trim();
            if (subject.length() > 150) {
                subject = subject.substring(0, 150);
            }
            description = description.trim();

            String sql = "INSERT INTO complaints (submitted_by, role, subject, description, status, management_response, created_at) "
                    + "VALUES (?, 'driver', ?, ?, 'Pending', NULL, NOW())";

            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, session.userId);
                ps.setString(2, subject);
                ps.setString(3, description);
                ps.executeUpdate();
                redirect(exchange, "/driver-complaints.html?submitted=1");
            } catch (SQLException e) {
                e.printStackTrace();
                redirect(exchange, "/driver-complaints.html?error=database");
            }
        }

        private void handleStudentComplaintsPage(HttpExchange exchange) throws IOException {
            UserSession session = currentSession(exchange);
            if (session == null || !"student".equals(session.role)) {
                redirect(exchange, "/student-login.html");
                return;
            }

            String method = exchange.getRequestMethod();
            if ("POST".equals(method)) {
                handleStudentComplaintSubmission(exchange, session);
                return;
            }
            if (!"GET".equals(method)) {
                sendError(exchange, 405, "Method not allowed.");
                return;
            }

            File file = new File("student-complaints.html");
            if (!file.exists() || file.isDirectory()) {
                sendError(exchange, 404, "Complaints page not found.");
                return;
            }

            String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            StringBuilder complaintsHtml = new StringBuilder();
            String sql = "SELECT complaint_id, subject, description, status, management_response, created_at "
                    + "FROM complaints WHERE submitted_by = ? AND role = 'student' "
                    + "ORDER BY created_at DESC, complaint_id DESC";

            int count = 0;
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, session.userId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        count++;
                        int id = rs.getInt("complaint_id");
                        String subject = defaultDisplay(rs.getString("subject"));
                        String description = defaultDisplay(rs.getString("description"));
                        String status = rs.getString("status");
                        if (status == null || status.trim().isEmpty()) {
                            status = "Pending";
                        }
                        String statusClass;
                        if ("Resolved".equalsIgnoreCase(status)) {
                            statusClass = "status-resolved";
                        } else if ("In Progress".equalsIgnoreCase(status) || "Under Review".equalsIgnoreCase(status)) {
                            statusClass = "status-review";
                        } else {
                            statusClass = "status-pending";
                        }
                        String response = rs.getString("management_response");
                        Timestamp createdAt = rs.getTimestamp("created_at");
                        String dateStr = createdAt != null ? createdAt.toLocalDateTime().toLocalDate().toString() : "";

                        complaintsHtml.append("<article class=\"complaint-item\">")
                                .append("<div class=\"complaint-item-header\">")
                                .append("<strong class=\"complaint-item-title\">").append(escapeHtml(subject)).append("</strong>")
                                .append("<span class=\"complaint-status ").append(statusClass).append("\">").append(escapeHtml(status)).append("</span>")
                                .append("</div>")
                                .append("<div class=\"complaint-item-meta\">CMP-").append(id)
                                .append(dateStr.isEmpty() ? "" : " &bull; Submitted " + escapeHtml(dateStr)).append("</div>")
                                .append("<p>").append(escapeHtml(description)).append("</p>");

                        if (response != null && !response.trim().isEmpty()) {
                            complaintsHtml.append("<div class=\"management-response\">")
                                    .append("<strong>Management Response:</strong> ")
                                    .append(escapeHtml(response))
                                    .append("</div>");
                        }
                        complaintsHtml.append("</article>\n");
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
                sendError(exchange, 500, "Database error loading complaints.");
                return;
            }

            String listContent;
            if (count == 0) {
                listContent = "<p class=\"registration-empty\">No complaints available.</p>";
            } else {
                listContent = complaintsHtml.toString();
            }

            String query = exchange.getRequestURI().getQuery();
            String msg = "";
            if ("1".equals(getQueryValue(query, "submitted"))) {
                msg = "<p class=\"registration-message registration-success\" role=\"status\">Complaint submitted successfully.</p>";
            } else if ("invalid".equals(getQueryValue(query, "error"))) {
                msg = "<p class=\"registration-message registration-error\" role=\"alert\">Please enter both subject and description.</p>";
            } else if ("database".equals(getQueryValue(query, "error"))) {
                msg = "<p class=\"registration-message registration-error\" role=\"alert\">Could not save complaint. Please try again.</p>";
            }

            if (html.contains("{{complaintItems}}")) {
                html = html.replace("{{complaintItems}}", listContent).replace("<!-- COMPLAINT_ITEMS -->", "");
            } else {
                html = html.replace("<!-- COMPLAINT_ITEMS -->", listContent);
            }
            if (html.contains("{{submissionMessage}}")) {
                html = html.replace("{{submissionMessage}}", msg).replace("<!-- SUBMISSION_MESSAGE -->", "");
            } else {
                html = html.replace("<!-- SUBMISSION_MESSAGE -->", msg);
            }

            sendHtml(exchange, html);
        }

        private void handleStudentComplaintSubmission(HttpExchange exchange, UserSession session) throws IOException {
            Map<String, String> fields;
            try {
                fields = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
            } catch (Exception e) {
                redirect(exchange, "/student-complaints.html?error=invalid");
                return;
            }

            String subject = fields.get("subject");
            String description = fields.get("description");

            if (subject == null || subject.trim().isEmpty() || description == null || description.trim().isEmpty()) {
                redirect(exchange, "/student-complaints.html?error=invalid");
                return;
            }

            subject = subject.trim();
            if (subject.length() > 150) {
                subject = subject.substring(0, 150);
            }
            description = description.trim();

            String sql = "INSERT INTO complaints (submitted_by, role, subject, description, status, management_response, created_at) "
                    + "VALUES (?, 'student', ?, ?, 'Pending', NULL, NOW())";

            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, session.userId);
                ps.setString(2, subject);
                ps.setString(3, description);
                ps.executeUpdate();
                redirect(exchange, "/student-complaints.html?submitted=1");
            } catch (SQLException e) {
                e.printStackTrace();
                redirect(exchange, "/student-complaints.html?error=database");
            }
        }

        private void handleManagementComplaintsPage(HttpExchange exchange) throws IOException {
            UserSession session = currentSession(exchange);
            if (session == null || !"management".equals(session.role)) {
                sendError(exchange, 403, "Management login required.");
                return;
            }

            String method = exchange.getRequestMethod();
            if ("POST".equals(method)) {
                handleManagementComplaintUpdate(exchange);
                return;
            }
            if (!"GET".equals(method)) {
                sendError(exchange, 405, "Method not allowed.");
                return;
            }

            File file = new File("management-complaints.html");
            if (!file.exists() || file.isDirectory()) {
                sendError(exchange, 404, "Page not found.");
                return;
            }

            String html = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            StringBuilder complaintsHtml = new StringBuilder();
            String sql = "SELECT complaint_id, submitted_by, role, subject, description, status, management_response, created_at "
                    + "FROM complaints ORDER BY created_at DESC, complaint_id DESC";

            int count = 0;
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    count++;
                    int id = rs.getInt("complaint_id");
                    String submittedBy = defaultDisplay(rs.getString("submitted_by"));
                    String role = defaultDisplay(rs.getString("role"));
                    String subject = defaultDisplay(rs.getString("subject"));
                    String description = defaultDisplay(rs.getString("description"));
                    String status = rs.getString("status");
                    if (status == null || status.trim().isEmpty()) {
                        status = "Pending";
                    }
                    String statusClass;
                    if ("Resolved".equalsIgnoreCase(status)) {
                        statusClass = "status-resolved";
                    } else if ("In Progress".equalsIgnoreCase(status) || "Under Review".equalsIgnoreCase(status)) {
                        statusClass = "status-review";
                    } else {
                        statusClass = "status-pending";
                    }
                    String response = rs.getString("management_response");
                    Timestamp createdAt = rs.getTimestamp("created_at");
                    String dateStr = createdAt != null ? createdAt.toLocalDateTime().toLocalDate().toString() : "";

                    complaintsHtml.append("<article class=\"complaint-review-item\">")
                            .append("<div class=\"row\">")
                            .append("<div><strong>Complaint ID</strong>CMP-").append(id).append("</div>")
                            .append("<div><strong>Submitted By</strong>").append(escapeHtml(submittedBy)).append(" (").append(escapeHtml(role)).append(")</div>")
                            .append("<div><strong>Date</strong>").append(escapeHtml(dateStr)).append("</div>")
                            .append("<div><strong>Status</strong><span class=\"complaint-status ").append(statusClass).append("\">").append(escapeHtml(status)).append("</span></div>")
                            .append("</div>")
                            .append("<h3 class=\"complaint-item-title\">").append(escapeHtml(subject)).append("</h3>")
                            .append("<p>").append(escapeHtml(description)).append("</p>");

                    if (response != null && !response.trim().isEmpty()) {
                        complaintsHtml.append("<div class=\"management-response\" style=\"margin-bottom:12px;\">")
                                .append("<strong>Current Response:</strong> ").append(escapeHtml(response))
                                .append("</div>");
                    }

                    complaintsHtml.append("<form action=\"/management-complaints.html\" method=\"post\" class=\"complaint-review-actions\">")
                            .append("<input type=\"hidden\" name=\"complaintId\" value=\"").append(id).append("\">")
                            .append("<div class=\"complaint-field\"><label>Update Status</label>")
                            .append("<select name=\"status\">")
                            .append("<option value=\"Pending\"").append("Pending".equalsIgnoreCase(status) ? " selected" : "").append(">Pending</option>")
                            .append("<option value=\"In Progress\"").append("In Progress".equalsIgnoreCase(status) || "Under Review".equalsIgnoreCase(status) ? " selected" : "").append(">In Progress</option>")
                            .append("<option value=\"Resolved\"").append("Resolved".equalsIgnoreCase(status) ? " selected" : "").append(">Resolved</option>")
                            .append("</select></div>")
                            .append("<div class=\"complaint-field\"><label>Management Response</label>")
                            .append("<input type=\"text\" name=\"response\" value=\"").append(escapeHtml(response == null ? "" : response)).append("\" placeholder=\"Enter response\"></div>")
                            .append("<button type=\"submit\" class=\"btn btn-primary\" style=\"grid-column: span 2; margin-top: 8px;\">Update Complaint</button>")
                            .append("</form></article>\n");
                }
            } catch (SQLException e) {
                e.printStackTrace();
                sendError(exchange, 500, "Database error loading complaints.");
                return;
            }

            String listContent;
            if (count == 0) {
                listContent = "<p class=\"registration-empty\">No complaints available.</p>";
            } else {
                listContent = complaintsHtml.toString();
            }

            String query = exchange.getRequestURI().getQuery();
            String msg = "";
            if ("1".equals(getQueryValue(query, "updated"))) {
                msg = "<p class=\"registration-message registration-success\" role=\"status\">Complaint updated successfully.</p>";
            }

            html = html.replace("{{complaintReviewItems}}", listContent)
                    .replace("<!-- COMPLAINT_REVIEW_ITEMS -->", "")
                    .replace("{{updateMessage}}", msg)
                    .replace("<!-- UPDATE_MESSAGE -->", "");

            sendHtml(exchange, html);
        }

        private void handleManagementComplaintUpdate(HttpExchange exchange) throws IOException {
            Map<String, String> fields;
            try {
                fields = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
            } catch (Exception e) {
                redirect(exchange, "/management-complaints.html?error=invalid");
                return;
            }

            String complaintIdStr = fields.get("complaintId");
            String status = fields.get("status");
            String response = fields.get("response");

            if (complaintIdStr == null || status == null) {
                redirect(exchange, "/management-complaints.html?error=invalid");
                return;
            }

            try {
                int complaintId = Integer.parseInt(complaintIdStr);
                String sql = "UPDATE complaints SET status = ?, management_response = ? WHERE complaint_id = ?";
                try (Connection con = DatabaseConnection.getConnection();
                     PreparedStatement ps = con.prepareStatement(sql)) {
                    ps.setString(1, status);
                    ps.setString(2, response != null && !response.trim().isEmpty() ? response.trim() : null);
                    ps.setInt(3, complaintId);
                    ps.executeUpdate();
                }
                redirect(exchange, "/management-complaints.html?updated=1");
            } catch (Exception e) {
                e.printStackTrace();
                redirect(exchange, "/management-complaints.html?error=database");
            }
        }

        private Map<String, String> parseFormData(String encodedForm) {
            Map<String, String> fields = new HashMap<>();
            if (encodedForm.isEmpty()) {
                throw new IllegalArgumentException("Empty profile form.");
            }
            for (String pair : encodedForm.split("&")) {
                int separator = pair.indexOf('=');
                if (separator < 0) {
                    throw new IllegalArgumentException("Malformed profile form.");
                }
                String key = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
                String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
                if (fields.put(key, value) != null) {
                    throw new IllegalArgumentException("Duplicate profile field.");
                }
            }
            return fields;
        }

        private byte[] readRequestBody(HttpExchange exchange) throws IOException {
            byte[] body = exchange.getRequestBody().readNBytes(16385);
            if (body.length > 16384) {
                throw new IOException("Profile form exceeds the permitted size.");
            }
            return body;
        }

        private String getQueryValue(String query, String key) {
            if (query == null) {
                return null;
            }
            for (String pair : query.split("&")) {
                int separator = pair.indexOf('=');
                if (separator > 0 && key.equals(pair.substring(0, separator))) {
                    return pair.substring(separator + 1);
                }
            }
            return null;
        }

        private String escapeHtml(String value) {
            if (value == null) return "";
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                    .replace("\"", "&quot;").replace("'", "&#39;");
        }

        private void sendError(HttpExchange exchange, int status, String message) throws IOException {
            byte[] response = message.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        }

        private void handleAttendanceRequest(HttpExchange exchange, String fileName) throws IOException {
            UserSession session = currentSession(exchange);
            
System.out.println("Attendance URL: " + fileName);
System.out.println("Session found: " + (session != null));

if (session != null) {
    System.out.println("Session role: " + session.role);
    System.out.println("Session user ID: " + session.userId);
}

            String requiredRole = "student-attendance.html".equals(fileName) ? "student"
                    : "driver-attendance.html".equals(fileName) ? "driver" : "management";
            if (session == null || !requiredRole.equals(session.role)) {
                sendError(exchange, 403, "Please sign in with an authorized " + requiredRole + " account to view attendance.");
                return;
            }

            if ("POST".equals(exchange.getRequestMethod())) {
                if ("student".equals(requiredRole)) {
                    submitStudentAttendance(exchange, session);
                } else if ("management".equals(requiredRole)) {
                    correctAttendance(exchange);
                } else {
                    sendError(exchange, 405, "Drivers have read-only access to attendance.");
                }
                return;
            }
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendError(exchange, 405, "Only GET and POST are supported for attendance.");
                return;
            }

            File page = new File(fileName);
            if (!page.exists() || page.isDirectory()) {
                sendError(exchange, 404, "Attendance page not found.");
                return;
            }

            String html = new String(Files.readAllBytes(page.toPath()), StandardCharsets.UTF_8);
            if ("student".equals(requiredRole)) {
                html = renderStudentAttendance(html, session, exchange.getRequestURI().getQuery());
            } else if ("driver".equals(requiredRole)) {
                html = renderDriverAttendance(html, session);
            } else {
                try {
                    html = renderManagementAttendance(html, exchange.getRequestURI().getQuery());
                } catch (IllegalArgumentException exception) {
                    sendError(exchange, 400, exception.getMessage());
                    return;
                }
            }
            byte[] response = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        }

        private void submitStudentAttendance(HttpExchange exchange, UserSession session) throws IOException {
            Map<String, String> fields;
            try {
                fields = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException exception) {
                sendError(exchange, 400, "The attendance form could not be read.");
                return;
            }
            String status = fields.get("status");
            if (!"PRESENT".equals(status) && !"ABSENT".equals(status)) {
                sendError(exchange, 400, "Select Present or Absent before submitting.");
                return;
            }
            String reason = fields.get("reason");
            if ("ABSENT".equals(status) && (reason == null || reason.trim().isEmpty())) {
                redirect(exchange, "/student-attendance.html?error=reason-required");
                return;
            }
            if ("PRESENT".equals(status)) {
                reason = null;
            } else if (reason != null) {
                reason = reason.trim();
            }

            Student student = STUDENTS.get(session.userId);
            if (student == null) {
                loadStudentsFromDatabase();
                student = STUDENTS.get(session.userId);
            }
            if (student == null) {
                sendError(exchange, 403, "The signed-in account is not a registered student.");
                return;
            }
            LocalDate attDate = LocalDate.now();
            String formDate = fields.get("date");
            if (formDate != null && !formDate.trim().isEmpty()) {
                try {
                    attDate = LocalDate.parse(formDate.trim());
                } catch (Exception ignore) {}
            }
            String result = "saved";
            try {
                attendanceStore.upsert(student, attDate, status, reason);
                saveAttendanceToDatabase(student.id, attDate, status, reason);
            } catch (IOException exception) {
                sendError(exchange, 500, "Attendance could not be saved. Please try again.");
                return;
            }
            recalculateRouteAfterAttendance(student.bus, attDate, attendanceStore);
            redirect(exchange, "/student-attendance.html?result=" + result);
        }

        private void saveAttendanceToDatabase(String studentId, LocalDate date, String status, String reason) {
            String sql = "INSERT INTO attendance (student_id, attendance_date, status, marked_by, absence_reason) "
                    + "VALUES (?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE status = VALUES(status), absence_reason = VALUES(absence_reason)";
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setString(1, studentId);
                ps.setDate(2, java.sql.Date.valueOf(date));
                ps.setString(3, "PRESENT".equalsIgnoreCase(status) ? "Present" : "Absent");
                ps.setString(4, studentId);
                if (reason != null && !reason.trim().isEmpty()) {
                    ps.setString(5, reason.trim());
                } else {
                    ps.setNull(5, java.sql.Types.VARCHAR);
                }
                ps.executeUpdate();
            } catch (SQLException e) {
                System.err.println("[WARN] Could not persist attendance to MySQL: " + e.getMessage());
            }
        }

        private void correctAttendance(HttpExchange exchange) throws IOException {
            Map<String, String> fields;
            try {
                fields = parseFormData(new String(readRequestBody(exchange), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException exception) {
                sendError(exchange, 400, "The correction form could not be read.");
                return;
            }
            String studentId = fields.get("studentId");
            String dateText = fields.get("date");
            String status = fields.get("status");
            if (!STUDENTS.containsKey(studentId) || (!"PRESENT".equals(status) && !"ABSENT".equals(status))) {
                sendError(exchange, 400, "Select a registered student and a valid attendance status.");
                return;
            }
            LocalDate date;
            try {
                date = LocalDate.parse(dateText);
            } catch (RuntimeException exception) {
                sendError(exchange, 400, "Enter a valid attendance date.");
                return;
            }
            try {
                attendanceStore.correct(studentId, date, status);
                saveAttendanceToDatabase(studentId, date, status, null);
            } catch (AttendanceNotFoundException exception) {
                sendError(exchange, 404, "The attendance record to correct no longer exists.");
                return;
            } catch (IOException exception) {
                sendError(exchange, 500, "The attendance correction could not be saved.");
                return;
            }
            recalculateRouteAfterAttendance(STUDENTS.get(studentId).bus, date, attendanceStore);
            redirect(exchange, "/management-attendance.html?result=corrected");
        }

        private void recalculateRouteAfterAttendance(
                String busId, LocalDate date, AttendanceStore attendanceStore) {
            if (busId == null || busId.trim().isEmpty()) {
                return;
            }
            try {
                Map<String, String> statuses = new HashMap<>();
                for (Attendance attendance : attendanceStore.all()) {
                    if (date.equals(attendance.date)) {
                        statuses.put(attendance.studentId, attendance.status);
                    }
                }
                RoutePlanner.BusRoute route = ROUTE_PLANNER.calculate(busId, statuses);
                try {
                    ROUTE_PLANNER.recalculateAndSaveSharedRoute(busId, date);
                } catch (Exception ex) {
                    System.err.println("[WARN] Real road route recalculation error: " + ex.getMessage());
                }
                if (!route.available) {
                    System.out.println("[ROUTE] Attendance saved for bus " + busId
                            + "; route pending configuration: " + route.message);
                } else {
                    System.out.println("[ROUTE] Recalculated bus " + busId
                            + " after attendance update. " + route.updateReason);
                }
            } catch (SQLException exception) {
                System.err.println("[ERROR] Attendance was saved, but route recalculation failed for bus "
                        + busId + ".");
                exception.printStackTrace();
            }
        }

        private String renderStudentAttendance(String html, UserSession session, String query) throws IOException {
            Student student = STUDENTS.get(session.userId);
            System.out.println("Attendance student ID: " + session.userId);
System.out.println("Student found in map: " + (student != null));
            if (student == null) {
                throw new IOException("Signed-in student is not registered.");
            }
            LocalDate today = LocalDate.now();
            Attendance todayRecord = attendanceStore.find(student.id, today);
            String todayStatus;
            if (todayRecord == null) {
                todayStatus = "<span class=\"attendance-empty\">No attendance submitted for today.</span>";
            } else {
                todayStatus = statusBadge(todayRecord.status);
                if (todayRecord.reason != null && !todayRecord.reason.trim().isEmpty()) {
                    todayStatus += " <span class=\"attendance-reason\">(" + escapeHtml(todayRecord.reason) + ")</span>";
                }
            }
            String result = queryValue(query, "result");
            String error = queryValue(query, "error");
            String message = "";
            if ("saved".equals(result)) {
                message = "<p class=\"attendance-message\" role=\"status\">Attendance submitted for " + today + ".</p>";
            } else if ("duplicate".equals(result)) {
                message = "<p class=\"attendance-message\" role=\"status\">Attendance was already submitted for today. Contact management for an authorized correction.</p>";
            } else if ("reason-required".equals(error)) {
                message = "<p class=\"attendance-message registration-error\" role=\"alert\">Reason for absence is required when marking Absent.</p>";
            }
            return html.replace("{{studentName}}", escapeHtml(student.name))
                    .replace("{{studentId}}", escapeHtml(student.id))
                    .replace("{{today}}", today.toString())
                    .replace("{{todayStatus}}", todayStatus)
                    .replace("{{attendanceMessage}}", message)
                    .replace("{{attendanceRows}}", attendanceRows(attendanceStore.forStudent(student.id), "student"));
        }

       private String renderDriverAttendance(String html, UserSession session) throws IOException {
            String bus = DRIVER_BUSES.get(session.userId);
            if (bus == null) {
                throw new IOException("Signed-in driver is not assigned to a bus.");
            }
            List<Attendance> records = attendanceStore.all();
            records.removeIf(record -> !record.bus.equals(bus));
            return html.replace("{{driverId}}", escapeHtml(session.userId))
                    .replace("{{driverBus}}", escapeHtml(bus))
                    .replace("{{today}}", LocalDate.now().toString())
                    .replace("{{attendanceRows}}", attendanceRows(records, "driver"));
        }

        private String renderManagementAttendance(String html, String query) throws IOException {
            String dateFilter = queryValue(query, "date");
            String busFilter = queryValue(query, "bus");
            String statusFilter = queryValue(query, "status");
            if (dateFilter != null && !dateFilter.isEmpty()) {
                try {
                    LocalDate.parse(dateFilter);
                } catch (DateTimeParseException exception) {
                    throw new IllegalArgumentException("Enter a valid attendance date filter.");
                }
            }
            if (busFilter != null && !busFilter.isEmpty()
                    && !"Bus #01".equals(busFilter) && !"Bus #02".equals(busFilter) && !"Bus #03".equals(busFilter)) {
throw new IllegalArgumentException("Select a valid bus filter.");
            }
            if (statusFilter != null && !statusFilter.isEmpty()
                    && !"PRESENT".equals(statusFilter) && !"ABSENT".equals(statusFilter)) {
                throw new IllegalArgumentException("Select a valid attendance status filter.");
            }

            List<Attendance> records = attendanceStore.all();
            records.removeIf(record -> (!isBlank(dateFilter) && !record.date.toString().equals(dateFilter))
                    || (!isBlank(busFilter) && !record.bus.equals(busFilter))
                    || (!isBlank(statusFilter) && !record.status.equals(statusFilter)));
            String message = "corrected".equals(queryValue(query, "result"))
                    ? "<p class=\"attendance-message\" role=\"status\">Attendance correction saved.</p>" : "";
            return html.replace("{{dateFilter}}", escapeHtml(defaultString(dateFilter)))
                    .replace("{{busOptions}}", busOptions(busFilter))
                    .replace("{{statusOptions}}", statusOptions(statusFilter))
                    .replace("{{attendanceMessage}}", message)
                    .replace("{{attendanceRows}}", attendanceRows(records, "management"));
        }

        private String attendanceRows(List<Attendance> records, String role) {
            int cols = "management".equals(role) ? 7 : "student".equals(role) ? 6 : 5;
            if (records.isEmpty()) {
                return "<tr><td colspan=\"" + cols
                        + "\" class=\"attendance-empty\">No attendance records available.</td></tr>";
            }
            StringBuilder rows = new StringBuilder();
            for (Attendance record : records) {
                Student student = STUDENTS.get(record.studentId);
                if (student == null) {
                    continue;
                }
                rows.append("<tr><td>").append(escapeHtml(student.id)).append("</td><td>")
                        .append(escapeHtml(student.name)).append("</td>");
                if ("management".equals(role)) {
                    rows.append("<td>").append(escapeHtml(student.bus)).append("</td>");
                }
                rows.append("<td>").append(escapeHtml(student.pickupStop)).append("</td><td>")
                        .append(escapeHtml(record.date.toString())).append("</td><td>")
                        .append(statusBadge(record.status)).append("</td>");
                if ("management".equals(role)) {
                    rows.append("<td><form class=\"attendance-correction-form\" method=\"post\" action=\"management-attendance.html\">")
                            .append("<input type=\"hidden\" name=\"studentId\" value=\"").append(escapeHtml(student.id)).append("\">")
                            .append("<input type=\"hidden\" name=\"date\" value=\"").append(record.date).append("\">")
                            .append("<select class=\"form-control\" name=\"status\" aria-label=\"Correct attendance status\">")
                            .append("<option value=\"PRESENT\"").append("PRESENT".equals(record.status) ? " selected" : "")
                            .append(">Present</option><option value=\"ABSENT\"").append("ABSENT".equals(record.status) ? " selected" : "")
                            .append(">Absent</option></select><button class=\"btn btn-secondary\" type=\"submit\">Save Correction</button></form></td>");
                } else if ("student".equals(role)) {
                    String reasonText = record.reason != null && !record.reason.trim().isEmpty() ? record.reason : "-";
                    rows.append("<td>").append(escapeHtml(reasonText)).append("</td>");
                }
                rows.append("</tr>");
            }
            return rows.toString();
        }

        private String statusBadge(String status) {
            boolean present = "PRESENT".equals(status);
            String label = present ? "Present" : "Absent";
            return "<span class=\"badge attendance-status " + (present ? "attendance-present" : "attendance-absent")
                    + "\">" + label + "</span>";
        }

        private String busOptions(String selectedBus) {
            StringBuilder options = new StringBuilder("<option value=\"\">All buses</option>");
            for (int busNumber = 1; busNumber <= 3; busNumber++) {
                String bus = String.format("Bus #%02d", busNumber);
                options.append("<option value=\"").append(bus).append("\"")
                        .append(bus.equals(selectedBus) ? " selected" : "").append(">")
                        .append(bus).append("</option>");
            }
            return options.toString();
        }

        private String statusOptions(String selectedStatus) {
            return "<option value=\"\">All statuses</option><option value=\"PRESENT\""
                    + ("PRESENT".equals(selectedStatus) ? " selected" : "")
                    + ">Present</option><option value=\"ABSENT\""
                    + ("ABSENT".equals(selectedStatus) ? " selected" : "") + ">Absent</option>";
        }

        private String queryValue(String query, String key) {
            String value = getQueryValue(query, key);
            if (value == null) {
                return "";
            }
            try {
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                return "";
            }
        }

        private String defaultString(String value) {
            return value == null ? "" : value;
        }

        private boolean isBlank(String value) {
            return value == null || value.isEmpty();
        }

        /**
         * Resolves the appropriate MIME content-type header for the browser.
         */
        private String getContentType(String fileName) {
            if (fileName.endsWith(".html")) {
                return "text/html; charset=UTF-8";
            } else if (fileName.endsWith(".css")) {
                return "text/css; charset=UTF-8";
            } else if (fileName.endsWith(".js")) {
                return "application/javascript; charset=UTF-8";
            } else if (fileName.endsWith(".svg")) {
                return "image/svg+xml";
            } else if (fileName.endsWith(".png")) {
                return "image/png";
            } else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
                return "image/jpeg";
            }
            return "text/plain; charset=UTF-8";
        }

        /**
         * Logs user navigation and application events to the terminal.
         */
        private void logNavigationEvent(String fileName, String query) {
            switch (fileName) {
                case "index.html":
                    System.out.println("[NAV] Home Page accessed -> Displaying Login Role Cards");
                    break;
                case "student-login.html":
                    System.out.println("[NAV] Student Login Page accessed");
                    break;
                case "driver-login.html":
                    System.out.println("[NAV] Driver Login Page accessed");
                    break;
                case "management-login.html":
                    System.out.println("[NAV] Management Login Page accessed");
                    break;
                case "student-dashboard.html":
                    System.out.println("[AUTH & NAV] Student Logged In -> Serving Student Dashboard (Bus #02, Jayadeva Stop)");
                    break;
                case "driver-dashboard.html":
                    System.out.println("[AUTH & NAV] Driver Logged In -> Serving Driver Console (Bus #02, 40-Seat Capacity)");
                    break;
                case "management-dashboard.html":
                    System.out.println("[AUTH & NAV] Management Logged In -> Serving Master Command (40 Students, 3 Buses, 10 Stops)");
                    break;
                case "style.css":
                    // Silent serving of styles to keep terminal clean
                    break;
                default:
                    System.out.println("[REQUEST] Serving asset: " + fileName);
                    break;
            }
        }
    }

    private static class Student {
        private final String id;
        private final String name;
        private final String bus;
        private final String pickupStop;

        private Student(String id, String name, String bus, String pickupStop) {
            this.id = id;
            this.name = name;
            this.bus = bus;
            this.pickupStop = pickupStop;
        }
    }

private static void loadStudentsFromDatabase() {
    String sql = "SELECT student_id, name, bus_id, stop_name, transport_authorized FROM students";

    try (Connection con = DatabaseConnection.getConnection();
         PreparedStatement ps = con.prepareStatement(sql);
         ResultSet rs = ps.executeQuery()) {

        STUDENTS.clear();

        while (rs.next()) {
            String id = rs.getString("student_id");
            String name = rs.getString("name");
            String busId = rs.getString("bus_id");
            String stop = rs.getString("stop_name");
            boolean authorized = rs.getBoolean("transport_authorized");

            if (authorized && id != null && name != null) {
                String bus = busId == null ? "" : busId.trim();

                Student student = new Student(
                        id.trim(),
                        name,
                        bus,
                        stop == null ? "" : stop
                );

                STUDENTS.put(student.id, student);
            }
        }

        System.out.println(
                "[OK] Loaded " + STUDENTS.size()
                        + " authorized students from MySQL."
        );

    } catch (SQLException e) {
        System.err.println("[ERROR] Could not load students from MySQL.");
        e.printStackTrace();
    }
}

    private static class UserSession {
        private final String role;
        private final String userId;

        private UserSession(String role, String userId) {
            this.role = role;
            this.userId = userId;
        }
    }

    private static class Attendance {
        private final String studentId;
        private final String bus;
        private final String pickupStop;
        private final LocalDate date;
        private final String status;
        private final String reason;

        private Attendance(String studentId, String bus, String pickupStop, LocalDate date, String status, String reason) {
            this.studentId = studentId;
            this.bus = bus;
            this.pickupStop = pickupStop;
            this.date = date;
            this.status = status;
            this.reason = reason;
        }

        private Attendance(String studentId, String bus, String pickupStop, LocalDate date, String status) {
            this(studentId, bus, pickupStop, date, status, null);
        }
    }

    static class AttendanceStore {
        private final Path file;
        private final Map<String, Attendance> records = new HashMap<>();

        AttendanceStore(Path file) throws IOException {
            this.file = file;
            load();
        }

        private synchronized void upsert(Student student, LocalDate date, String status, String reason)
                throws IOException {
            String key = recordKey(student.id, date);
            Attendance previous = records.get(key);
            Attendance record = new Attendance(student.id, student.bus, student.pickupStop, date, status, reason);
            records.put(key, record);
            try {
                persist();
            } catch (IOException exception) {
                if (previous != null) {
                    records.put(key, previous);
                } else {
                    records.remove(key);
                }
                throw exception;
            }
        }

        private synchronized void submit(Student student, LocalDate date, String status, String reason)
                throws IOException, DuplicateAttendanceException {
            String key = recordKey(student.id, date);
            if (records.containsKey(key)) {
                throw new DuplicateAttendanceException();
            }
            Attendance record = new Attendance(student.id, student.bus, student.pickupStop, date, status, reason);
            records.put(key, record);
            try {
                persist();
            } catch (IOException exception) {
                records.remove(key);
                throw exception;
            }
        }

        private synchronized void submit(Student student, LocalDate date, String status)
                throws IOException, DuplicateAttendanceException {
            submit(student, date, status, null);
        }

        private synchronized void correct(String studentId, LocalDate date, String status)
                throws IOException, AttendanceNotFoundException {
            String key = recordKey(studentId, date);
            Attendance previous = records.get(key);
            if (previous == null) {
                throw new AttendanceNotFoundException();
            }
            String reason = "PRESENT".equals(status) ? null : previous.reason;
            records.put(key, new Attendance(previous.studentId, previous.bus, previous.pickupStop,
                    previous.date, status, reason));
            try {
                persist();
            } catch (IOException exception) {
                records.put(key, previous);
                throw exception;
            }
        }

        private synchronized Attendance find(String studentId, LocalDate date) {
            return records.get(recordKey(studentId, date));
        }

        private synchronized List<Attendance> forStudent(String studentId) {
            List<Attendance> result = new ArrayList<>();
            for (Attendance record : records.values()) {
                if (studentId.equals(record.studentId)) {
                    result.add(record);
                }
            }
            sortRecords(result);
            return result;
        }

        private synchronized List<Attendance> all() {
            List<Attendance> result = new ArrayList<>(records.values());
            sortRecords(result);
            return result;
        }

        private void sortRecords(List<Attendance> attendance) {
            attendance.sort(Comparator.comparing((Attendance record) -> record.date).reversed()
                    .thenComparing(record -> record.studentId));
        }

        private String recordKey(String studentId, LocalDate date) {
            return studentId + "|" + date;
        }

        private void load() throws IOException {
            if (Files.exists(file)) {
                Properties stored = new Properties();
                try (java.io.InputStream input = Files.newInputStream(file)) {
                    stored.load(input);
                }
                for (String key : stored.stringPropertyNames()) {
                    int separator = key.lastIndexOf('|');
                    if (separator < 0) {
                        continue;
                    }
                    String studentId = key.substring(0, separator);
                    Student student = STUDENTS.get(studentId);
                    if (student == null) {
                        continue;
                    }
                    LocalDate date;
                    try {
                        date = LocalDate.parse(key.substring(separator + 1));
                    } catch (DateTimeParseException exception) {
                        continue;
                    }
                    String val = stored.getProperty(key);
                    String status = val;
                    String reason = null;
                    if (val != null && val.contains("|")) {
                        String[] parts = val.split("\\|", 2);
                        status = parts[0];
                        reason = parts[1];
                    }
                    if (!"PRESENT".equals(status) && !"ABSENT".equals(status)) {
                        continue;
                    }
                    records.put(key, new Attendance(studentId, student.bus, student.pickupStop, date, status, reason));
                }
            }

            // Sync from MySQL attendance table
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(
                         "SELECT student_id, attendance_date, status, absence_reason FROM attendance");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String studentId = rs.getString("student_id");
                    java.sql.Date sqlDate = rs.getDate("attendance_date");
                    if (studentId == null || sqlDate == null) continue;
                    LocalDate date = sqlDate.toLocalDate();
                    String status = rs.getString("status");
                    String reason = rs.getString("absence_reason");
                    Student student = STUDENTS.get(studentId);
                    if (student != null) {
                        String key = recordKey(studentId, date);
                        String normStatus = "Present".equalsIgnoreCase(status) ? "PRESENT" : "ABSENT";
                        records.put(key, new Attendance(studentId, student.bus, student.pickupStop, date, normStatus, reason));
                    }
                }
            } catch (SQLException e) {
                System.err.println("[WARN] Could not sync attendance from MySQL: " + e.getMessage());
            }
        }

        private void persist() throws IOException {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Properties stored = new Properties();
            for (Attendance record : records.values()) {
                String val = record.status;
                if (record.reason != null && !record.reason.trim().isEmpty()) {
                    val += "|" + record.reason.trim();
                }
                stored.setProperty(recordKey(record.studentId, record.date), val);
            }
            Path temporary = Files.createTempFile(parent, "attendance-", ".tmp");
            try {
                try (java.io.OutputStream output = Files.newOutputStream(temporary)) {
                    stored.store(output, "CIET student attendance records");
                }
                try {
                    Files.move(temporary, file.toAbsolutePath(),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, file.toAbsolutePath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static class DuplicateAttendanceException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static class AttendanceNotFoundException extends Exception {
        private static final long serialVersionUID = 1L;
    }

    private static class Profile {
        private final String name;
        private final String phone;
        private final String email;
        private final String address;
        private final String pickupStop;
        private final String licenseNumber;
        private final String licenseClass;
        private final String licenseExpiry;

        private Profile(String name, String phone, String email, String address,
                        String pickupStop, String licenseNumber, String licenseClass, String licenseExpiry) {
            this.name = name;
            this.phone = phone;
            this.email = email;
            this.address = address;
            this.pickupStop = pickupStop;
            this.licenseNumber = licenseNumber;
            this.licenseClass = licenseClass;
            this.licenseExpiry = licenseExpiry;
        }
    }

    /**
     * Automatically attempts to launch the user's default web browser.
     */
    private static void launchBrowser(String url) {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(new URI(url));
                System.out.println("[SYSTEM] Browser automatically opened to " + url);
            } catch (Exception e) {
                System.out.println("[INFO] Browser could not be opened automatically: " + e.getMessage());
                System.out.println("Please open your browser manually and visit: " + url);
            }
        } else {
            System.out.println("[INFO] Desktop browsing not directly supported on this system.");
            System.out.println("Please open your browser manually and visit: " + url);
        }
    }

    /**
     * Prints an attractive console banner with project case study details.
     */
    private static void printProjectBanner() {
        System.out.println("================================================================================");
        System.out.println("   SMART COLLEGE TRANSPORTATION MANAGEMENT SYSTEM - SMART TRANSPORT MANAGEMENT ");
        System.out.println("================================================================================");
        System.out.println(" [OK] Java Application Server successfully started on Port " + PORT);
        System.out.println(" [OK] Browser URL: " + SERVER_URL);
        System.out.println("--------------------------------------------------------------------------------");
        System.out.println(" CASE STUDY HIGHLIGHTS (CSE 2nd Year Front-End Mini Project):                  ");
        System.out.println("   * 1 Central Campus: Central Institute of Engineering & Technology (CIET)    ");
        System.out.println("   * 3 College Fleet Buses: Bus #01 (North), Bus #02 (South), Bus #03 (Central)");
        System.out.println("   * 10 Central Pickup Stops with Ordered Schedules                            ");
        System.out.println("   * Registered student accounts loaded from the transport_db MySQL database  ");
        System.out.println("   * Bus Capacity Limit: 40 Seats Max per vehicle                              ");
        System.out.println("   * Smart Stop-Skipping Rule: Stop #06 Skipped when 0 students waiting        ");
        System.out.println("   * Breakdown Alert System: Bus #03 Clutch Overhaul / Relief Van R-01 Active  ");
        System.out.println("   * Technologies Used: HTML, CSS, and Java only (No JavaScript)               ");
        System.out.println("================================================================================");
        System.out.println(" Press Ctrl+C in this terminal window to stop the Java server at any time.     ");
        System.out.println("================================================================================");
    }
}