export interface paths {
    "/api/v1/activity": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Authenticated owner's history/usage only; no client owner selector. History survives removal and grants no resource access. Unknown/foreign history filters return an empty page. Usage is logical used/reserved accounting, not physical reclaim evidence. */
        get: operations["ResourceActivity_list"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/login": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Public registration/login; refresh requires cookie + CSRF with no Authorization header. Logout requires bearer + matching cookie + CSRF. Login requires X-VaultDrive-Client: 1. Login/refresh/logout reject query strings. Serialize rotation; uncertain refresh requires login. Seven-day absolute refresh family, 30-minute default JWT. */
        post: operations["Auth_login"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/logout": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Public registration/login; refresh requires cookie + CSRF with no Authorization header. Logout requires bearer + matching cookie + CSRF. Login requires X-VaultDrive-Client: 1. Login/refresh/logout reject query strings. Serialize rotation; uncertain refresh requires login. Seven-day absolute refresh family, 30-minute default JWT. */
        post: operations["Auth_logout"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/refresh": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Public registration/login; refresh requires cookie + CSRF with no Authorization header. Logout requires bearer + matching cookie + CSRF. Login requires X-VaultDrive-Client: 1. Login/refresh/logout reject query strings. Serialize rotation; uncertain refresh requires login. Seven-day absolute refresh family, 30-minute default JWT. */
        post: operations["Auth_refresh"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/auth/register": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Public registration/login; refresh requires cookie + CSRF with no Authorization header. Logout requires bearer + matching cookie + CSRF. Login requires X-VaultDrive-Client: 1. Login/refresh/logout reject query strings. Serialize rotation; uncertain refresh requires login. Seven-day absolute refresh family, 30-minute default JWT. */
        post: operations["Auth_register"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/copy-operations/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Owner-only source/destination and retained operation. Visible collaborators 403; missing/foreign/hidden resources or foreign operation 404. Required retained Idempotency-Key on admission; changed normalized input 409. GET performs no storage work. Explicit execute/recover/cancel; folder discard returns disposalOperationId. No autonomous copy worker. */
        get: operations["FileCopy_get"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/copy-operations/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Owner-only source/destination and retained operation. Visible collaborators 403; missing/foreign/hidden resources or foreign operation 404. Required retained Idempotency-Key on admission; changed normalized input 409. GET performs no storage work. Explicit execute/recover/cancel; folder discard returns disposalOperationId. No autonomous copy worker. */
        post: operations["FileCopy_cancel"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/copy-operations/{id}/execute": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Owner-only source/destination and retained operation. Visible collaborators 403; missing/foreign/hidden resources or foreign operation 404. Required retained Idempotency-Key on admission; changed normalized input 409. GET performs no storage work. Explicit execute/recover/cancel; folder discard returns disposalOperationId. No autonomous copy worker. */
        post: operations["FileCopy_execute"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/copy-operations/{id}/recover": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Owner-only source/destination and retained operation. Visible collaborators 403; missing/foreign/hidden resources or foreign operation 404. Required retained Idempotency-Key on admission; changed normalized input 409. GET performs no storage work. Explicit execute/recover/cancel; folder discard returns disposalOperationId. No autonomous copy worker. */
        post: operations["FileCopy_recover"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/deletion-operations/{operationId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Original owner-only retained operation, including after removal. Unknown/foreign IDs 404. Logical completion is distinct from physical reclamation; audited operator recovery can return FAILED to PROCESSING. */
        get: operations["DeletionOperation_get"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/empty-file-creations/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Owner or destination Editor; status/recovery requires owner or original initiator with current destination Editor. Viewer admission 403; unknown/foreign/hidden 404. Retained key scoped to owner/initiator. Replay never repeats admitted PUT. Recover uses positive evidence only. */
        get: operations["EmptyFile_get"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/empty-file-creations/{id}/recover": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Owner or destination Editor; status/recovery requires owner or original initiator with current destination Editor. Viewer admission 403; unknown/foreign/hidden 404. Retained key scoped to owner/initiator. Replay never repeats admitted PUT. Recover uses positive evidence only. */
        post: operations["EmptyFile_recover"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/files": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        get: operations["File_listFiles"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/files/empty": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Owner or destination Editor; status/recovery requires owner or original initiator with current destination Editor. Viewer admission 403; unknown/foreign/hidden 404. Retained key scoped to owner/initiator. Replay never repeats admitted PUT. Recover uses positive evidence only. */
        post: operations["EmptyFile_create"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/files/trash": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        get: operations["File_listTrash"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/files/{fileId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        get: operations["File_getFile"];
        put?: never;
        post?: never;
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        delete: operations["File_trashFile"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/files/{fileId}/download": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        get: operations["File_downloadFile"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/files/{fileId}/move": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        patch: operations["File_moveFile"];
        trace?: never;
    };
    "/api/v1/files/{fileId}/name": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        patch: operations["File_renameFile"];
        trace?: never;
    };
    "/api/v1/files/{fileId}/permanent": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        delete: operations["File_permanentlyDeleteFile"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/files/{fileId}/restore": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        post: operations["File_restoreFile"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/files/{fileId}/share-links": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Owner-only file links. Visible collaborator 403; hidden/foreign 404. Creation requires active READY file; owner inspection/revocation allowed in Trash before purge. Secret returned once; null expiry defaults 24 hours, maximum seven days. Repeated revoke of matching retained link is idempotent. */
        get: operations["ShareLink_list"];
        put?: never;
        /** @description Owner-only file links. Visible collaborator 403; hidden/foreign 404. Creation requires active READY file; owner inspection/revocation allowed in Trash before purge. Secret returned once; null expiry defaults 24 hours, maximum seven days. Repeated revoke of matching retained link is idempotent. */
        post: operations["ShareLink_create"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/files/{fileId}/share-links/{linkId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** @description Owner-only file links. Visible collaborator 403; hidden/foreign 404. Creation requires active READY file; owner inspection/revocation allowed in Trash before purge. Secret returned once; null expiry defaults 24 hours, maximum seven days. Repeated revoke of matching retained link is idempotent. */
        delete: operations["ShareLink_revoke"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/files/{id}/grants": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Owner-only grant management. Visible collaborator 403, inaccessible/foreign 404. PATCH requires quoted If-Match: missing 428, malformed 400, stale 412. Only VIEWER/EDITOR can be granted. Revoke absent/wrong-target direct grant is a no-op after target authorization. */
        get: operations["Sharing_list_file"];
        put?: never;
        /** @description Owner-only grant management. Visible collaborator 403, inaccessible/foreign 404. PATCH requires quoted If-Match: missing 428, malformed 400, stale 412. Only VIEWER/EDITOR can be granted. Revoke absent/wrong-target direct grant is a no-op after target authorization. */
        post: operations["Sharing_create_file"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/files/{id}/grants/{grantId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** @description Owner-only grant management. Visible collaborator 403, inaccessible/foreign 404. PATCH requires quoted If-Match: missing 428, malformed 400, stale 412. Only VIEWER/EDITOR can be granted. Revoke absent/wrong-target direct grant is a no-op after target authorization. */
        delete: operations["Sharing_revoke_file"];
        options?: never;
        head?: never;
        /** @description Owner-only grant management. Visible collaborator 403, inaccessible/foreign 404. PATCH requires quoted If-Match: missing 428, malformed 400, stale 412. Only VIEWER/EDITOR can be granted. Revoke absent/wrong-target direct grant is a no-op after target authorization. */
        patch: operations["Sharing_update_file"];
        trace?: never;
    };
    "/api/v1/files/{sourceId}/copy": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Owner-only source/destination and retained operation. Visible collaborators 403; missing/foreign/hidden resources or foreign operation 404. Required retained Idempotency-Key on admission; changed normalized input 409. GET performs no storage work. Explicit execute/recover/cancel; folder discard returns disposalOperationId. No autonomous copy worker. */
        post: operations["FileCopy_copy"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/folder-copy-operations/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Owner-only source/destination and retained operation. Visible collaborators 403; missing/foreign/hidden resources or foreign operation 404. Required retained Idempotency-Key on admission; changed normalized input 409. GET performs no storage work. Explicit execute/recover/cancel; folder discard returns disposalOperationId. No autonomous copy worker. */
        get: operations["FolderCopy_get"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/folder-copy-operations/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Owner-only source/destination and retained operation. Visible collaborators 403; missing/foreign/hidden resources or foreign operation 404. Required retained Idempotency-Key on admission; changed normalized input 409. GET performs no storage work. Explicit execute/recover/cancel; folder discard returns disposalOperationId. No autonomous copy worker. */
        post: operations["FolderCopy_cancel"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/folder-copy-operations/{id}/discard": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Owner-only source/destination and retained operation. Visible collaborators 403; missing/foreign/hidden resources or foreign operation 404. Required retained Idempotency-Key on admission; changed normalized input 409. GET performs no storage work. Explicit execute/recover/cancel; folder discard returns disposalOperationId. No autonomous copy worker. */
        post: operations["FolderCopy_discard"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/folder-copy-operations/{id}/execute": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Owner-only source/destination and retained operation. Visible collaborators 403; missing/foreign/hidden resources or foreign operation 404. Required retained Idempotency-Key on admission; changed normalized input 409. GET performs no storage work. Explicit execute/recover/cancel; folder discard returns disposalOperationId. No autonomous copy worker. */
        post: operations["FolderCopy_execute"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/folder-copy-operations/{id}/recover": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Owner-only source/destination and retained operation. Visible collaborators 403; missing/foreign/hidden resources or foreign operation 404. Required retained Idempotency-Key on admission; changed normalized input 409. GET performs no storage work. Explicit execute/recover/cancel; folder discard returns disposalOperationId. No autonomous copy worker. */
        post: operations["FolderCopy_recover"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/folders": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        get: operations["Folder_listRootFolders"];
        put?: never;
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        post: operations["Folder_createFolder"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/folders/{folderId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        get: operations["Folder_getFolder"];
        put?: never;
        post?: never;
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        delete: operations["Folder_deleteFolder"];
        options?: never;
        head?: never;
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        patch: operations["Folder_renameFolder"];
        trace?: never;
    };
    "/api/v1/folders/{folderId}/children": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        get: operations["Folder_listChildFolders"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/folders/{folderId}/move": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        patch: operations["Folder_moveFolder"];
        trace?: never;
    };
    "/api/v1/folders/{id}/grants": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Owner-only grant management. Visible collaborator 403, inaccessible/foreign 404. PATCH requires quoted If-Match: missing 428, malformed 400, stale 412. Only VIEWER/EDITOR can be granted. Revoke absent/wrong-target direct grant is a no-op after target authorization. */
        get: operations["Sharing_list_folder"];
        put?: never;
        /** @description Owner-only grant management. Visible collaborator 403, inaccessible/foreign 404. PATCH requires quoted If-Match: missing 428, malformed 400, stale 412. Only VIEWER/EDITOR can be granted. Revoke absent/wrong-target direct grant is a no-op after target authorization. */
        post: operations["Sharing_create_folder"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/folders/{id}/grants/{grantId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** @description Owner-only grant management. Visible collaborator 403, inaccessible/foreign 404. PATCH requires quoted If-Match: missing 428, malformed 400, stale 412. Only VIEWER/EDITOR can be granted. Revoke absent/wrong-target direct grant is a no-op after target authorization. */
        delete: operations["Sharing_revoke_folder"];
        options?: never;
        head?: never;
        /** @description Owner-only grant management. Visible collaborator 403, inaccessible/foreign 404. PATCH requires quoted If-Match: missing 428, malformed 400, stale 412. Only VIEWER/EDITOR can be granted. Revoke absent/wrong-target direct grant is a no-op after target authorization. */
        patch: operations["Sharing_update_folder"];
        trace?: never;
    };
    "/api/v1/folders/{sourceId}/copy": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Owner-only source/destination and retained operation. Visible collaborators 403; missing/foreign/hidden resources or foreign operation 404. Required retained Idempotency-Key on admission; changed normalized input 409. GET performs no storage work. Explicit execute/recover/cancel; folder discard returns disposalOperationId. No autonomous copy worker. */
        post: operations["FolderCopy_copy"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/health": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Public process liveness or cached readiness; invalid supplied bearer credentials still fail authentication. */
        get: operations["Health_health"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/health/ready": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Public process liveness or cached readiness; invalid supplied bearer credentials still fail authentication. */
        get: operations["Health_ready"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/notifications": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Recipient-only list/count/read. Unknown and foreign notification IDs 404; repeated mark-read 204. Actionable is informational and must be reauthorized; obsolete notifications expose generic history. Delivery is asynchronous. */
        get: operations["SharingNotification_list"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/notifications/unread-count": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Recipient-only list/count/read. Unknown and foreign notification IDs 404; repeated mark-read 204. Actionable is informational and must be reauthorized; obsolete notifications expose generic history. Delivery is asynchronous. */
        get: operations["SharingNotification_unreadCount"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/notifications/{id}/read": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /** @description Recipient-only list/count/read. Unknown and foreign notification IDs 404; repeated mark-read 204. Actionable is informational and must be reauthorized; obsolete notifications expose generic history. Delivery is asynchronous. */
        patch: operations["SharingNotification_markRead"];
        trace?: never;
    };
    "/api/v1/permission-activity": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Authenticated owner's history/usage only; no client owner selector. History survives removal and grants no resource access. Unknown/foreign history filters return an empty page. Usage is logical used/reserved accounting, not physical reclaim evidence. */
        get: operations["PermissionActivity_list"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/public/share-links/{linkId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Exactly one X-VaultDrive-Share-Token; no query strings. JWT confers no link authority. Missing/invalid/expired/revoked/hidden links and unsupported public routes/methods share 404. Admission may return 429 first. */
        get: operations["PublicShareLink_metadata"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/public/share-links/{linkId}/download": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Exactly one X-VaultDrive-Share-Token; no query strings. JWT confers no link authority. Missing/invalid/expired/revoked/hidden links and unsupported public routes/methods share 404. Admission may return 429 first. */
        get: operations["PublicShareLink_download"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/search": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Authenticated owned/effectively shared active folders and READY files, visibility filtered before count/page. Parent null can mean root or private-parent redaction. Literal filename substring; fixed order; all filters AND. See search contract for MIME/date/range validation. */
        get: operations["Search_search"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/shared-with-me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Authenticated direct shared entry points with current effective access and private-parent redaction; strongest inherited role applies. Share links alone confer no authenticated access. */
        get: operations["Sharing_shared"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/trash/folders": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        get: operations["Trash_listTrashedFolders"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/trash/folders/{folderId}/permanent": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        delete: operations["Trash_permanentlyDeleteFolder"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/trash/folders/{folderId}/restore": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Bearer required. Active metadata/list/download: Owner/Viewer/Editor with complete lifecycle/ancestry checks and private-parent redaction. Creation: Owner/Editor destination; rename: Owner/Editor target; Editor Trash additionally requires editable immediate non-null parent. Move/restore/permanent delete/Trash feeds: owner only. Visible insufficient role 403; inaccessible/foreign/hidden 404. Root listings belong to caller. Restore can fall back to root and rename collisions. Capabilities are fresh hints; each mutation reauthorizes. */
        post: operations["Trash_restoreFolder"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/upload-sessions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Reservation: owner root or accessible destination Owner/Editor; Viewer 403. Existing session: owner or original initiator with current destination Editor; all other callers 404. One Create/Complete/Abort dispatch, immutable part evidence; 202 never authorizes blind retry. See multipart feature contracts. */
        post: operations["MultipartSession_reserve"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/upload-sessions/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Reservation: owner root or accessible destination Owner/Editor; Viewer 403. Existing session: owner or original initiator with current destination Editor; all other callers 404. One Create/Complete/Abort dispatch, immutable part evidence; 202 never authorizes blind retry. See multipart feature contracts. */
        get: operations["MultipartSession_get"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/upload-sessions/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Reservation: owner root or accessible destination Owner/Editor; Viewer 403. Existing session: owner or original initiator with current destination Editor; all other callers 404. One Create/Complete/Abort dispatch, immutable part evidence; 202 never authorizes blind retry. See multipart feature contracts. */
        post: operations["MultipartSession_cancel"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/upload-sessions/{id}/cancel/recover": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Reservation: owner root or accessible destination Owner/Editor; Viewer 403. Existing session: owner or original initiator with current destination Editor; all other callers 404. One Create/Complete/Abort dispatch, immutable part evidence; 202 never authorizes blind retry. See multipart feature contracts. */
        post: operations["MultipartSession_recoverCancellation"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/upload-sessions/{id}/cancellation": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Reservation: owner root or accessible destination Owner/Editor; Viewer 403. Existing session: owner or original initiator with current destination Editor; all other callers 404. One Create/Complete/Abort dispatch, immutable part evidence; 202 never authorizes blind retry. See multipart feature contracts. */
        get: operations["MultipartSession_cancellation"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/upload-sessions/{id}/complete": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Reservation: owner root or accessible destination Owner/Editor; Viewer 403. Existing session: owner or original initiator with current destination Editor; all other callers 404. One Create/Complete/Abort dispatch, immutable part evidence; 202 never authorizes blind retry. See multipart feature contracts. */
        post: operations["MultipartSession_complete"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/upload-sessions/{id}/initiate": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Reservation: owner root or accessible destination Owner/Editor; Viewer 403. Existing session: owner or original initiator with current destination Editor; all other callers 404. One Create/Complete/Abort dispatch, immutable part evidence; 202 never authorizes blind retry. See multipart feature contracts. */
        post: operations["MultipartSession_initiate"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/upload-sessions/{id}/part-urls": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Reservation: owner root or accessible destination Owner/Editor; Viewer 403. Existing session: owner or original initiator with current destination Editor; all other callers 404. One Create/Complete/Abort dispatch, immutable part evidence; 202 never authorizes blind retry. See multipart feature contracts. */
        post: operations["MultipartSession_parts"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/upload-sessions/{id}/parts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Reservation: owner root or accessible destination Owner/Editor; Viewer 403. Existing session: owner or original initiator with current destination Editor; all other callers 404. One Create/Complete/Abort dispatch, immutable part evidence; 202 never authorizes blind retry. See multipart feature contracts. */
        get: operations["MultipartSession_list"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/upload-sessions/{id}/recover": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        /** @description Reservation: owner root or accessible destination Owner/Editor; Viewer 403. Existing session: owner or original initiator with current destination Editor; all other callers 404. One Create/Complete/Abort dispatch, immutable part evidence; 202 never authorizes blind retry. See multipart feature contracts. */
        post: operations["MultipartSession_recover"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/users/me": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Authenticated subject profile only, cookie alone insufficient. PATCH changes displayName only. Unknown user 401. No password/email/account-management endpoint. */
        get: operations["User_getCurrentUser"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        /** @description Authenticated subject profile only, cookie alone insufficient. PATCH changes displayName only. Unknown user 401. No password/email/account-management endpoint. */
        patch: operations["User_updateCurrentUser"];
        trace?: never;
    };
    "/api/v1/users/me/storage": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        /** @description Authenticated owner's history/usage only; no client owner selector. History survives removal and grants no resource access. Unknown/foreign history filters return an empty page. Usage is logical used/reserved accounting, not physical reclaim evidence. */
        get: operations["StorageUsage_get"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        activity_ResourceActivityService_Activity: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            actorId: string;
            actorDisplayName: string;
            /** @enum {string} */
            actorAttribution: "CALLER" | "UPLOAD_INITIATOR" | "DELETION_REQUESTER";
            /** Format: uuid */
            ownerId: string;
            /** @enum {string} */
            resourceType: "FILE" | "FOLDER";
            /** Format: uuid */
            resourceId: string;
            resourceName: string;
            /** @enum {string} */
            eventType: "UPLOAD_COMPLETED" | "COPIED" | "FOLDER_CREATED" | "RENAMED" | "MOVED" | "TRASHED" | "RESTORED" | "PERMANENTLY_DELETED";
            beforeName: string | null;
            afterName: string | null;
            beforeParentId: string | null;
            afterParentId: string | null;
            beforeTrashed: boolean | null;
            afterTrashed: boolean | null;
            beforeStatus: string | null;
            afterStatus: string | null;
            /** Format: date-time */
            occurredAt: string;
        };
        activity_ResourceActivityService_ActivityPage: {
            content: components["schemas"]["activity_ResourceActivityService_Activity"][];
            /** Format: int32 */
            page: number;
            /** Format: int32 */
            size: number;
            /** Format: int64 */
            totalElements: number | bigint;
            /** Format: int64 */
            totalPages: number | bigint;
        };
        auth_dto_LoginRequest: {
            /** Format: email */
            email: string;
            password: string;
        };
        auth_dto_LoginResponse: {
            accessToken: string;
            /** @constant */
            tokenType: "Bearer";
            /** Format: int64 */
            expiresIn: number | bigint;
        };
        auth_dto_RegisterRequest: {
            /** Format: email */
            email: string;
            /** @description BCrypt UTF-8 byte limit also applies */
            password: string;
            displayName: string;
        };
        auth_dto_RegisterResponse: {
            /** Format: uuid */
            userId: string;
            message: string;
        };
        common_exception_ApiError: {
            /** Format: int32 */
            status: number;
            error: string;
            message: string;
        };
        common_health_HealthResponse: {
            status: string;
            service: string;
        };
        deletion_DeletionOperationController_Accepted: {
            /** Format: uuid */
            operationId: string;
        };
        deletion_DeletionOperationService_Status: {
            /** Format: uuid */
            operationId: string;
            resourceType: string;
            /** Format: uuid */
            resourceId: string;
            /** Format: date-time */
            requestedAt: string;
            /** @enum {string} */
            state: "ACCEPTED" | "PROCESSING" | "BLOCKED" | "FAILED" | "LOGICALLY_COMPLETED";
            reasonCode: string | null;
            logicallyCompletedAt: string | null;
        };
        file_EmptyFileTransactions_Request: {
            folderId?: string | null;
            /** @description Nonblank, trimmed then limited to 255 Java UTF-16 units; case-sensitive, no path separators/controls. Rename preserves existing file extension. Raw padding is not part of the normalized length limit. */
            name: string;
            contentType?: string | null;
        };
        file_EmptyFileTransactions_Status: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            fileId: string;
            /** @enum {string} */
            state: "RESERVED" | "UNKNOWN" | "STORAGE_COMPLETE" | "READY" | "FAILED" | "REMOVED";
            storageVerified: boolean;
        };
        file_FileCopyTransactions_Request: {
            destinationFolderId?: string | null;
            /** @description Nonblank, trimmed then limited to 255 Java UTF-16 units; case-sensitive, no path separators/controls. Rename preserves existing file extension. Raw padding is not part of the normalized length limit. */
            name: string;
        };
        file_FileCopyTransactions_Status: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            sourceFileId: string;
            /** Format: uuid */
            fileId: string;
            destinationFolderId: string | null;
            name: string;
            /** @enum {string} */
            state: "RESERVED" | "UNKNOWN" | "STORAGE_COMPLETE" | "READY" | "BLOCKED" | "CANCELLED" | "FAILED";
            /** Format: int64 */
            revision: number | bigint;
            storageVerified: boolean;
            /** Format: date-time */
            createdAt: string;
        };
        file_MultipartCancellationTransactions_Cancellation: {
            session: components["schemas"]["file_MultipartSessionService_Session"];
            cause: string | null;
            /** @enum {string} */
            disposition: "PENDING" | "UNKNOWN" | "BLOCKED" | "CANCELLED";
            reason: string | null;
            dispatchId: string | null;
            /** Format: int64 */
            revision: number | bigint;
            evidenceKind: string | null;
            acknowledgedAt: string | null;
        };
        file_MultipartSessionController_PartRequest: {
            parts: components["schemas"]["file_MultipartSessionService_PartExpectation"][];
        };
        file_MultipartSessionService_CompletionInfo: {
            /** @enum {string} */
            disposition: "PENDING" | "UNKNOWN" | "STORAGE_COMPLETE" | "BLOCKED" | "READY";
            /** Format: int64 */
            revision: number | bigint;
            dispatchId: string | null;
            receiptRecorded: boolean;
            reason: string | null;
        };
        file_MultipartSessionService_PartExpectation: {
            partNumber: number;
            /** Format: int64 */
            length: number | bigint;
            /** @description Canonical Base64 SHA-256, exactly 32 decoded bytes; final unused bits must be zero */
            sha256: string;
        };
        file_MultipartSessionService_RegisteredPart: {
            /** Format: int32 */
            partNumber: number;
            /** Format: int64 */
            length: number | bigint;
            sha256: string;
            /** Format: uuid */
            registrationId: string;
        };
        file_MultipartSessionService_Request: {
            folderId?: string | null;
            /** @description Nonblank, trimmed then limited to 255 Java UTF-16 units; case-sensitive, no path separators/controls. Rename preserves existing file extension. Raw padding is not part of the normalized length limit. */
            name: string;
            /** Format: int64 */
            size: number | bigint;
            contentType?: string | null;
        };
        file_MultipartSessionService_Session: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            fileId: string;
            /** @enum {string} */
            state: "RESERVED" | "INITIATING" | "OPEN" | "COMPLETION_DECIDED" | "ABANDONMENT_DECIDED";
            /** Format: int64 */
            revision: number | bigint;
            initiationId: string | null;
            uploadId: string | null;
            decisionId: string | null;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            expiresAt: string;
            /** Format: int64 */
            declaredSize: number | bigint;
            /** Format: int32 */
            partSize: number;
            /** Format: int32 */
            partCount: number;
            completion: components["schemas"]["file_MultipartSessionService_CompletionInfo"] | null;
            /** Format: uuid */
            ownerId: string;
            /** Format: uuid */
            initiatorUserId: string;
        };
        file_MultipartTransferService_PartUrl: {
            part: components["schemas"]["file_MultipartSessionService_RegisteredPart"];
            upload: components["schemas"]["storage_MultipartStorage_SignedPart"];
        };
        file_dto_FilePageResponse: {
            content: components["schemas"]["file_dto_FileResponse"][];
            /** Format: int32 */
            page: number;
            /** Format: int32 */
            size: number;
            /** Format: int64 */
            totalElements: number | bigint;
            /** Format: int32 */
            totalPages: number;
        };
        file_dto_FileResponse: {
            /** Format: uuid */
            id: string;
            name: string;
            folderId: string | null;
            contentType: string | null;
            /** Format: int64 */
            sizeBytes: number | bigint;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            updatedAt: string;
            /** @enum {string} */
            effectiveRole: "VIEWER" | "EDITOR" | "OWNER";
            allowedCapabilities: string[];
        };
        file_dto_MoveFileRequest: {
            folderId?: string | null;
        };
        file_dto_RenameFileRequest: {
            /** @description Nonblank, trimmed then limited to 255 Java UTF-16 units; case-sensitive, no path separators/controls. Rename preserves existing file extension. Raw padding is not part of the normalized length limit. */
            name: string;
        };
        folder_FolderCopyTransactions_Request: {
            destinationFolderId?: string | null;
            /** @description Nonblank, trimmed then limited to 255 Java UTF-16 units; case-sensitive, no path separators/controls. Rename preserves existing file extension. Raw padding is not part of the normalized length limit. */
            name: string;
        };
        folder_FolderCopyTransactions_Status: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            sourceFolderId: string;
            destinationFolderId: string | null;
            folderId: string | null;
            name: string;
            /** @enum {string} */
            state: "ACTIVE" | "BLOCKED" | "UNKNOWN" | "FAILED" | "CANCELLED" | "COMPLETED";
            /** Format: int32 */
            checkpoint: number;
            /** Format: int32 */
            totalMembers: number;
            /** Format: int64 */
            completedFiles: number | bigint;
            /** Format: int64 */
            totalFiles: number | bigint;
            /** Format: int64 */
            revision: number | bigint;
            /** Format: date-time */
            createdAt: string;
            disposalOperationId: string | null;
        };
        folder_dto_CreateFolderRequest: {
            /** @description Nonblank, trimmed then limited to 255 Java UTF-16 units; case-sensitive, no path separators/controls. Rename preserves existing file extension. Raw padding is not part of the normalized length limit. */
            name: string;
            parentFolderId?: string | null;
        };
        folder_dto_CreateFolderResponse: {
            folderId: string | null;
        };
        folder_dto_FolderPageResponse: {
            content: components["schemas"]["folder_dto_FolderResponse"][];
            /** Format: int32 */
            page: number;
            /** Format: int32 */
            size: number;
            /** Format: int64 */
            totalElements: number | bigint;
            /** Format: int32 */
            totalPages: number;
        };
        folder_dto_FolderResponse: {
            /** Format: uuid */
            id: string;
            name: string;
            parentFolderId: string | null;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            updatedAt: string;
            /** @enum {string} */
            effectiveRole: "VIEWER" | "EDITOR" | "OWNER";
            allowedCapabilities: string[];
        };
        folder_dto_MoveFolderRequest: {
            destinationFolderId?: string | null;
        };
        folder_dto_RenameFolderRequest: {
            /** @description Nonblank, trimmed then limited to 255 Java UTF-16 units; case-sensitive, no path separators/controls. Rename preserves existing file extension. Raw padding is not part of the normalized length limit. */
            name: string;
        };
        folder_dto_RestoreFolderResponse: {
            folderId: string | null;
            restoredName: string;
            parentFolderId: string | null;
        };
        folder_dto_TrashPageResponse: {
            content: components["schemas"]["folder_dto_TrashPageResponse_Entry"][];
            /** Format: int32 */
            page: number;
            /** Format: int32 */
            size: number;
            /** Format: int64 */
            totalElements: number | bigint;
            /** Format: int32 */
            totalPages: number;
        };
        folder_dto_TrashPageResponse_Entry: {
            /** Format: uuid */
            id: string;
            /** @enum {string} */
            resourceType: "FILE" | "FOLDER";
            name: string;
            originalParentId: string | null;
            /** Format: date-time */
            deletedAt: string;
            purgeRequestedAt: string | null;
            /** @enum {string} */
            lifecycleState: "TRASHED" | "PENDING_PURGE";
            allowedCapabilities: string[];
        };
        search_SearchService_Entry: {
            /** @enum {string} */
            resourceType: "FILE" | "FOLDER";
            resource: components["schemas"]["file_dto_FileResponse"] | components["schemas"]["folder_dto_FolderResponse"];
        };
        search_SearchService_SearchPage: {
            content: components["schemas"]["search_SearchService_Entry"][];
            /** Format: int32 */
            page: number;
            /** Format: int32 */
            size: number;
            /** Format: int64 */
            totalElements: number | bigint;
            /** Format: int32 */
            totalPages: number;
        };
        sharing_GrantService_Grant: {
            /** Format: uuid */
            id: string;
            fileId: string | null;
            folderId: string | null;
            /** Format: uuid */
            granteeUserId: string;
            email: string;
            /** @enum {string} */
            role: "VIEWER" | "EDITOR" | "OWNER";
            /** Format: int64 */
            version: number | bigint;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            updatedAt: string;
        };
        sharing_GrantService_GrantList: {
            direct: components["schemas"]["sharing_GrantService_Grant"][];
            inherited: components["schemas"]["sharing_GrantService_Grant"][];
        };
        sharing_PermissionActivityService_Activity: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            actorId: string;
            actorDisplayName: string;
            /** Format: uuid */
            ownerId: string;
            /** @enum {string} */
            resourceType: "FILE" | "FOLDER";
            /** Format: uuid */
            resourceId: string;
            resourceName: string;
            /** @enum {string} */
            eventType: "GRANT_CREATED" | "GRANT_ROLE_CHANGED" | "GRANT_REVOKED" | "LINK_CREATED" | "LINK_REVOKED";
            /** Format: uuid */
            subjectId: string;
            /** Format: int64 */
            subjectVersion: number | bigint;
            recipientId: string | null;
            recipientEmail: string | null;
            oldRole: ("VIEWER" | "EDITOR" | "OWNER") | null;
            newRole: ("VIEWER" | "EDITOR" | "OWNER") | null;
            linkExpiresAt: string | null;
            /** Format: date-time */
            occurredAt: string;
        };
        sharing_PermissionActivityService_ActivityPage: {
            content: components["schemas"]["sharing_PermissionActivityService_Activity"][];
            /** Format: int32 */
            page: number;
            /** Format: int32 */
            size: number;
            /** Format: int64 */
            totalElements: number | bigint;
            /** Format: int64 */
            totalPages: number | bigint;
        };
        sharing_ResourceReadService_SharedEntry: {
            /** @enum {string} */
            resourceType: "FILE" | "FOLDER";
            resource: components["schemas"]["file_dto_FileResponse"] | components["schemas"]["folder_dto_FolderResponse"];
        };
        sharing_ResourceReadService_SharedPage: {
            content: components["schemas"]["sharing_ResourceReadService_SharedEntry"][];
            /** Format: int32 */
            page: number;
            /** Format: int32 */
            size: number;
            /** Format: int64 */
            totalElements: number | bigint;
            /** Format: int32 */
            totalPages: number;
        };
        sharing_ShareLinkController_CreateLink: {
            expiresAt?: string | null;
        };
        sharing_ShareLinkService_CreatedLink: {
            link: components["schemas"]["sharing_ShareLinkService_Link"];
            secret: string;
        };
        sharing_ShareLinkService_Link: {
            /** Format: uuid */
            id: string;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            expiresAt: string;
            revokedAt: string | null;
            /** Format: int64 */
            version: number | bigint;
        };
        sharing_ShareLinkService_PublicFile: {
            name: string;
            contentType: string | null;
            /** Format: int64 */
            sizeBytes: number | bigint;
        };
        sharing_SharingController_CreateGrant: {
            email: string;
            /** @enum {string} */
            role: "VIEWER" | "EDITOR";
        };
        sharing_SharingController_UpdateGrant: {
            /** @enum {string} */
            role: "VIEWER" | "EDITOR";
        };
        sharing_SharingNotificationService_Notification: {
            /** Format: uuid */
            id: string;
            /** Format: uuid */
            sourceEventId: string;
            /** @enum {string} */
            eventType: "GRANT_CREATED" | "GRANT_ROLE_CHANGED" | "GRANT_REVOKED" | "STORAGE_QUOTA_THRESHOLD_CROSSED";
            /** Format: date-time */
            createdAt: string;
            readAt: string | null;
            actionable: boolean;
            resourceType: ("FILE" | "FOLDER") | null;
            resourceId: string | null;
            resourceName: string | null;
            newRole: ("VIEWER" | "EDITOR" | "OWNER") | null;
            quota: components["schemas"]["sharing_SharingNotificationService_QuotaMetadata"] | null;
        };
        sharing_SharingNotificationService_Page: {
            content: components["schemas"]["sharing_SharingNotificationService_Notification"][];
            /** Format: int32 */
            page: number;
            /** Format: int32 */
            size: number;
            /** Format: int64 */
            totalElements: number | bigint;
            /** Format: int64 */
            totalPages: number | bigint;
        };
        sharing_SharingNotificationService_QuotaMetadata: {
            /** @enum {integer} */
            threshold: 80 | 90 | 100;
            /** Format: int64 */
            logicalUsageBytes: number | bigint;
            /** Format: int64 */
            limitBytes: number | bigint;
            /** Format: date-time */
            occurredAt: string;
        };
        sharing_SharingNotificationService_UnreadCount: {
            /** Format: int64 */
            unreadCount: number | bigint;
        };
        storage_MultipartStorage_Page: {
            parts: components["schemas"]["storage_MultipartStorage_Part"][];
            nextMarker: number | null;
        };
        storage_MultipartStorage_Part: {
            /** Format: int32 */
            partNumber: number;
            /** Format: int64 */
            size: number | bigint;
            etag: string;
            sha256: string;
        };
        storage_MultipartStorage_SignedPart: {
            url: string;
            requiredHeaders: {
                [key: string]: string[];
            };
            /** Format: date-time */
            expiresAt: string;
        };
        storage_StorageUsageService_Usage: {
            /** Format: int64 */
            limitBytes: number | bigint;
            /** Format: int64 */
            usedBytes: number | bigint;
            /** Format: int64 */
            reservedBytes: number | bigint;
            /** Format: int64 */
            availableBytes: number | bigint;
            /** Format: int64 */
            uncertainStorageBytes: number | bigint;
            /** Format: int64 */
            uncertainStorageAllocations: number | bigint;
            /** Format: int64 */
            unknownSizeAllocations: number | bigint;
        };
        user_ProfileService_Profile: {
            /** Format: uuid */
            userId: string;
            email: string;
            displayName: string;
            /** Format: date-time */
            createdAt: string;
            /** Format: date-time */
            updatedAt: string;
        };
        user_UserController_UpdateProfile: {
            displayName: string;
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    ResourceActivity_list: {
        parameters: {
            query?: {
                resourceType?: "FILE" | "FOLDER";
                resourceId?: string;
                eventType?: "UPLOAD_COMPLETED" | "COPIED" | "FOLDER_CREATED" | "RENAMED" | "MOVED" | "TRASHED" | "RESTORED" | "PERMANENTLY_DELETED";
                actorId?: string;
                from?: string;
                to?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["activity_ResourceActivityService_ActivityPage"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Auth_login: {
        parameters: {
            query?: never;
            header: {
                "X-VaultDrive-Client": "1";
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["auth_dto_LoginRequest"];
            };
        };
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Secure HttpOnly SameSite=Strict Path=/ host-only refresh cookie; cleared only on successful logout */
                    "Set-Cookie"?: string;
                    "X-VaultDrive-CSRF"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["auth_dto_LoginResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Auth_logout: {
        parameters: {
            query?: never;
            header: {
                "X-VaultDrive-CSRF": string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            204: {
                headers: {
                    /** @description Secure HttpOnly SameSite=Strict Path=/ host-only refresh cookie; cleared only on successful logout */
                    "Set-Cookie"?: string;
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Auth_refresh: {
        parameters: {
            query?: never;
            header: {
                "X-VaultDrive-CSRF": string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Secure HttpOnly SameSite=Strict Path=/ host-only refresh cookie; cleared only on successful logout */
                    "Set-Cookie"?: string;
                    "X-VaultDrive-CSRF"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["auth_dto_LoginResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Auth_register: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["auth_dto_RegisterRequest"];
            };
        };
        responses: {
            /** @description Success */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["auth_dto_RegisterResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    FileCopy_get: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_FileCopyTransactions_Status"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    FileCopy_cancel: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_FileCopyTransactions_Status"];
                };
            };
            /** @description Accepted or nonterminal retained state; inspect body and follow the recovery protocol */
            202: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_FileCopyTransactions_Status"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    FileCopy_execute: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_FileCopyTransactions_Status"];
                };
            };
            /** @description Accepted or nonterminal retained state; inspect body and follow the recovery protocol */
            202: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_FileCopyTransactions_Status"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    FileCopy_recover: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_FileCopyTransactions_Status"];
                };
            };
            /** @description Accepted or nonterminal retained state; inspect body and follow the recovery protocol */
            202: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_FileCopyTransactions_Status"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    DeletionOperation_get: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                operationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["deletion_DeletionOperationService_Status"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    EmptyFile_get: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_EmptyFileTransactions_Status"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    EmptyFile_recover: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_EmptyFileTransactions_Status"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    File_listFiles: {
        parameters: {
            query?: {
                folderId?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_dto_FilePageResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    EmptyFile_create: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["file_EmptyFileTransactions_Request"];
            };
        };
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_EmptyFileTransactions_Status"];
                };
            };
            /** @description Accepted or nonterminal retained state; inspect body and follow the recovery protocol */
            202: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_EmptyFileTransactions_Status"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    File_listTrash: {
        parameters: {
            query?: {
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_dto_TrashPageResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    File_getFile: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                fileId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_dto_FileResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    File_trashFile: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                fileId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    File_downloadFile: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                fileId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Attachment stream; authenticated MIME uses declaration/fallback, public links always octet-stream. Interrupted streams may truncate after headers; no JSON envelope is possible after commit. */
            200: {
                headers: {
                    /** @description attachment; encoded UTF-8 filename */
                    "Content-Disposition"?: string;
                    "Content-Length"?: number | bigint;
                    [name: string]: unknown;
                };
                content: {
                    "*/*": string;
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    File_moveFile: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                fileId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["file_dto_MoveFileRequest"];
            };
        };
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_dto_FileResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    File_renameFile: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                fileId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["file_dto_RenameFileRequest"];
            };
        };
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_dto_FileResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    File_permanentlyDeleteFile: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                fileId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Accepted or nonterminal retained state; inspect body and follow the recovery protocol */
            202: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["deletion_DeletionOperationController_Accepted"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    File_restoreFile: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                fileId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_dto_FileResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    ShareLink_list: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                fileId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["sharing_ShareLinkService_Link"][];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    ShareLink_create: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                fileId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["sharing_ShareLinkController_CreateLink"];
            };
        };
        responses: {
            /** @description Success */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["sharing_ShareLinkService_CreatedLink"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    ShareLink_revoke: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                fileId: string;
                linkId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Sharing_list_file: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["sharing_GrantService_GrantList"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Sharing_create_file: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["sharing_SharingController_CreateGrant"];
            };
        };
        responses: {
            /** @description Success */
            201: {
                headers: {
                    /** @description Quoted grant version */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["sharing_GrantService_Grant"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Sharing_revoke_file: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
                grantId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Sharing_update_file: {
        parameters: {
            query?: never;
            header: {
                "If-Match": string;
            };
            path: {
                id: string;
                grantId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["sharing_SharingController_UpdateGrant"];
            };
        };
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Quoted grant version */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["sharing_GrantService_Grant"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Stale grant If-Match version */
            412: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Grant update requires If-Match */
            428: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    FileCopy_copy: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                sourceId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["file_FileCopyTransactions_Request"];
            };
        };
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_FileCopyTransactions_Status"];
                };
            };
            /** @description Accepted or nonterminal retained state; inspect body and follow the recovery protocol */
            202: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_FileCopyTransactions_Status"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    FolderCopy_get: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_FolderCopyTransactions_Status"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    FolderCopy_cancel: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_FolderCopyTransactions_Status"];
                };
            };
            /** @description Accepted or nonterminal retained state; inspect body and follow the recovery protocol */
            202: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_FolderCopyTransactions_Status"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    FolderCopy_discard: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_FolderCopyTransactions_Status"];
                };
            };
            /** @description Accepted or nonterminal retained state; inspect body and follow the recovery protocol */
            202: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_FolderCopyTransactions_Status"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    FolderCopy_execute: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_FolderCopyTransactions_Status"];
                };
            };
            /** @description Accepted or nonterminal retained state; inspect body and follow the recovery protocol */
            202: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_FolderCopyTransactions_Status"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    FolderCopy_recover: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_FolderCopyTransactions_Status"];
                };
            };
            /** @description Accepted or nonterminal retained state; inspect body and follow the recovery protocol */
            202: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_FolderCopyTransactions_Status"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Folder_listRootFolders: {
        parameters: {
            query?: {
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_dto_FolderPageResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Folder_createFolder: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["folder_dto_CreateFolderRequest"];
            };
        };
        responses: {
            /** @description Success */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_dto_CreateFolderResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Folder_getFolder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                folderId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_dto_FolderResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Folder_deleteFolder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                folderId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Folder_renameFolder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                folderId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["folder_dto_RenameFolderRequest"];
            };
        };
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_dto_FolderResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Folder_listChildFolders: {
        parameters: {
            query?: {
                page?: number;
                size?: number;
            };
            header?: never;
            path: {
                folderId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_dto_FolderPageResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Folder_moveFolder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                folderId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["folder_dto_MoveFolderRequest"];
            };
        };
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_dto_FolderResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Sharing_list_folder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["sharing_GrantService_GrantList"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Sharing_create_folder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["sharing_SharingController_CreateGrant"];
            };
        };
        responses: {
            /** @description Success */
            201: {
                headers: {
                    /** @description Quoted grant version */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["sharing_GrantService_Grant"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Sharing_revoke_folder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
                grantId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Sharing_update_folder: {
        parameters: {
            query?: never;
            header: {
                "If-Match": string;
            };
            path: {
                id: string;
                grantId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["sharing_SharingController_UpdateGrant"];
            };
        };
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Quoted grant version */
                    ETag?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["sharing_GrantService_Grant"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Stale grant If-Match version */
            412: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Grant update requires If-Match */
            428: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    FolderCopy_copy: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path: {
                sourceId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["folder_FolderCopyTransactions_Request"];
            };
        };
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_FolderCopyTransactions_Status"];
                };
            };
            /** @description Accepted or nonterminal retained state; inspect body and follow the recovery protocol */
            202: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_FolderCopyTransactions_Status"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Health_health: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_health_HealthResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Health_ready: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_health_HealthResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Readiness DOWN */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_health_HealthResponse"];
                };
            };
        };
    };
    SharingNotification_list: {
        parameters: {
            query?: {
                unread?: boolean;
                eventType?: "GRANT_CREATED" | "GRANT_ROLE_CHANGED" | "GRANT_REVOKED" | "STORAGE_QUOTA_THRESHOLD_CROSSED";
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["sharing_SharingNotificationService_Page"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    SharingNotification_unreadCount: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["sharing_SharingNotificationService_UnreadCount"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    SharingNotification_markRead: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            204: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    PermissionActivity_list: {
        parameters: {
            query?: {
                resourceType?: "FILE" | "FOLDER";
                resourceId?: string;
                eventType?: "GRANT_CREATED" | "GRANT_ROLE_CHANGED" | "GRANT_REVOKED" | "LINK_CREATED" | "LINK_REVOKED";
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["sharing_PermissionActivityService_ActivityPage"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    PublicShareLink_metadata: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                linkId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["sharing_ShareLinkService_PublicFile"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    PublicShareLink_download: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                linkId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Attachment stream; authenticated MIME uses declaration/fallback, public links always octet-stream. Interrupted streams may truncate after headers; no JSON envelope is possible after commit. */
            200: {
                headers: {
                    /** @description attachment; encoded UTF-8 filename */
                    "Content-Disposition"?: string;
                    "Content-Length"?: number | bigint;
                    [name: string]: unknown;
                };
                content: {
                    "application/octet-stream": string;
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            502: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Search_search: {
        parameters: {
            query: {
                q: string;
                resourceType?: "FILE" | "FOLDER";
                scope?: "ALL" | "OWNED" | "SHARED";
                mimeType?: string;
                minSizeBytes?: number | bigint;
                maxSizeBytes?: number | bigint;
                createdFrom?: string;
                createdTo?: string;
                modifiedFrom?: string;
                modifiedTo?: string;
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["search_SearchService_SearchPage"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Sharing_shared: {
        parameters: {
            query?: {
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["sharing_ResourceReadService_SharedPage"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Trash_listTrashedFolders: {
        parameters: {
            query?: {
                page?: number;
                size?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_dto_TrashPageResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Trash_permanentlyDeleteFolder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                folderId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Accepted or nonterminal retained state; inspect body and follow the recovery protocol */
            202: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    /** @description 2 on nonterminal states; deletion FAILED omits the hint */
                    "Retry-After"?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["deletion_DeletionOperationController_Accepted"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    Trash_restoreFolder: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                folderId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["folder_dto_RestoreFolderResponse"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    MultipartSession_reserve: {
        parameters: {
            query?: never;
            header: {
                "Idempotency-Key": string;
            };
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["file_MultipartSessionService_Request"];
            };
        };
        responses: {
            /** @description Success */
            201: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_MultipartSessionService_Session"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    MultipartSession_get: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_MultipartSessionService_Session"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    MultipartSession_cancel: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_MultipartCancellationTransactions_Cancellation"];
                };
            };
            /** @description Accepted or nonterminal retained state; inspect body and follow the recovery protocol */
            202: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_MultipartCancellationTransactions_Cancellation"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    MultipartSession_recoverCancellation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_MultipartCancellationTransactions_Cancellation"];
                };
            };
            /** @description Accepted or nonterminal retained state; inspect body and follow the recovery protocol */
            202: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_MultipartCancellationTransactions_Cancellation"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    MultipartSession_cancellation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_MultipartCancellationTransactions_Cancellation"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    MultipartSession_complete: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_MultipartSessionService_Session"];
                };
            };
            /** @description Accepted or nonterminal retained state; inspect body and follow the recovery protocol */
            202: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_MultipartSessionService_Session"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    MultipartSession_initiate: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_MultipartSessionService_Session"];
                };
            };
            /** @description Accepted or nonterminal retained state; inspect body and follow the recovery protocol */
            202: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_MultipartSessionService_Session"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    MultipartSession_parts: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["file_MultipartSessionController_PartRequest"];
            };
        };
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_MultipartTransferService_PartUrl"][];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    MultipartSession_list: {
        parameters: {
            query?: {
                marker?: number;
                limit?: number;
            };
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["storage_MultipartStorage_Page"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    MultipartSession_recover: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_MultipartSessionService_Session"];
                };
            };
            /** @description Accepted or nonterminal retained state; inspect body and follow the recovery protocol */
            202: {
                headers: {
                    /** @description Relative retained status URI */
                    Location?: string;
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["file_MultipartSessionService_Session"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    User_getCurrentUser: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["user_ProfileService_Profile"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    User_updateCurrentUser: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["user_UserController_UpdateProfile"];
            };
        };
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["user_ProfileService_Profile"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
    StorageUsage_get: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description Success */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["storage_StorageUsageService_Usage"];
                };
            };
            /** @description Malformed or invalid request; sanitized message or sorted field validation messages; do not parse message as a code */
            400: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Authentication required or invalid credentials/session */
            401: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Visible resource but insufficient permission, or failed auth transport/CSRF admission */
            403: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unknown, foreign or inaccessible resource; public capability failures use Share link not found */
            404: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            405: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            406: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Namespace/quota/idempotency/lifecycle/immutable-evidence conflict; read current state */
            409: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            415: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Bounded process admission rejection, no business work; back off at least one second and retain idempotency key */
            429: {
                headers: {
                    "Retry-After"?: "1";
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Sanitized common API error; see status and error, not message parsing */
            500: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
            /** @description Unavailable/bounded timeout. Mutation outcome may be uncertain; follow operation recovery, never blindly retry refresh/storage controls. Retry-After: 2 only for bounded database timeout handler. */
            503: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["common_exception_ApiError"];
                };
            };
        };
    };
}
