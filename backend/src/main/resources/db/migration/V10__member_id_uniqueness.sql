CREATE UNIQUE INDEX members_id_blind_unique_idx ON members (id_number_blind_index)
    WHERE id_number_blind_index IS NOT NULL;
