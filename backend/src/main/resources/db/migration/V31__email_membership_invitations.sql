ALTER TABLE account_invitations ADD COLUMN prospective_full_name varchar(200)
 CHECK (prospective_full_name IS NULL OR nullif(btrim(prospective_full_name),'') IS NOT NULL);
