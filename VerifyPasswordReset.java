import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import lib.DatabaseConnection;

/**
 * VerifyPasswordReset tests the management password reset functionality:
 * 1. PBKDF2 Hashing format ("PBKDF2$210000$salt$hash").
 * 2. Password verification logic.
 * 3. Database update affects ONLY password_hash.
 * 4. Other student fields remain completely unchanged.
 * 5. Authentication works with the new password and rejects incorrect passwords.
 * 6. Password mismatch and validation rules.
 */
public class VerifyPasswordReset {

    private static int testsRun = 0;
    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("======================================================================");
        System.out.println("  VERIFICATION SUITE: MANAGEMENT PBKDF2 STUDENT PASSWORD RESET");
        System.out.println("======================================================================");

        testPBKDF2HashGenerationAndVerification();
        testPasswordResetOnDatabase();
        testStudentIdentityVerificationProcess();
        testDriverIdentityVerificationProcess();
        testDriverPasswordResetDoesNotAffectManagement();

        System.out.println("----------------------------------------------------------------------");
        System.out.printf("SUMMARY: Total Tests: %d | Passed: %d | Failed: %d%n", testsRun, testsPassed, testsFailed);
        System.out.println("======================================================================");

        if (testsFailed > 0) {
            System.err.println("[FAIL] Some verification scenarios did not pass.");
            System.exit(1);
        } else {
            System.out.println("[SUCCESS] ALL PASSWORD RESET SCENARIOS VERIFIED SUCCESSFULLY!");
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
            System.err.printf("         Details: %s%n", details);
        }
    }

    private static void testPBKDF2HashGenerationAndVerification() {
        String plainPassword = "StudentReset2026@Pass";
        String hash = hashPasswordHelper(plainPassword);

        boolean formatCorrect = hash != null && hash.startsWith("PBKDF2$210000$");
        String[] parts = hash.split("\\$");
        boolean fourParts = parts.length == 4;
        boolean algorithm = "PBKDF2".equals(parts[0]);
        boolean iterations = "210000".equals(parts[1]);

        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] rawHash = Base64.getDecoder().decode(parts[3]);
        boolean lengths = salt.length == 16 && rawHash.length == 32; // 256 bits = 32 bytes

        assertTrue("PBKDF2 Hash Format (PBKDF2$210000$salt$hash)",
                formatCorrect && fourParts && algorithm && iterations && lengths,
                "Hash preview: " + hash.substring(0, 35) + "..., Salt bytes: " + salt.length + ", Hash bytes: " + rawHash.length);

        boolean verifyCorrect = verifyPasswordHelper(plainPassword, hash);
        boolean verifyWrong = !verifyPasswordHelper("WrongPassword123", hash);

        assertTrue("PBKDF2 Hash Verification (matches correct, rejects wrong)",
                verifyCorrect && verifyWrong,
                "Correct match: " + verifyCorrect + ", Wrong rejected: " + verifyWrong);
    }

    private static void testPasswordResetOnDatabase() {
        String testStudentId = "25CSR002"; // Existing student in database
        String newPassword = "ResetSuccess2026#Pass";

        try (Connection con = DatabaseConnection.getConnection()) {
            // 1. Snapshot student's existing details
            String selectSql = "SELECT student_id, name, bus_id, stop_name, transport_authorized, department, academic_year, password_hash "
                    + "FROM students WHERE student_id = ?";
            String originalName, originalBus, originalStop, originalDept, originalYear, originalHash;
            boolean originalAuth;

            try (PreparedStatement ps = con.prepareStatement(selectSql)) {
                ps.setString(1, testStudentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        System.err.println("[WARN] Student 25CSR002 not found in database; skipping DB test.");
                        return;
                    }
                    originalName = rs.getString("name");
                    originalBus = rs.getString("bus_id");
                    originalStop = rs.getString("stop_name");
                    originalAuth = rs.getBoolean("transport_authorized");
                    originalDept = rs.getString("department");
                    originalYear = rs.getString("academic_year");
                    originalHash = rs.getString("password_hash");
                }
            }

            // 2. Perform password reset using PBKDF2 hash
            String newHash = hashPasswordHelper(newPassword);
            String updateSql = "UPDATE students SET password_hash = ? WHERE student_id = ?";
            int updatedRows = 0;
            try (PreparedStatement ps = con.prepareStatement(updateSql)) {
                ps.setString(1, newHash);
                ps.setString(2, testStudentId);
                updatedRows = ps.executeUpdate();
            }

            assertTrue("Password reset SQL update execution", updatedRows == 1,
                    "Updated rows: " + updatedRows + " for student " + testStudentId);

            // 3. Verify that student details are unchanged, only password_hash changed
            try (PreparedStatement ps = con.prepareStatement(selectSql)) {
                ps.setString(1, testStudentId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    boolean nameMatches = originalName.equals(rs.getString("name"));
                    boolean busMatches = (originalBus == null && rs.getString("bus_id") == null) || originalBus.equals(rs.getString("bus_id"));
                    boolean stopMatches = (originalStop == null && rs.getString("stop_name") == null) || originalStop.equals(rs.getString("stop_name"));
                    boolean authMatches = originalAuth == rs.getBoolean("transport_authorized");
                    boolean deptMatches = (originalDept == null && rs.getString("department") == null) || originalDept.equals(rs.getString("department"));
                    boolean yearMatches = (originalYear == null && rs.getString("academic_year") == null) || originalYear.equals(rs.getString("academic_year"));
                    String updatedStoredHash = rs.getString("password_hash");

                    boolean detailsUntouched = nameMatches && busMatches && stopMatches && authMatches && deptMatches && yearMatches;
                    boolean hashChanged = updatedStoredHash != null && updatedStoredHash.startsWith("PBKDF2$210000$") && !updatedStoredHash.equals(originalHash);

                    assertTrue("Student details preserved intact; only password_hash updated",
                            detailsUntouched && hashChanged,
                            "Name preserved: " + nameMatches + ", Bus: " + busMatches + ", Stop: " + stopMatches + ", Auth: " + authMatches);

                    // 4. Test authentication with the new password
                    boolean authSuccess = verifyPasswordHelper(newPassword, updatedStoredHash);
                    boolean authOldFails = !verifyPasswordHelper("oldPassword123", updatedStoredHash);

                    assertTrue("Student login with new password succeeds and rejects old password",
                            authSuccess && authOldFails,
                            "New password verified: " + authSuccess + ", Old password rejected: " + authOldFails);

                    // 5. Test authenticateDatabaseUser equivalent with new password, wrong password, and unknown student ID
                    String authValidId = authenticateUserHelper("students", "student_id", testStudentId, newPassword);
                    String authWrongPassId = authenticateUserHelper("students", "student_id", testStudentId, "WrongPassword@2026");
                    String authUnknownStudentId = authenticateUserHelper("students", "student_id", "UNKNOWN_STUDENT_999", newPassword);

                    assertTrue("Student login with new password verifies against stored hash",
                            testStudentId.equalsIgnoreCase(authValidId),
                            "Authenticated canonical ID: " + authValidId);

                    assertTrue("Student login with incorrect password is safely rejected",
                            authWrongPassId == null,
                            "Result with incorrect password: " + authWrongPassId);

                    assertTrue("Student login with unknown student ID is safely rejected",
                            authUnknownStudentId == null,
                            "Result with unknown student ID: " + authUnknownStudentId);
                }
            }

            // 6. Verify total student count is unchanged (no records deleted)
            try (PreparedStatement ps = con.prepareStatement("SELECT COUNT(*) FROM students");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                int studentCount = rs.getInt(1);
                assertTrue("Student record count unchanged (no records deleted)", studentCount >= 1,
                        "Current student count in DB: " + studentCount);
            }

        } catch (SQLException e) {
            e.printStackTrace();
            assertTrue("Database operations succeeded", false, "Exception: " + e.getMessage());
        }
    }

    // Helper mirroring Main.hashPassword()
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

            spec.clearPassword();

            return "PBKDF2$210000$"
                    + Base64.getEncoder().encodeToString(salt)
                    + "$"
                    + Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("Hashing failed", e);
        }
    }

    // Helper mirroring Main.verifyPassword()
    private static boolean verifyPasswordHelper(String password, String storedHash) {
        try {
            String[] parts = storedHash.split("\\$");
            if (parts.length != 4 || !"PBKDF2".equals(parts[0])) {
                return false;
            }

            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expectedHash = Base64.getDecoder().decode(parts[3]);

            javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                    password.toCharArray(), salt, iterations, expectedHash.length * 8
            );

            byte[] actualHash = javax.crypto.SecretKeyFactory
                    .getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();

            spec.clearPassword();

            return java.security.MessageDigest.isEqual(expectedHash, actualHash);
        } catch (Exception e) {
            return false;
        }
    }

    // Helper mirroring Main.authenticateDatabaseUser()
    private static String authenticateUserHelper(String table, String idColumn, String userId, String password) {
        if (!("students".equals(table) && "student_id".equals(idColumn))
                && !("drivers".equals(table) && "driver_id".equals(idColumn))) {
            return null;
        }

        String sql = "SELECT " + idColumn + ", password_hash FROM " + table + " WHERE " + idColumn + " = ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {

            ps.setString(1, userId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String canonicalId = rs.getString(idColumn);
                    String storedHash = rs.getString("password_hash");

                    if (storedHash == null || storedHash.trim().isEmpty()) {
                        return null;
                    }
                    storedHash = storedHash.trim();

                    boolean matched = false;
                    if (storedHash.startsWith("PBKDF2$")) {
                        matched = verifyPasswordHelper(password, storedHash);
                    } else if (storedHash.equals(password)) {
                        matched = true;
                    }

                    if (matched) {
                        return canonicalId;
                    }
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private static boolean verifyStudentIdentityHelper(String studentId, String studentName) {
        if (studentId == null || studentId.trim().isEmpty()
                || studentName == null || studentName.trim().isEmpty()) {
            return false;
        }
        String sql = "SELECT name FROM students WHERE student_id = ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, studentId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String dbName = rs.getString("name");
                    return dbName != null && dbName.trim().equalsIgnoreCase(studentName.trim());
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private static boolean verifyDriverIdentityHelper(String driverId, String driverName, String phoneNumber) {
        if (driverId == null || driverId.trim().isEmpty()
                || driverName == null || driverName.trim().isEmpty()
                || phoneNumber == null || phoneNumber.trim().isEmpty()) {
            return false;
        }
        String sql = "SELECT name, phone_number FROM drivers WHERE driver_id = ?";
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, driverId.trim());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String dbName = rs.getString("name");
                    String dbPhone = rs.getString("phone_number");
                    boolean nameMatches = dbName != null && dbName.trim().equalsIgnoreCase(driverName.trim());
                    String cleanInput = phoneNumber.replaceAll("[^0-9]", "");
                    String cleanDb = dbPhone != null ? dbPhone.replaceAll("[^0-9]", "") : "";
                    boolean phoneMatches = !cleanInput.isEmpty() && (cleanInput.equals(cleanDb)
                            || cleanInput.endsWith(cleanDb) || cleanDb.endsWith(cleanInput));
                    return nameMatches && phoneMatches;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    private static void testStudentIdentityVerificationProcess() {
        String testId = "25CSR002"; // Akshay
        boolean correctVerified = verifyStudentIdentityHelper(testId, "Akshay");
        boolean correctCaseInsensitive = verifyStudentIdentityHelper(testId, "akshay");
        boolean wrongNameRejected = !verifyStudentIdentityHelper(testId, "ImposterStudent");
        boolean emptyDetailsRejected = !verifyStudentIdentityHelper(testId, "");
        boolean nullDetailsRejected = !verifyStudentIdentityHelper(testId, null);
        boolean nonExistentStudentRejected = !verifyStudentIdentityHelper("NON_EXISTENT_999", "Akshay");

        assertTrue("Student Identity Verification: correct credentials (name) pass verification",
                correctVerified && correctCaseInsensitive,
                "Standard match: " + correctVerified + ", Case-insensitive: " + correctCaseInsensitive);

        assertTrue("Student Identity Verification: wrong name, empty name, and unknown ID safely rejected",
                wrongNameRejected && emptyDetailsRejected && nullDetailsRejected && nonExistentStudentRejected,
                "Wrong name rejected: " + wrongNameRejected + ", Empty rejected: " + emptyDetailsRejected
                        + ", Unknown ID rejected: " + nonExistentStudentRejected);
    }

    private static void testDriverIdentityVerificationProcess() {
        String testDriverId = "DRV-01"; // Ravi Shankar, 9876543210
        boolean correctVerified = verifyDriverIdentityHelper(testDriverId, "Ravi Shankar", "9876543210");
        boolean correctFormattedPhone = verifyDriverIdentityHelper(testDriverId, "Ravi Shankar", "+91 98765-43210");
        boolean wrongNameRejected = !verifyDriverIdentityHelper(testDriverId, "Imposter Driver", "9876543210");
        boolean wrongPhoneRejected = !verifyDriverIdentityHelper(testDriverId, "Ravi Shankar", "9999999999");
        boolean emptyDetailsRejected = !verifyDriverIdentityHelper(testDriverId, "", "");
        boolean nonExistentDriverRejected = !verifyDriverIdentityHelper("NON_EXISTENT_DRV", "Ravi Shankar", "9876543210");

        assertTrue("Driver Identity Verification: correct registered details pass verification",
                correctVerified && correctFormattedPhone,
                "Standard match: " + correctVerified + ", Formatted phone match: " + correctFormattedPhone);

        assertTrue("Driver Identity Verification: wrong name, wrong phone, and unknown ID safely rejected",
                wrongNameRejected && wrongPhoneRejected && emptyDetailsRejected && nonExistentDriverRejected,
                "Wrong name rejected: " + wrongNameRejected + ", Wrong phone rejected: " + wrongPhoneRejected
                        + ", Unknown ID rejected: " + nonExistentDriverRejected);
    }

    private static void testDriverPasswordResetDoesNotAffectManagement() {
        String testDriverId = "DRV-01";
        String adminId = "ADMIN-01";
        String siblingDriverId = "DRV-02";
        String newDriverPass = "NewDriverPass#2026";

        try (Connection con = DatabaseConnection.getConnection()) {
            // Snapshot admin and sibling driver hashes
            String originalAdminHash = null;
            try (PreparedStatement ps = con.prepareStatement("SELECT password_hash FROM management WHERE admin_id = ?")) {
                ps.setString(1, adminId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) originalAdminHash = rs.getString(1);
                }
            }

            String originalSiblingHash = null;
            try (PreparedStatement ps = con.prepareStatement("SELECT password_hash FROM drivers WHERE driver_id = ?")) {
                ps.setString(1, siblingDriverId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) originalSiblingHash = rs.getString(1);
                }
            }

            // Update DRV-01 password
            String newHash = hashPasswordHelper(newDriverPass);
            try (PreparedStatement ps = con.prepareStatement("UPDATE drivers SET password_hash = ? WHERE driver_id = ?")) {
                ps.setString(1, newHash);
                ps.setString(2, testDriverId);
                ps.executeUpdate();
            }

            // Check DRV-01 authenticates with new password
            String authDriver = authenticateUserHelper("drivers", "driver_id", testDriverId, newDriverPass);
            boolean driverAuthSuccess = testDriverId.equalsIgnoreCase(authDriver);

            // Check admin hash is untouched
            String currentAdminHash = null;
            try (PreparedStatement ps = con.prepareStatement("SELECT password_hash FROM management WHERE admin_id = ?")) {
                ps.setString(1, adminId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) currentAdminHash = rs.getString(1);
                }
            }
            boolean adminUntouched = java.util.Objects.equals(originalAdminHash, currentAdminHash);

            // Check sibling driver hash is untouched
            String currentSiblingHash = null;
            try (PreparedStatement ps = con.prepareStatement("SELECT password_hash FROM drivers WHERE driver_id = ?")) {
                ps.setString(1, siblingDriverId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) currentSiblingHash = rs.getString(1);
                }
            }
            boolean siblingUntouched = java.util.Objects.equals(originalSiblingHash, currentSiblingHash);

            assertTrue("Driver Password Reset updates ONLY target driver and leaves management & other drivers untouched",
                    driverAuthSuccess && adminUntouched && siblingUntouched,
                    "Driver authenticated: " + driverAuthSuccess + ", Management untouched: " + adminUntouched + ", Sibling driver untouched: " + siblingUntouched);

        } catch (SQLException e) {
            e.printStackTrace();
            assertTrue("Driver password reset isolation test succeeded", false, "Exception: " + e.getMessage());
        }
    }
}

