ALTER TABLE members ADD COLUMN version bigint NOT NULL DEFAULT 0 CHECK (version >= 0);
