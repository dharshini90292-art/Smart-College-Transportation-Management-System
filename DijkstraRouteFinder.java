import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lib.DatabaseConnection;

/**
 * DijkstraRouteFinder
 *
 * Implements Dijkstra's Shortest Path Algorithm in pure Java over an actual weighted
 * road-network graph.
 *
 * Requirements enforced:
 * - Weighted graph representing actual road network (vertices = road junctions / stops / terminals,
 *   edges = verified road connections with actual road distances in meters).
 * - Adjacency list representation.
 * - PriorityQueue for selecting vertex with minimum tentative distance.
 * - Computes shortest connecting road paths between consecutive active pickup locations.
 * - Preserves fixed stop sequence (Starting Point -> Stop 1..10 -> College).
 * - Combines shortest road paths into one complete continuous route.
 * - Obtains and persists verified road-network data in MySQL (`road_network_edges`).
 * - Fails with explicit error if road data is unavailable (strictly no fake routes).
 */
public class DijkstraRouteFinder {

    public static class RoutingDataUnavailableException extends Exception {
        public RoutingDataUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * Weighted road edge representing an actual street connection.
     */
    public static class RoadEdge {
        public final String fromNode;
        public final String toNode;
        public final double distanceMeters;
        public final double durationSeconds;
        public final String geometryJson; // GeoJSON array of [lon, lat] coordinates

        public RoadEdge(String fromNode, String toNode, double distanceMeters, double durationSeconds, String geometryJson) {
            this.fromNode = fromNode;
            this.toNode = toNode;
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
            this.geometryJson = (geometryJson != null && !geometryJson.trim().isEmpty()) ? geometryJson : "[]";
        }
    }

    /**
     * Graph structure using an adjacency list.
     */
    public static class RoadGraph {
        private final Map<String, List<RoadEdge>> adjList = new HashMap<>();
        private final Map<String, double[]> nodeCoordinates = new LinkedHashMap<>();

        public void addNode(String nodeKey, double lat, double lon) {
            nodeCoordinates.put(nodeKey, new double[]{lat, lon});
            adjList.putIfAbsent(nodeKey, new ArrayList<>());
        }

        public void addEdge(RoadEdge edge) {
            adjList.putIfAbsent(edge.fromNode, new ArrayList<>());
            adjList.putIfAbsent(edge.toNode, new ArrayList<>());
            adjList.get(edge.fromNode).add(edge);
        }

        public List<RoadEdge> getEdges(String nodeKey) {
            return adjList.getOrDefault(nodeKey, Collections.emptyList());
        }

        public double[] getCoordinates(String nodeKey) {
            return nodeCoordinates.get(nodeKey);
        }

        public Set<String> getNodes() {
            return nodeCoordinates.keySet();
        }

        public int edgeCount() {
            int count = 0;
            for (List<RoadEdge> edges : adjList.values()) {
                count += edges.size();
            }
            return count;
        }
    }

    /**
     * Helper item for PriorityQueue in Dijkstra's algorithm.
     */
    private static class VertexDist {
        final String node;
        final double distance;

        VertexDist(String node, double distance) {
            this.node = node;
            this.distance = distance;
        }
    }

    /**
     * Shortest road path result between two consecutive required locations.
     */
    public static class DijkstraLegResult {
        public final String fromNode;
        public final String toNode;
        public final boolean reachable;
        public final double distanceMeters;
        public final double durationSeconds;
        public final List<String> nodePath;
        public final List<RoadEdge> edgePath;
        public final List<double[]> coordinatePoints; // [lat, lon]

        public DijkstraLegResult(String fromNode, String toNode, boolean reachable,
                                double distanceMeters, double durationSeconds,
                                List<String> nodePath, List<RoadEdge> edgePath,
                                List<double[]> coordinatePoints) {
            this.fromNode = fromNode;
            this.toNode = toNode;
            this.reachable = reachable;
            this.distanceMeters = distanceMeters;
            this.durationSeconds = durationSeconds;
            this.nodePath = nodePath != null ? nodePath : Collections.emptyList();
            this.edgePath = edgePath != null ? edgePath : Collections.emptyList();
            this.coordinatePoints = coordinatePoints != null ? coordinatePoints : Collections.emptyList();
        }
    }

    /**
     * Complete continuous bus route composed of stitched Dijkstra shortest legs.
     */
    public static class CompleteRouteResult {
        public final String busId;
        public final LocalDate date;
        public final boolean success;
        public final String message;
        public final double totalDistanceMeters;
        public final double totalDurationSeconds;
        public final List<String> activeStopKeys;
        public final List<String> skippedStopKeys;
        public final List<String> fullPathNodes;
        public final List<DijkstraLegResult> legs;
        public final List<double[]> fullPolylinePoints; // [lat, lon]
        public final String fullGeoJsonCoordinates;

