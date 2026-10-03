ALTER TABLE members ADD COLUMN join_date_basis varchar(24) NOT NULL DEFAULT 'CONFIRMED' CHECK (join_date_basis IN ('CONFIRMED','SOURCE_BASELINE'));
