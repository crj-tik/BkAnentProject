-- Buyer consultation × comparison report association (add-consultation-comparison-cards task 3.1).
-- One-way reference: the customer domain stores report ids only, never report content (design decision 2).
USE bk_customer;

CREATE TABLE IF NOT EXISTS customer_inquiry_comparison (
    id BIGINT NOT NULL AUTO_INCREMENT,
    inquiry_id BIGINT NOT NULL,
    report_id VARCHAR(64) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_inquiry_comparison (inquiry_id, report_id),
    KEY idx_customer_inquiry_comparison_inquiry (inquiry_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