        public CompleteRouteResult(String busId, LocalDate date, boolean success, String message,
                                   double totalDistanceMeters, double totalDurationSeconds,
                                   List<String> activeStopKeys, List<String> skippedStopKeys,
                                   List<String> fullPathNodes, List<DijkstraLegResult> legs,
                                   List<double[]> fullPolylinePoints, String fullGeoJsonCoordinates) {
            this.busId = busId;
            this.date = date;
            this.success = success;
            this.message = message;
            this.totalDistanceMeters = totalDistanceMeters;
            this.totalDurationSeconds = totalDurationSeconds;
            this.activeStopKeys = activeStopKeys;
            this.skippedStopKeys = skippedStopKeys;
            this.fullPathNodes = fullPathNodes;
            this.legs = legs;
            this.fullPolylinePoints = fullPolylinePoints;
            this.fullGeoJsonCoordinates = fullGeoJsonCoordinates;
        }
    }

    /**
     * Ensures the database table `road_network_edges` exists in transport_db.
     */
    public void ensureTable(Connection con) throws SQLException {
        try (Statement stmt = con.createStatement()) {
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS road_network_edges ("
                    + "edge_id INT AUTO_INCREMENT PRIMARY KEY, "
                    + "bus_id VARCHAR(30) NOT NULL, "
                    + "from_node_key VARCHAR(100) NOT NULL, "
                    + "to_node_key VARCHAR(100) NOT NULL, "
                    + "from_latitude DECIMAL(10,7) NOT NULL, "
                    + "from_longitude DECIMAL(10,7) NOT NULL, "
                    + "to_latitude DECIMAL(10,7) NOT NULL, "
                    + "to_longitude DECIMAL(10,7) NOT NULL, "
                    + "distance_meters DOUBLE NOT NULL, "
                    + "duration_seconds DOUBLE NOT NULL, "
                    + "geometry_geojson LONGTEXT NOT NULL, "
                    + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, "
                    + "UNIQUE KEY uk_edge (bus_id, from_node_key, to_node_key))");
        }
    }

    /**
     * Loads the road graph for a specific bus corridor from MySQL `road_network_edges`.
     * If edges are not yet populated, attempts to discover and populate them from verified OSRM driving data.
     */
    public RoadGraph loadOrBuildRoadGraph(Connection con, String busId,
                                          Map<String, double[]> nodeCoordinates,
                                          List<String[]> connectivityList)
            throws SQLException, RoutingDataUnavailableException {
        ensureTable(con);

        RoadGraph graph = new RoadGraph();
        for (Map.Entry<String, double[]> entry : nodeCoordinates.entrySet()) {
            graph.addNode(entry.getKey(), entry.getValue()[0], entry.getValue()[1]);
        }

        String selectSql = "SELECT from_node_key, to_node_key, distance_meters, duration_seconds, geometry_geojson "
                + "FROM road_network_edges WHERE bus_id = ?";
        try (PreparedStatement ps = con.prepareStatement(selectSql)) {
            ps.setString(1, busId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String from = rs.getString("from_node_key");
                    String to = rs.getString("to_node_key");
                    double dist = rs.getDouble("distance_meters");
                    double dur = rs.getDouble("duration_seconds");
                    String geo = rs.getString("geometry_geojson");
                    graph.addEdge(new RoadEdge(from, to, dist, dur, geo));
                }
            }
        }

        // Check if any expected connections are missing from the graph
        List<String[]> missing = new ArrayList<>();
        if (connectivityList != null) {
            for (String[] conn : connectivityList) {
                String u = conn[0];
                String v = conn[1];
                if (!hasEdge(graph, u, v)) missing.add(new String[]{u, v});
                if (!hasEdge(graph, v, u)) missing.add(new String[]{v, u});
            }
        }

