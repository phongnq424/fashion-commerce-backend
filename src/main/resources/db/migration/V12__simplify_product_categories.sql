-- Keep only the three catalog groups currently supported by the marketplace.
-- Deprecated seeded categories are disabled instead of deleted so historical products remain valid.

UPDATE categories
SET name = 'Áo',
    slug = 'ao',
    sort_order = 10,
    is_active = true
WHERE id = '10000000-0000-0000-0000-000000000001';

UPDATE categories
SET name = 'Quần',
    slug = 'quan',
    sort_order = 20,
    is_active = true
WHERE id = '10000000-0000-0000-0000-000000000002';

UPDATE categories
SET name = 'Đầm & Váy',
    slug = 'vay-dam',
    sort_order = 30,
    is_active = true
WHERE id = '10000000-0000-0000-0000-000000000003';

UPDATE categories
SET is_active = false
WHERE id IN (
    '10000000-0000-0000-0000-000000000004',
    '10000000-0000-0000-0000-000000000005'
);
