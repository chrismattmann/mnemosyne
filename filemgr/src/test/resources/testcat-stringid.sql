DROP TABLE GenericFile_metadata IF EXISTS;
DROP TABLE GenericFile_reference IF EXISTS;
DROP TABLE products IF EXISTS;

CREATE TABLE GenericFile_metadata
(
  product_id varchar(100) NOT NULL,
  element_id varchar(1000) NOT NULL,
  metadata_value varchar(2500) NOT NULL
);

CREATE TABLE GenericFile_reference
(
  product_id varchar(100) NOT NULL,
  product_orig_reference varchar(2000) NOT NULL,
  product_datastore_reference varchar(2000),
  product_reference_filesize int NOT NULL,
  product_reference_mimetype varchar(50)
);

-- The 'products' table shape the productIdString branch is written for:
-- a string product_id, plus the product_datetime column that branch used
-- to order by. Taken from the commented example in
-- cas-filemgr-schema-mysql.sql, which is the only place it is written down.
CREATE TABLE products (
  product_id varchar(100) NOT NULL PRIMARY KEY,
  product_structure varchar(20) NOT NULL,
  product_type_id varchar(255) NOT NULL,
  product_name varchar(255) NOT NULL,
  product_transfer_status varchar(255) NOT NULL,
  product_datetime timestamp
);

INSERT INTO products VALUES ('urn:prod:a', 'Flat', 'urn:oodt:GenericFile', 'a', 'RECEIVED', '2026-01-01 00:00:00');
INSERT INTO products VALUES ('urn:prod:b', 'Flat', 'urn:oodt:GenericFile', 'b', 'RECEIVED', '2026-01-02 00:00:00');
INSERT INTO products VALUES ('urn:prod:c', 'Flat', 'urn:oodt:GenericFile', 'c', 'RECEIVED', '2026-01-03 00:00:00');

INSERT INTO GenericFile_metadata VALUES ('urn:prod:a', 'urn:oodt:ProductName', 'a');
INSERT INTO GenericFile_metadata VALUES ('urn:prod:b', 'urn:oodt:ProductName', 'b');
INSERT INTO GenericFile_metadata VALUES ('urn:prod:c', 'urn:oodt:ProductName', 'c');