        if (!missing.isEmpty()) {
            System.out.printf("[DijkstraRouteFinder] Fetching %d missing road edges from routing service for %s...%n",
                    missing.size(), busId);
            String insertSql = "INSERT INTO road_network_edges "
                    + "(bus_id, from_node_key, to_node_key, from_latitude, from_longitude, to_latitude, to_longitude, "
                    + "distance_meters, duration_seconds, geometry_geojson) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE distance_meters = VALUES(distance_meters), duration_seconds = VALUES(duration_seconds), "
                    + "geometry_geojson = VALUES(geometry_geojson)";

            try (PreparedStatement insPs = con.prepareStatement(insertSql)) {
                for (String[] pair : missing) {
                    String from = pair[0];
                    String to = pair[1];
                    double[] fromCoord = nodeCoordinates.get(from);
                    double[] toCoord = nodeCoordinates.get(to);
                    if (fromCoord == null || toCoord == null) continue;

                    String[] osrmData = fetchOsrmEdge(fromCoord[0], fromCoord[1], toCoord[0], toCoord[1]);
                    if (osrmData != null) {
                        double dist = Double.parseDouble(osrmData[0]);
                        double dur = Double.parseDouble(osrmData[1]);
                        String geo = osrmData[2];

                        insPs.setString(1, busId);
                        insPs.setString(2, from);
                        insPs.setString(3, to);
                        insPs.setDouble(4, fromCoord[0]);
                        insPs.setDouble(5, fromCoord[1]);
                        insPs.setDouble(6, toCoord[0]);
                        insPs.setDouble(7, toCoord[1]);
                        insPs.setDouble(8, dist);
                        insPs.setDouble(9, dur);
                        insPs.setString(10, geo);
                        insPs.executeUpdate();

                        graph.addEdge(new RoadEdge(from, to, dist, dur, geo));
                    } else {
                        System.err.printf("[WARN] Routing service unavailable for edge %s -> %s%n", from, to);
                    }
                }
            }
        }

        if (graph.edgeCount() == 0) {
            throw new RoutingDataUnavailableException("Road-network data is unavailable in the database or routing service. Fake routes are prohibited.");
        }

        return graph;
    }

    private boolean hasEdge(RoadGraph graph, String from, String to) {
        for (RoadEdge edge : graph.getEdges(from)) {
            if (edge.toNode.equals(to)) return true;
        }
        return false;
    }

    /**
     * Dijkstra's Shortest Path Algorithm in pure Java.
     * Computes the shortest road path from source to target over the weighted road graph.
     */
    public DijkstraLegResult findShortestPath(RoadGraph graph, String source, String target)
            throws RoutingDataUnavailableException {

        if (source == null || target == null) {
            throw new IllegalArgumentException("Source and target must not be null.");
        }

        if (source.equals(target)) {
            double[] coord = graph.getCoordinates(source);
            List<double[]> pts = new ArrayList<>();
            if (coord != null) pts.add(coord);
            return new DijkstraLegResult(source, target, true, 0.0, 0.0,
                    Collections.singletonList(source), Collections.emptyList(), pts);
        }

        Map<String, Double> dist = new HashMap<>();
        Map<String, RoadEdge> edgeTo = new HashMap<>();
        Set<String> visited = new HashSet<>();

        PriorityQueue<VertexDist> pq = new PriorityQueue<>(Comparator.comparingDouble(v -> v.distance));
        dist.put(source, 0.0);
        pq.add(new VertexDist(source, 0.0));

        while (!pq.isEmpty()) {
            VertexDist current = pq.poll();
            String u = current.node;

            if (visited.contains(u)) continue;
            visited.add(u);

            if (u.equals(target)) {
                break; // Target settled with minimum tentative distance
            }

            for (RoadEdge edge : graph.getEdges(u)) {
                String v = edge.toNode;
                if (visited.contains(v)) continue;

                double alt = dist.get(u) + edge.distanceMeters;
                if (alt < dist.getOrDefault(v, Double.POSITIVE_INFINITY)) {
                    dist.put(v, alt);
                    edgeTo.put(v, edge);
                    pq.add(new VertexDist(v, alt));
                }
            }
        }

        if (!dist.containsKey(target)) {
            throw new RoutingDataUnavailableException(
                    "No road path found between " + source + " and " + target + " in the road graph.");
        }

        // Reconstruct the shortest path from target back to source
        LinkedList<String> nodePath = new LinkedList<>();
        LinkedList<RoadEdge> edgePath = new LinkedList<>();
        String curr = target;
        nodePath.addFirst(curr);
        while (!curr.equals(source)) {
            RoadEdge edge = edgeTo.get(curr);
            if (edge == null) {
                throw new RoutingDataUnavailableException("Broken edge path while reconstructing shortest path from "
                        + source + " to " + target);
            }
            edgePath.addFirst(edge);
            curr = edge.fromNode;
            nodePath.addFirst(curr);
        }

        double totalDist = dist.get(target);
        double totalDur = 0.0;
        List<double[]> legCoords = new ArrayList<>();
        for (RoadEdge edge : edgePath) {
            totalDur += edge.durationSeconds;
            List<double[]> eCoords = parseCoordinates(edge.geometryJson);
            if (!legCoords.isEmpty() && !eCoords.isEmpty()) {
                // Remove duplicate vertex junction coordinate
                eCoords.remove(0);
            }
            legCoords.addAll(eCoords);
        }

        return new DijkstraLegResult(source, target, true, totalDist, totalDur, nodePath, edgePath, legCoords);
    }

