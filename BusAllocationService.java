import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lib.DatabaseConnection;

/**
 * BusAllocationService
 *
 * Implements automatic bus capacity checks and intelligent bus reallocation
 * when a bus reaches configured capacity.
 *
 * Requirements enforced:
 * 1. Checks actual configured capacity in MySQL `buses` table.
 * 2. Counts students currently assigned to that bus.
 * 3. If available seats exist, assigns to requested bus.
 * 4. If full, automatically finds another available bus serving the required route/corridor.
 * 5. Assigns to next suitable bus with available capacity.
 * 6. Checks next suitable bus if that bus is also full.
 * 7. Never exceeds the configured capacity of any bus.
 * 8. Preserves the student's original pickup stop when reallocating.
 * 9. Updates the student's bus assignment in MySQL immediately.
 * 10. Ensures the new assignment appears consistently across Student, Driver, and Management dashboards.
 * 11. Student appears in pickup list of newly assigned bus, not previous bus.
 * 12. If no suitable bus has seats: "All buses on this route are full. Please contact Management."
 * 13. Does not silently assign a student to an unrelated route.
 * 14. Prevents duplicate assignments and handles simultaneous student allocations safely
 *     using database transactions and row-level locking (SELECT ... FOR UPDATE).
 */
public class BusAllocationService {

    public static final String ALL_BUSES_FULL_MESSAGE =
            "All buses on this route are full. Please contact Management.";

    public static class BusAllocationResult {
        public final boolean success;
        public final String assignedBusId;
        public final String requestedBusId;
        public final boolean wasReallocated;
        public final String pickupStop;
        public final String message;
        public final int assignedCount;
        public final int capacity;

        public BusAllocationResult(
                boolean success,
                String assignedBusId,
                String requestedBusId,
                boolean wasReallocated,
                String pickupStop,
                String message,
                int assignedCount,
                int capacity) {
            this.success = success;
            this.assignedBusId = assignedBusId;
            this.requestedBusId = requestedBusId;
            this.wasReallocated = wasReallocated;
            this.pickupStop = pickupStop;
            this.message = message;
            this.assignedCount = assignedCount;
            this.capacity = capacity;
        }

        @Override
        public String toString() {
            return "BusAllocationResult{" +
                    "success=" + success +
                    ", assignedBusId='" + assignedBusId + '\'' +
                    ", requestedBusId='" + requestedBusId + '\'' +
                    ", wasReallocated=" + wasReallocated +
                    ", pickupStop='" + pickupStop + '\'' +
                    ", message='" + message + '\'' +
                    ", assignedCount=" + assignedCount +
                    ", capacity=" + capacity +
                    '}';
        }
    }

