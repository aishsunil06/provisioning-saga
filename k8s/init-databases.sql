-- One PostgreSQL container, three logical databases, one per service.
--
-- A container per database would be more faithful to database-per-service, and
-- it would cost three times the memory to demonstrate something no measurement
-- in this repository can see.
--
-- What this does enforce: PostgreSQL has no cross-database query, so no service
-- can read another's tables even by accident, and the checker in bench/ has to
-- open three separate connections to compare them at all. That is the boundary
-- every consistency measurement here depends on.
--
-- What it does not enforce: all three databases are reached with the same
-- superuser credentials. A service that went looking could connect to a
-- sibling's database on purpose. Per-database roles would close that, and the
-- reason they are not here is cost rather than design: this script runs only on
-- an empty data directory, so adding roles means recreating the volume and
-- re-running both arms of the scaling experiment. It is a real gap, written
-- down rather than implied away.

CREATE DATABASE orders_db;
CREATE DATABASE inventory_db;
CREATE DATABASE provisioning_db;
