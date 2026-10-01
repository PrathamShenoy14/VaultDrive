DROP INDEX uq_files_active_name;

CREATE UNIQUE INDEX uq_files_active_name
ON files(owner_id, folder_id, name)
NULLS NOT DISTINCT
WHERE deleted_at IS NULL
  AND status IN ('UPLOADING', 'READY');
