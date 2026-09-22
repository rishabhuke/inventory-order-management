# Inventory & Order Management System

A console-based inventory and order management system written in **Core Java 17** —
no Spring, no ORM, no frameworks. Built as a portfolio project for fresher
software-engineering roles, to demonstrate OOP design, hand-written DSA
(merge sort, binary search, a `HashMap` index, a `PriorityQueue`, and a
`Stack`), JDBC/DBMS work with real transactions, and layered exception
handling.

Two roles: an **admin** manages the product catalogue and fulfils orders;
a **customer** browses, searches, and places orders.

## Tech stack

| Layer       | Choice                                            |
|-------------|----------------------------------------------------|
| Language    | Java 17 (compiled and tested with JDK 17–27)        |
| Build       | Maven                                              |
| Database    | SQLite, via the `sqlite-jdbc` driver (see below)    |
| Persistence | Plain JDBC — `PreparedStatement` everywhere, no ORM |
| Tests       | JUnit 5 (59 tests: DSA, models, DAO-backed services)|

**Why SQLite instead of MySQL.** The brief mentioned "MySQL or SQLite for
simplicity." SQLite was chosen so the project runs with **zero external
setup** — `git clone` and `mvn compile exec:java` is enough, no database
server to install or credentials to configure, which matters for anyone
evaluating this from a fresh machine. All SQL in `schema.sql` and the DAOs
is standard SQL; moving to MySQL later means changing the JDBC URL/driver
in `DBConnection`, swapping `AUTOINCREMENT` for `AUTO_INCREMENT` in
`schema.sql`, and — for exact currency handling — using `DECIMAL(10,2)`
columns instead of SQLite's dynamic typing (see **Known limitations**).

## Package structure

```
src/main/java/com/inventory/
├── Main.java              Console entry point: login loop + role menus. No SQL, no business logic.
├── model/                 Plain data classes: User, Role, Product, Order, OrderItem, OrderStatus
├── exception/              5 custom checked/unchecked exceptions (see below)
├── dao/                    All JDBC/SQL: UserDAO, ProductDAO, OrderDAO
├── service/                Business logic + DSA: UserService, ProductService, OrderService
└── util/
    ├── DBConnection.java   Connection factory + schema bootstrap
    ├── SortUtil.java       Hand-written merge sort
    └── SearchUtil.java     Hand-written binary search + HashMap index

src/main/resources/schema.sql   Table definitions + sample data, run automatically on first launch
src/test/java/com/inventory/    59 JUnit 5 tests, mirroring the main package structure
```

**Why each SQL statement lives in the DAO, not the service.** Every
`PreparedStatement` is built and executed inside `dao/`. The `service/`
classes decide *what* should happen (validate a cart, pick the next order
off the queue) and call a DAO method to make it happen — they never see a
`Connection` or write SQL. `OrderDAO` is the one exception worth calling
out: it owns the order-placement **transaction** (`setAutoCommit(false)`,
commit/rollback), because "no SQL in the service layer" means the code
that begins and ends a transaction belongs in the DAO too.

## Setup and running

Requires JDK 17+ and Maven. No database server, no configuration file.

```bash
git clone https://github.com/rishabhuke/inventory-order-management.git
cd inventory-order-management
mvn compile exec:java
```

On first launch, `DBConnection` detects that no `users` table exists and
runs `schema.sql` automatically, creating `inventory.db` in the working
directory with 12 sample products and 3 users:

| Username | Password  | Role     |
|----------|-----------|----------|
| admin    | admin123  | ADMIN    |
| alice    | alice123  | CUSTOMER |
| bob      | bob123    | CUSTOMER |

To run the test suite:

```bash
mvn test
```

`inventory.db` is git-ignored; delete it any time to reset to the sample
data on the next run.

## Key DSA concepts used, and why

Each of these was picked to fit what the operation actually needs, not
just to check a box:

