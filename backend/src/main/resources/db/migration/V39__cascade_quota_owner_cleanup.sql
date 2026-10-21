-- Account cleanup follows the already-required deletion of owned file metadata.
-- V38 is retained unchanged in schemas where it has already been applied.
ALTER TABLE storage_quotas DROP CONSTRAINT storage_quotas_owner_id_fkey;
ALTER TABLE storage_quotas ADD CONSTRAINT storage_quotas_owner_id_fkey
    FOREIGN KEY (owner_id) REFERENCES users(id) ON DELETE CASCADE;
