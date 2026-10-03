CREATE INDEX idx_files_active_folder_listing
ON files(owner_id, folder_id, name)
WHERE deleted_at IS NULL
  AND status = 'READY';