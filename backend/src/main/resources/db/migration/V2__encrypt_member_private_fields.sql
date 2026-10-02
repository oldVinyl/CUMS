-- There is no member write API in V1; these columns have never contained application data.
ALTER TABLE members
    DROP COLUMN phone,
    DROP COLUMN date_of_birth,
    DROP COLUMN address,
    DROP COLUMN next_of_kin_name,
    DROP COLUMN next_of_kin_phone,
    DROP COLUMN next_of_kin_relationship,
    ADD COLUMN encrypted_phone bytea,
    ADD COLUMN phone_blind_index varchar(67),
    ADD COLUMN id_number_blind_index varchar(67),
    ADD COLUMN encrypted_date_of_birth bytea,
    ADD COLUMN encrypted_address bytea,
    ADD COLUMN encrypted_next_of_kin bytea;

CREATE INDEX members_phone_blind_idx ON members (phone_blind_index)
    WHERE phone_blind_index IS NOT NULL;
CREATE INDEX members_id_blind_idx ON members (id_number_blind_index)
    WHERE id_number_blind_index IS NOT NULL;
