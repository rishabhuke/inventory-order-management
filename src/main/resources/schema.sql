-- =====================================================================
-- Inventory & Order Management System - schema + sample data (SQLite)
--
-- Relationships:
--   users 1 --- N orders 1 --- N order_items N --- 1 products
--
-- Note: this file is split on semicolons by DBConnection, so do not put
-- a semicolon inside any comment or string literal.
-- =====================================================================

CREATE TABLE IF NOT EXISTS users (
    user_id   INTEGER PRIMARY KEY AUTOINCREMENT,
    username  TEXT    NOT NULL UNIQUE,
    password  TEXT    NOT NULL,   -- plain text on purpose, see README (future improvement)
    role      TEXT    NOT NULL CHECK (role IN ('ADMIN', 'CUSTOMER'))
);

CREATE TABLE IF NOT EXISTS products (
    product_id      INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT    NOT NULL,
    category        TEXT    NOT NULL,
    price           NUMERIC NOT NULL CHECK (price >= 0),
    stock_quantity  INTEGER NOT NULL CHECK (stock_quantity >= 0),
    -- Soft delete: a product that appears in old orders can never be
    -- physically removed without breaking order history.
    is_active       INTEGER NOT NULL DEFAULT 1 CHECK (is_active IN (0, 1))
);

CREATE TABLE IF NOT EXISTS orders (
    order_id    INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id     INTEGER NOT NULL,
    order_date  TEXT    NOT NULL,   -- ISO-8601, e.g. 2026-09-22T14:30:00 (sorts correctly as text)
    status      TEXT    NOT NULL DEFAULT 'PLACED' CHECK (status IN ('PLACED', 'PROCESSED')),
    FOREIGN KEY (user_id) REFERENCES users (user_id)
);

CREATE TABLE IF NOT EXISTS order_items (
    order_item_id  INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id       INTEGER NOT NULL,
    product_id     INTEGER NOT NULL,
    quantity       INTEGER NOT NULL CHECK (quantity > 0),
    unit_price     NUMERIC NOT NULL CHECK (unit_price >= 0),  -- price at time of purchase
    FOREIGN KEY (order_id)   REFERENCES orders (order_id) ON DELETE CASCADE,
    FOREIGN KEY (product_id) REFERENCES products (product_id)
);

CREATE INDEX IF NOT EXISTS idx_orders_user     ON orders (user_id);
CREATE INDEX IF NOT EXISTS idx_items_order     ON order_items (order_id);
CREATE INDEX IF NOT EXISTS idx_products_active ON products (is_active);

-- ---------------------------- sample data ----------------------------
INSERT OR IGNORE INTO users (username, password, role) VALUES
    ('admin', 'admin123', 'ADMIN'),
    ('alice', 'alice123', 'CUSTOMER'),
    ('bob',   'bob123',   'CUSTOMER');

INSERT INTO products (name, category, price, stock_quantity) VALUES
    ('Wireless Mouse',      'Electronics', 799.00,  50),
    ('Mechanical Keyboard', 'Electronics', 3499.00, 25),
    ('USB-C Hub',           'Electronics', 1299.00, 8),
    ('HD Webcam',           'Electronics', 2199.00, 2),
    ('Laptop Stand',        'Accessories', 1499.00, 15),
    ('Phone Stand',         'Accessories', 299.00,  40),
    ('Backpack',            'Accessories', 1899.00, 12),
    ('Notebook A5',         'Stationery',  89.00,   200),
    ('Gel Pen Pack',        'Stationery',  149.00,  4),
    ('Sticky Notes',        'Stationery',  59.00,   6),
    ('Desk Lamp',           'Home',        999.00,  30),
    ('Water Bottle',        'Home',        349.00,  3);
