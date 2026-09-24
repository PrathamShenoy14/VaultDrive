ALTER TABLE users
DROP CONSTRAINT users_email_key;

CREATE UNIQUE INDEX users_email_unique_lower
ON users (LOWER(email));