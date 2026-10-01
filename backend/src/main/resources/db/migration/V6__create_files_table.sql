CREATE TABLE files (
    id UUID PRIMARY KEY,

    owner_id UUID NOT NULL,
    folder_id UUID,

    name VARCHAR(255) NOT NULL,
    storage_key VARCHAR(1024) NOT NULL,
    content_type VARCHAR(255),
    size_bytes BIGINT NOT NULL,

    status VARCHAR(32) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,

    CONSTRAINT fk_files_owner
        FOREIGN KEY (owner_id)
        REFERENCES users(id),

    CONSTRAINT fk_files_folder_owner
        FOREIGN KEY (folder_id, owner_id)
        REFERENCES folders(id, owner_id),

    CONSTRAINT chk_files_name_not_empty
        CHECK (LENGTH(TRIM(name)) > 0),

    CONSTRAINT chk_files_size_non_negative
        CHECK (size_bytes >= 0),

    CONSTRAINT chk_files_status
        CHECK (status IN ('UPLOADING', 'READY', 'FAILED')),

    CONSTRAINT uq_files_storage_key
        UNIQUE (storage_key)
);

CREATE UNIQUE INDEX uq_files_active_name
ON files(owner_id, folder_id, name)
NULLS NOT DISTINCT
WHERE deleted_at IS NULL;

CREATE INDEX idx_files_owner_folder
ON files(owner_id, folder_id);

CREATE INDEX idx_files_owner_status
ON files(owner_id, status);

CREATE INDEX idx_files_owner_deleted
ON files(owner_id, deleted_at);