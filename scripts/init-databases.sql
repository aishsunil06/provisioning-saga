-- One PostgreSQL container, three logical databases, one per service.
--
-- A container per database would be more faithful to database-per-service, and
-- it would cost three times the memory to demonstrate something no measurement
-- in this repository can see. The boundary that matters is that no service can
-- read another's tables, and separate databases with separate credentials
-- enforce that just as well as separate hosts.

CREATE DATABASE orders_db;
CREATE DATABASE inventory_db;
CREATE DATABASE provisioning_db;