    /**
     * Determines the optimal bus with available capacity for a student on their route/corridor.
     * Uses row locking (FOR UPDATE) within a transaction to safely handle concurrent allocations.
     *
     * @param con             Active MySQL connection (transaction managed)
     * @param requestedBusId  Bus requested for the student (e.g. "BUS-01")
     * @param pickupStop      Student's designated pickup stop (preserved unchanged)
     * @param studentId       Student ID being assigned or registered
     * @param isNewStudent    True if inserting a brand-new student, false if reallocating existing
     * @return BusAllocationResult with details of allocation or failure message
     * @throws SQLException On database errors
     */
    public static BusAllocationResult allocateBusForStudent(
            Connection con,
            String requestedBusId,
            String pickupStop,
            String studentId,
            boolean isNewStudent) throws SQLException {

        if (requestedBusId == null || requestedBusId.trim().isEmpty()) {
            requestedBusId = "BUS-01";
        }
        String normRequested = RoutePlanner.normalizeBusId(requestedBusId.trim());
        String normStop = pickupStop != null ? pickupStop.trim() : "";
        String sId = studentId != null ? studentId.trim() : "";

        // 1. Lock and inspect requested bus capacity and route
        String busSql = "SELECT bus_id, bus_number, capacity, route_name FROM buses WHERE bus_id = ? FOR UPDATE";
        int reqCapacity = 40;
        String reqRouteName = null;
        boolean busFound = false;

        try (PreparedStatement ps = con.prepareStatement(busSql)) {
            ps.setString(1, normRequested);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    busFound = true;
                    reqCapacity = rs.getInt("capacity");
                    reqRouteName = rs.getString("route_name");
                }
            }
        }

        if (!busFound) {
            return new BusAllocationResult(
                    false, null, normRequested, false, normStop,
                    "Requested bus " + normRequested + " not found in fleet records.",
                    0, 0
            );
        }

        // 2. Count current students assigned to requested bus (excluding current student)
        int currentCount = countAssignedStudents(con, normRequested, sId);

        // 3. If requested bus has available seats, assign student to it
        if (currentCount < reqCapacity) {
            if (!isNewStudent && !sId.isEmpty()) {
                updateStudentBus(con, sId, normRequested);
            }
            return new BusAllocationResult(
                    true, normRequested, normRequested, false, normStop,
                    "Assigned to requested bus " + normRequested + " (" + (currentCount + 1) + "/" + reqCapacity + " seats).",
                    currentCount + 1, reqCapacity
            );
        }

        // 4. Requested bus is FULL (currentCount >= reqCapacity)
        // Automatically find another available bus serving the required route/corridor.
        List<String> suitableBuses = findSuitableBusesForRoute(con, normRequested, reqRouteName, normStop);

        for (String candidateBusId : suitableBuses) {
            // Lock candidate bus
            int candCapacity = 40;
            try (PreparedStatement ps = con.prepareStatement(busSql)) {
                ps.setString(1, candidateBusId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        candCapacity = rs.getInt("capacity");
                    }
                }
            }

            // Count students assigned to candidate bus
            int candCount = countAssignedStudents(con, candidateBusId, sId);

            // If candidate has available capacity, assign to it!
            if (candCount < candCapacity) {
                if (!isNewStudent && !sId.isEmpty()) {
                    updateStudentBus(con, sId, candidateBusId);
                }
                return new BusAllocationResult(
                        true, candidateBusId, normRequested, true, normStop,
                        "Requested bus " + normRequested + " is full (" + currentCount + "/" + reqCapacity +
                                "). Student automatically reallocated to available " + candidateBusId +
                                " on this route corridor (" + (candCount + 1) + "/" + candCapacity + " seats).",
                        candCount + 1, candCapacity
                );
            }
        }

        // 5. If all suitable buses on this route are full:
        // Strictly do NOT assign to an unrelated route!
        return new BusAllocationResult(
                false, null, normRequested, false, normStop,
                ALL_BUSES_FULL_MESSAGE,
                currentCount, reqCapacity
        );
    }

    /**
     * Finds other buses in MySQL that serve the required route or corridor or stop.
     * Excludes buses that serve completely unrelated routes.
     */
    private static List<String> findSuitableBusesForRoute(
            Connection con,
            String originalBusId,
            String originalRouteName,
            String pickupStop) throws SQLException {

        List<String> candidates = new ArrayList<>();

        // Candidate query: all other buses
        String sql = "SELECT bus_id, bus_number, capacity, route_name FROM buses WHERE bus_id != ? ORDER BY bus_id";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, originalBusId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String candBusId = rs.getString("bus_id");
                    String candRoute = rs.getString("route_name");

                    boolean isSuitable = false;

                    // Condition A: Same route_name in buses table
                    if (originalRouteName != null && candRoute != null &&
                            originalRouteName.trim().equalsIgnoreCase(candRoute.trim())) {
                        isSuitable = true;
                    }

                    // Condition B: Candidate bus serves the student's pickup stop in stops table
                    if (!isSuitable && pickupStop != null && !pickupStop.trim().isEmpty()) {
                        if (busServesStop(con, candBusId, pickupStop)) {
                            isSuitable = true;
                        }
                    }

                    // Condition C: Corridor relationship / Route corridor prefix
                    // e.g., BUS-01 and BUS-01B or both in North Sector Corridor
                    if (!isSuitable) {
                        String origCorridor = getCorridorLabel(originalBusId, originalRouteName);
                        String candCorridor = getCorridorLabel(candBusId, candRoute);
                        if (origCorridor != null && candCorridor != null &&
                                origCorridor.equalsIgnoreCase(candCorridor)) {
                            isSuitable = true;
                        }
                    }

                    // Condition D: Same corridor stops overlap in stops table
                    if (!isSuitable && hasSharedStops(con, originalBusId, candBusId)) {
                        isSuitable = true;
                    }

                    if (isSuitable && !candidates.contains(candBusId)) {
                        candidates.add(candBusId);
                    }
                }
            }
        }

        return candidates;
    }

    /**
     * Checks if a bus serves a given pickup stop code or landmark name in MySQL.
     */
    private static boolean busServesStop(Connection con, String busId, String pickupStop) throws SQLException {
        String stopSql = "SELECT 1 FROM stops WHERE bus_id = ? AND " +
                "(LOWER(TRIM(stop_name)) = LOWER(TRIM(?)) OR LOWER(TRIM(stop_code)) = LOWER(TRIM(?)) " +
                "OR LOWER(TRIM(stop_name)) = LOWER(TRIM(?)) OR LOWER(TRIM(stop_code)) = LOWER(TRIM(?))) LIMIT 1";
        String normStop = RoutePlanner.normalizeStopCode(pickupStop);
        try (PreparedStatement ps = con.prepareStatement(stopSql)) {
            ps.setString(1, busId);
            ps.setString(2, pickupStop);
            ps.setString(3, pickupStop);
            ps.setString(4, normStop);
            ps.setString(5, normStop);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Checks if two buses share pickup stops along their corridor.
     */
    private static boolean hasSharedStops(Connection con, String busId1, String busId2) throws SQLException {
        String sql = "SELECT 1 FROM stops s1 JOIN stops s2 ON " +
                "(LOWER(TRIM(s1.stop_name)) = LOWER(TRIM(s2.stop_name)) OR LOWER(TRIM(s1.stop_code)) = LOWER(TRIM(s2.stop_code))) " +
                "WHERE s1.bus_id = ? AND s2.bus_id = ? LIMIT 1";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, busId1);
            ps.setString(2, busId2);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Normalizes corridor label for bus and route matching.
     */
    private static String getCorridorLabel(String busId, String routeName) {
        String combined = (busId + " " + (routeName != null ? routeName : "")).toLowerCase(Locale.ROOT);
        if (combined.contains("north") || combined.contains("route 01") || combined.contains("route 1") || combined.contains("bus-01")) {
            return "NORTH_CORRIDOR";
        }
        if (combined.contains("south") || combined.contains("route 02") || combined.contains("route 2") || combined.contains("bus-02")) {
            return "SOUTH_CORRIDOR";
        }
        if (combined.contains("east") || combined.contains("central") || combined.contains("route 03") || combined.contains("route 3") || combined.contains("bus-03")) {
            return "EAST_CORRIDOR";
        }
        return routeName != null ? routeName.trim().toUpperCase(Locale.ROOT) : null;
    }

    /**
     * Counts students currently assigned to a bus in MySQL.
     * Excludes studentId to prevent counting an existing student against capacity during reallocation.
     */
    public static int countAssignedStudents(Connection con, String busId, String studentId) throws SQLException {
        String countSql = "SELECT COUNT(*) FROM students WHERE bus_id = ? AND student_id != ?";
        try (PreparedStatement ps = con.prepareStatement(countSql)) {
            ps.setString(1, busId);
            ps.setString(2, studentId != null ? studentId : "");
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Updates student's bus_id in MySQL immediately, preserving pickup stop.
     */
    public static void updateStudentBus(Connection con, String studentId, String newBusId) throws SQLException {
        String sql = "UPDATE students SET bus_id = ? WHERE student_id = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, newBusId);
            ps.setString(2, studentId);
            ps.executeUpdate();
        }
    }

    /**
     * Reallocates an existing student to a target bus with safe database transaction and locking.
     * Preserves original pickup stop.
     */
    public static BusAllocationResult reallocateStudent(String studentId, String targetBusId) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection()) {
            con.setAutoCommit(false);
            try {
                // Fetch student's current pickup stop
                String currentStop = "";
                String fetchSql = "SELECT stop_name FROM students WHERE student_id = ? FOR UPDATE";
                try (PreparedStatement ps = con.prepareStatement(fetchSql)) {
                    ps.setString(1, studentId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            currentStop = rs.getString("stop_name");
                        } else {
                            con.rollback();
                            return new BusAllocationResult(
                                    false, null, targetBusId, false, "",
                                    "Student " + studentId + " not found in database.",
                                    0, 0
                            );
                        }
                    }
                }

                BusAllocationResult result = allocateBusForStudent(
                        con, targetBusId, currentStop, studentId, false
                );

                if (result.success) {
                    con.commit();
                } else {
                    con.rollback();
                }

                return result;
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(true);
            }
        }
    }

    /**
     * Gets configured capacity for a bus directly from MySQL `buses` table.
     */
    public static int getConfiguredCapacity(Connection con, String busId) throws SQLException {
        String sql = "SELECT capacity FROM buses WHERE bus_id = ?";
        try (PreparedStatement ps = con.prepareStatement(sql)) {
            ps.setString(1, busId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("capacity") : 0;
            }
        }
    }
}
