ALTER TABLE folders
ADD COLUMN purge_requested_at TIMESTAMPTZ;

ALTER TABLE folders
ADD CONSTRAINT chk_folders_purge_requires_deleted
CHECK (
    purge_requested_at IS NULL
    OR deleted_at IS NOT NULL
);