- **Merge sort** (`SortUtil.mergeSort`) sorts products by price or stock.
  Chosen over quick sort because it's **O(n log n) in every case** — a
  product list read from the database in id order is close to sorted,
  which is quick sort's worst case with a naive pivot — and because it's
  **stable**, so products with equal prices stay in a predictable (id)
  order instead of shuffling on every sort.

- **Binary search** (`SearchUtil.lowerBound` and friends) powers exact and
  prefix product-name search in **O(log n + k)** for k results, against a
  name-sorted list maintained by `ProductService`. Binary search can only
  answer "starts with", not "contains" — that's a deliberate scope
  decision, documented in the code, not an oversight.

- **`HashMap<String, List<Product>>`** indexes products by category for
  **O(1)**-average category lookups — the right structure for exact-match
  keys with many values per key, where sorting would be unnecessary work.

- **`PriorityQueue`** (a binary min-heap) does two jobs: `getLowStockAlerts`
  surfaces the products with the least stock first in
  O(k log k), and the order-processing queue in `OrderService` always
  pops the **highest-value pending order** next (ties broken oldest-first),
  in O(log n) per insert/remove.

- **`Stack`** (modeled with `ArrayDeque`'s push/pop — the modern
  replacement for the legacy, synchronized `java.util.Stack`) gives each
  customer's order history in **LIFO** order: the DAO returns orders
  oldest-first, and pushing them one by one leaves the most recent order
  on top. `ORDER BY order_id DESC` would give the same result in one line;
  the stack is here specifically to demonstrate LIFO access.

## Exception handling

Five custom exceptions, each with one job:

| Exception                      | Thrown when                                                    |
|---------------------------------|------------------------------------------------------------------|
| `InsufficientStockException`   | An order line asks for more than is in stock (checked)         |
| `InvalidUserException`         | Bad login, or a role tries an action it isn't allowed (checked)|
| `ProductNotFoundException`     | A product id doesn't match any active product (checked)        |
| `OrderProcessingException`     | Empty cart, bad quantity, or an order fails for a non-stock reason (checked) |
| `DatabaseException`            | Wraps a raw `SQLException` (unchecked) so `java.sql` never leaks into `service`/`Main` |

`Main` catches every exception a service call can throw and prints a
message instead of a stack trace — nothing in this program should ever
crash the console session, including the input stream closing mid-prompt
(Ctrl+D), which is handled explicitly.

## Data model and transaction design

```
users            products (soft-delete via is_active)
  │                  │
  │ 1              N │ 1
  ▼                  ▼
orders  ──1───N──  order_items  ──N───1──  products
```

- **Products are soft-deleted** (`is_active = 0`), never physically
  removed. A hard delete of a product that appears in any order is
  blocked by the foreign key from `order_items` — deleting it would erase
  part of order history. `order_items` also stores `unit_price`, a
  **snapshot** of the price at purchase time, so a later price change never
  rewrites past orders.

- **Placing an order is one JDBC transaction** (`OrderDAO.placeOrder`):
  insert the order, then for every line run a guarded update —
  `UPDATE products SET stock_quantity = stock_quantity - ? WHERE ... AND
  stock_quantity >= ?` — and insert the order line. If *any* line fails
  (not enough stock, unknown product, a SQL error), the whole transaction
  rolls back: no order row is left behind, and no other line's stock
  deduction survives. The guarded `UPDATE` is what actually prevents
  overselling — it's checked by the database at write time, so it holds
  even if the in-memory cache described below is stale.

## The in-memory cache and search indexes

`ProductService` keeps a `HashMap<Integer, Product>` cache for O(1) lookup
by id, plus two indexes rebuilt lazily after any change: a
name-sorted list (for binary search) and a category `HashMap` (built with
`SearchUtil.buildIndex`). The rule that keeps them from drifting out of
sync with the database: **every write goes to the database first**, and
the cache is only updated once the database has accepted the change. After
placing an order, `OrderService` doesn't decrement the cached stock by
hand — it re-reads the affected products from the database, which is the
same thing the DAO's guarded update just validated, so the cache can never
disagree with what's actually stored.

