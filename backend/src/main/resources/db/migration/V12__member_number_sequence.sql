-- Short, database-sequenced member numbers; existing numbers keep their values.
CREATE SEQUENCE member_number_seq AS bigint START WITH 1;
SELECT setval('member_number_seq',
    GREATEST(1, COALESCE((SELECT max(substring(member_number FROM 4)::bigint)
                         FROM members WHERE member_number ~ '^CU-[0-9]{6,}$'), 0)),
    EXISTS (SELECT 1 FROM members WHERE member_number ~ '^CU-[0-9]{6,}$'));
