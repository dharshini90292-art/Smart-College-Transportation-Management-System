-- =============================================================================
-- SMART TRANSPORT MANAGEMENT SYSTEM - DEMO DATA BACKUP
-- Preserved before removing unwanted demo content per user requirements
-- Timestamp: 2026-10-29
-- =============================================================================

-- Backup Table 1: complaints_backup_demo
CREATE TABLE IF NOT EXISTS complaints_backup_demo (
    complaint_id INT PRIMARY KEY,
    submitted_by VARCHAR(50) NOT NULL,
    role VARCHAR(20) NOT NULL,
    subject VARCHAR(150) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(30) DEFAULT 'Pending',
    management_response TEXT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO complaints_backup_demo (complaint_id, submitted_by, role, subject, description, status, management_response, created_at) VALUES
(1, '25CSR003', 'student', 'AC Issue Bus 1', 'Air conditioning temperature is too warm in afternoons.', 'Pending', NULL, '2026-10-22 08:07:42'),
(2, 'DRV-01', 'driver', 'Wiper Replacement', 'Left windshield wiper blade is worn out.', 'Pending', NULL, '2026-10-22 08:07:42'),
(3, 'DRV-01', 'driver', 'Rear indicator bulb', 'Right rear turn indicator bulb is flickering', 'Pending', NULL, '2026-10-25 10:52:17'),
(4, 'DRV-01', 'driver', 'Rear indicator bulb', 'Right rear turn indicator bulb is flickering', 'Pending', NULL, '2026-10-25 10:55:44'),
(5, 'DRV-01', 'driver', 'Rear indicator bulb', 'Right rear turn indicator bulb is flickering', 'Pending', NULL, '2026-10-25 10:56:52'),
(6, '25CSR002', 'student', 'Bus Timing Query', 'Requesting evening bus departure time schedule', 'Pending', NULL, '2026-10-25 11:52:38')
ON DUPLICATE KEY UPDATE subject = VALUES(subject);

-- Backup Table 2: breakdown_reports_backup_demo
CREATE TABLE IF NOT EXISTS breakdown_reports_backup_demo (
    report_id INT PRIMARY KEY,
    driver_id VARCHAR(30) NOT NULL,
    bus_id VARCHAR(30) NOT NULL,
    breakdown_date DATETIME NOT NULL,
    issue_description TEXT NOT NULL,
    severity VARCHAR(20) NOT NULL DEFAULT 'Medium',
    status VARCHAR(30) NOT NULL DEFAULT 'Reported',
    action_taken TEXT NULL,
    remarks TEXT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

INSERT INTO breakdown_reports_backup_demo (report_id, driver_id, bus_id, breakdown_date, issue_description, severity, status, action_taken, remarks, created_at, updated_at) VALUES
(1, 'DRV-01', 'BUS-01', '2026-10-25 09:00:00', 'Coolant leak and engine temp spiking', 'Medium', 'Resolved', 'Replaced radiator hose and tested coolant', 'Bus operational', '2026-10-25 10:52:17', '2026-10-25 10:52:17'),
(2, 'DRV-01', 'BUS-01', '2026-10-25 09:00:00', 'Coolant leak and engine temp spiking', 'Medium', 'Resolved', 'Replaced radiator hose and tested coolant', 'Bus operational', '2026-10-25 10:55:44', '2026-10-25 10:55:44'),
(3, 'DRV-01', 'BUS-01', '2026-10-25 09:00:00', 'Coolant leak and engine temp spiking', 'Medium', 'Resolved', 'Replaced radiator hose and tested coolant', 'Bus operational', '2026-10-25 10:56:52', '2026-10-25 10:56:52')
ON DUPLICATE KEY UPDATE issue_description = VALUES(issue_description);