    /**
     * Calculates the complete continuous route for an ordered sequence of active stops:
     * Starting Point -> Active Stop 1 -> Active Stop 2 -> ... -> College
     */
    public CompleteRouteResult calculateContinuousRoute(RoadGraph graph, String busId, LocalDate date,
                                                         List<String> activeLocationsInOrder,
                                                         List<String> skippedStopKeys)
            throws RoutingDataUnavailableException {

        if (activeLocationsInOrder == null || activeLocationsInOrder.size() < 2) {
            throw new IllegalArgumentException("At least two locations (e.g. Depot and College) are required.");
        }

        double totalDistance = 0.0;
        double totalDuration = 0.0;
        List<DijkstraLegResult> legs = new ArrayList<>();
        List<String> fullPathNodes = new ArrayList<>();
        List<double[]> fullPolylinePoints = new ArrayList<>();

        for (int i = 0; i < activeLocationsInOrder.size() - 1; i++) {
            String from = activeLocationsInOrder.get(i);
            String to = activeLocationsInOrder.get(i + 1);

            DijkstraLegResult leg = findShortestPath(graph, from, to);
            legs.add(leg);
            totalDistance += leg.distanceMeters;
            totalDuration += leg.durationSeconds;

            // Stitch node path
            if (fullPathNodes.isEmpty()) {
                fullPathNodes.addAll(leg.nodePath);
            } else {
                for (int ni = 1; ni < leg.nodePath.size(); ni++) {
                    fullPathNodes.add(leg.nodePath.get(ni));
                }
            }

            // Stitch coordinates
            if (fullPolylinePoints.isEmpty()) {
                fullPolylinePoints.addAll(leg.coordinatePoints);
            } else {
                for (int ci = 1; ci < leg.coordinatePoints.size(); ci++) {
                    fullPolylinePoints.add(leg.coordinatePoints.get(ci));
                }
            }
        }

        // Build GeoJSON array string: "[[lon,lat],[lon,lat],...]"
        StringBuilder geoJson = new StringBuilder("[");
        for (int pi = 0; pi < fullPolylinePoints.size(); pi++) {
            double[] pt = fullPolylinePoints.get(pi);
            if (pi > 0) geoJson.append(",");
            geoJson.append(String.format(Locale.ROOT, "[%.6f,%.6f]", pt[1], pt[0]));
        }
        geoJson.append("]");

        String message = String.format(Locale.ROOT,
                "Route calculated via Java Dijkstra algorithm: %d active locations, %.2f km road distance.",
                activeLocationsInOrder.size(), totalDistance / 1000.0);

        return new CompleteRouteResult(busId, date, true, message, totalDistance, totalDuration,
                activeLocationsInOrder, skippedStopKeys, fullPathNodes, legs,
                fullPolylinePoints, geoJson.toString());
    }

    /**
     * Parses GeoJSON coordinates string into a list of [lat, lon] double arrays.
     */
    public static List<double[]> parseCoordinates(String geometryJson) {
        List<double[]> points = new ArrayList<>();
        if (geometryJson == null || geometryJson.trim().isEmpty()) return points;

        Pattern coordPattern = Pattern.compile("\\[\\s*(-?\\d+\\.\\d+)\\s*,\\s*(-?\\d+\\.\\d+)\\s*\\]");
        Matcher matcher = coordPattern.matcher(geometryJson);
        while (matcher.find()) {
            double lon = Double.parseDouble(matcher.group(1));
            double lat = Double.parseDouble(matcher.group(2));
            points.add(new double[]{lat, lon});
        }
        return points;
    }

    /**
     * Queries OSRM driving service for a single road edge between two coordinates.
     */
    private static String[] fetchOsrmEdge(double fromLat, double fromLon, double toLat, double toLon) {
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
            String url = String.format(Locale.ROOT,
                    "https://router.project-osrm.org/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=full&geometries=geojson",
                    fromLon, fromLat, toLon, toLat);
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(6)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body().contains("\"code\":\"Ok\"")) {
                String body = response.body();
                double dist = extractJsonDouble(body, "distance");
                double dur = extractJsonDouble(body, "duration");
                String geo = extractGeojsonCoords(body);
                return new String[]{String.valueOf(dist), String.valueOf(dur), geo};
            }
        } catch (Exception ex) {
            System.err.println("[DijkstraRouteFinder] OSRM fetch error: " + ex.getMessage());
        }
        return null;
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
        return (end != -1) ? json.substring(start + 14, end) : null;
    }
}