## Known limitations (and what would come next)

Being upfront about these because a portfolio project should be judged on
correctness *and* self-awareness of its edges:

- **Single process.** The cache assumes it's the only thing writing to
  `inventory.db`. A second process editing the same file wouldn't be
  reflected until the next cache refresh — though the database
  transaction still guarantees stock is never oversold, since it re-checks
  at write time regardless of what the cache believes.
- **Passwords are stored in plain text.** Fine for a learning project, not
  for production. Next step: hash with BCrypt or PBKDF2 on the way in,
  compare hashes on login.
- **SQLite has no true `DECIMAL` type**, so prices come back as whatever
  precision SQLite feels like and are normalised to 2 decimal places on
  read. `BigDecimal` is used everywhere in Java for exact arithmetic; the
  MySQL migration path would use `DECIMAL(10,2)` columns for an exact
  round trip.
- **Order-processing priority is strict "highest value first."** A large
  order can indefinitely delay a small one. A production version would add
  aging (priority rises the longer an order waits).
- **No password hashing, no pagination, no concurrent-user locking** — all
  reasonable next steps, deliberately left out to keep this project's
  scope demonstrable in one sitting.

## Sample console interaction

An actual run (`mvn compile exec:java`), lightly trimmed for length. The
admin views the catalogue, checks low-stock alerts, and searches by name;
the customer builds a cart and places an order; the admin then processes
it off the priority queue.

```
Inventory & Order Management System
------------------------------------

1. Login   2. Exit
Choice: 1
Username: admin
Password: admin123
Welcome, admin (ADMIN)

--- Admin menu (admin) ---
 1. View all products
 ...
 8. Low-stock alerts
 ...
Choice: 8
ID   Name                     Category            Price   Stock
4    HD Webcam                Electronics       2199.00       2
12   Water Bottle             Home               349.00       3
9    Gel Pen Pack             Stationery         149.00       4
10   Sticky Notes             Stationery          59.00       6
3    USB-C Hub                Electronics       1299.00       8

Choice: 7
1. By exact name   2. By name prefix   3. By category
Choice: 1
Name: Wireless Mouse
ID   Name                     Category            Price   Stock
1    Wireless Mouse           Electronics        799.00      50

Choice: 11

1. Login   2. Exit
Choice: 1
Username: alice
Password: alice123
Welcome, alice (CUSTOMER)

--- Customer menu (alice) ---
1. View all products
2. Search products
3. Place an order
4. View my order history
5. Logout
Choice: 3
Building your order. Enter product id 0 when done.
...
Product id (0 to finish): 1
Quantity: 2
Added to cart. (1 distinct product(s) so far)
...
Product id (0 to finish): 6
Quantity: 1
Added to cart. (2 distinct product(s) so far)
...
Product id (0 to finish): 0
Order placed: Order #1 [PLACED] 2026-09-22T04:29:14 - total 1897.00
    - Wireless Mouse x2 @ 799.00 = 1598.00
    - Phone Stand x1 @ 299.00 = 299.00

Choice: 4
Your orders, most recent first:
Order #1 [PLACED] 2026-09-22T04:29:14 - total 1897.00
    - Wireless Mouse x2 @ 799.00 = 1598.00
    - Phone Stand x1 @ 299.00 = 299.00

Choice: 5

1. Login   2. Exit
Choice: 1
Username: admin
Password: admin123
Welcome, admin (ADMIN)

--- Admin menu (admin) ---
...
Choice: 10
Processed Order #1 [PROCESSED] 2026-09-22T04:29:14 - total 1897.00
    - Wireless Mouse x2 @ 799.00 = 1598.00
    - Phone Stand x1 @ 299.00 = 299.00

Choice: 11

1. Login   2. Exit
Choice: 2

Goodbye!
```
