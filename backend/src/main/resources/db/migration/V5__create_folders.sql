CREATE TABLE folders (
    id UUID PRIMARY KEY,

    owner_id UUID NOT NULL,

    parent_folder_id UUID,

    name VARCHAR(255) NOT NULL,

    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,

    CONSTRAINT fk_folders_owner
        FOREIGN KEY (owner_id)
        REFERENCES users(id),

    CONSTRAINT chk_folders_name_not_empty
        CHECK (LENGTH(TRIM(name)) > 0),

    CONSTRAINT chk_folders_not_own_parent
        CHECK (
            parent_folder_id IS NULL
            OR parent_folder_id <> id
        ),

    CONSTRAINT uq_folders_id_owner
        UNIQUE (id, owner_id),

    CONSTRAINT fk_folders_parent_owner
        FOREIGN KEY (parent_folder_id, owner_id)
        REFERENCES folders(id, owner_id)
);

CREATE UNIQUE INDEX uq_folders_active_name
ON folders (
    owner_id,
    parent_folder_id,
    name
)
NULLS NOT DISTINCT
WHERE deleted_at IS NULL;

CREATE INDEX idx_folders_parent_owner
ON folders (parent_folder_id, owner_id);