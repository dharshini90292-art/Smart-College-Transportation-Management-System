import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class VerifyRouteManagementUI {
    public static void main(String[] args) {
        try {
            System.out.println("======================================================================");
            System.out.println("   VERIFY ROUTE MANAGEMENT CLEANUP & MAP FUNCTIONALITY               ");
            System.out.println("======================================================================");

            // 1. Authenticate as Management (ADMIN-01 / admin123)
            HttpURLConnection loginConn = (HttpURLConnection) new URL("http://localhost:8080/management-login.html").openConnection();
            loginConn.setRequestMethod("POST");
            loginConn.setInstanceFollowRedirects(false);
            loginConn.setDoOutput(true);
            loginConn.getOutputStream().write("managementId=ADMIN-01&password=admin123".getBytes(StandardCharsets.UTF_8));
            String cookie = loginConn.getHeaderField("Set-Cookie");
            if (cookie != null && cookie.contains(";")) {
                cookie = cookie.split(";")[0];
            }
            System.out.println("[INFO] Login HTTP Code: " + loginConn.getResponseCode() + ", Cookie: " + cookie);

            // 2. Fetch /management-routes.html
            HttpURLConnection req = (HttpURLConnection) new URL("http://localhost:8080/management-routes.html?busId=BUS-01&date=2026-11-01").openConnection();
            if (cookie != null) req.setRequestProperty("Cookie", cookie);
            req.setInstanceFollowRedirects(false);
            int code = req.getResponseCode();
            String html = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            System.out.println("[INFO] Route Management HTTP Code: " + code + ", HTML length: " + html.length());

            // 3. Verify removals
            String[] forbiddenStrings = {
                // Item 1
                "The road polyline below is computed using real road geometry",
                // Item 2
                "Dijkstra Road Route Optimization Active",
                "Continuous route calculated via pure Java Dijkstra shortest-path algorithm",
                // Item 3
                "MAP LEGEND",
                "Bus Depot (Start)",
                "Active Pickup Stop (Stop 1 to 10)",
                "Skipped Stop (100% Absent)",
                "Excluded (0 Students)",
                "CIET Campus (Destination)",
                "Dijkstra Shortest Road Route",
                // Item 4
                "Corridor Fleet",
                "Road Route Distance",
                "Estimated Transit Time",
                "Last Database Update",
                // Section B and Terminals section removals
                "Management can view and update the precise real-world geographic coordinates",
                "Route Starting Point (Depot)",
                "Gandhipuram Central Terminal (Depot)",
                "Final Route Destination (College)",
                "Mandatory Route Termination Point",
                "Edit Starting Point & College Coordinates",
                "Depot Latitude (-90 to 90)",
                "Depot Longitude (-180 to 180)",
                "College Latitude (-90 to 90)",
                "College Longitude (-180 to 180)",
                "Update Terminals & Recalculate",
                "action=\"/update-terminals\""
            };

            int removedPass = 0;
            for (String s : forbiddenStrings) {
                boolean contains = html.contains(s);
                if (contains) {
                    System.err.println("  [FAIL] Page still contains forbidden text: \"" + s + "\"");
                } else {
                    System.out.println("  [PASS] Successfully removed: \"" + s + "\"");
                    removedPass++;
                }
            }

            // 4. Verify preserved working features
            boolean hasMap = html.contains("id=\"map\"");
            boolean hasLeafletJs = html.contains("leaflet.js");
            boolean hasLeafletCss = html.contains("leaflet.css");
            boolean hasPolyline = html.contains("L.polyline") || html.contains("polyline");
            boolean hasStopMarkers = html.contains("marker_1") && html.contains("marker_10");
            boolean hasStopTable = html.contains("Section C &mdash; Pickup Stop Management");
            boolean hasReassignTable = html.contains("Section D &mdash; Student Pickup Stop Assignments");
            boolean hasBreakdownTable = html.contains("Section E &mdash; Attendance-Based Route Optimization Breakdown");

            System.out.println("\n--- Preserved Features Check ---");
            System.out.println("  Interactive Leaflet Map: " + (hasMap ? "[PASS]" : "[FAIL]"));
            System.out.println("  Leaflet JS & CSS: " + (hasLeafletJs && hasLeafletCss ? "[PASS]" : "[FAIL]"));
            System.out.println("  Road Route Polyline: " + (hasPolyline ? "[PASS]" : "[FAIL]"));
            System.out.println("  Stop Markers (Stop 1 to 10): " + (hasStopMarkers ? "[PASS]" : "[FAIL]"));
            System.out.println("  Pickup Stop Management Table: " + (hasStopTable ? "[PASS]" : "[FAIL]"));
            System.out.println("  Student Reassignment Table: " + (hasReassignTable ? "[PASS]" : "[FAIL]"));
            System.out.println("  Attendance Breakdown Table: " + (hasBreakdownTable ? "[PASS]" : "[FAIL]"));

            boolean allRemoved = (removedPass == forbiddenStrings.length);
            boolean allPreserved = hasMap && hasLeafletJs && hasLeafletCss && hasPolyline && hasStopMarkers
                    && hasStopTable && hasReassignTable && hasBreakdownTable;

            if (allRemoved && allPreserved) {
                System.out.println("\n>>> ALL CHECKS PASSED PERFECTLY! <<<");
                System.exit(0);
            } else {
                System.err.println("\n>>> SOME CHECKS FAILED! <<<");
                System.exit(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
