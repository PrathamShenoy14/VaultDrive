# MVP endpoint inventory

Generated from Spring MVC mappings and record DTOs by `ApiContractFreezeIntegrationTest`.
Every path below is relative to the same HTTPS application origin. Request/response
schema names refer to [OpenAPI components](openapi.json); `—` means no body.
Security and authorization descriptions are per operation in that artifact.
Common errors: `{status,error,message}`; detailed error/status/retry semantics and
operation states are in [the frontend guide](frontend-integration.md).
Implicit Spring HEAD/OPTIONS behavior is not a separate supported operation.

| Method | Path | Authentication | Request schema | Success: response schema |
|---|---|---|---|---|
| GET | `/api/v1/activity` | Bearer | — | 200: `activity_ResourceActivityService_ActivityPage` |
| POST | `/api/v1/auth/login` | Public | `auth_dto_LoginRequest` | 200: `auth_dto_LoginResponse` |
| POST | `/api/v1/auth/logout` | Bearer + cookie + CSRF | — | 204: — |
| POST | `/api/v1/auth/refresh` | Cookie + CSRF | — | 200: `auth_dto_LoginResponse` |
| POST | `/api/v1/auth/register` | Public | `auth_dto_RegisterRequest` | 201: `auth_dto_RegisterResponse` |
| GET | `/api/v1/copy-operations/{id}` | Bearer | — | 200: `file_FileCopyTransactions_Status` |
| POST | `/api/v1/copy-operations/{id}/cancel` | Bearer | — | 200: `file_FileCopyTransactions_Status`; 202: `file_FileCopyTransactions_Status` |
| POST | `/api/v1/copy-operations/{id}/execute` | Bearer | — | 200: `file_FileCopyTransactions_Status`; 202: `file_FileCopyTransactions_Status` |
| POST | `/api/v1/copy-operations/{id}/recover` | Bearer | — | 200: `file_FileCopyTransactions_Status`; 202: `file_FileCopyTransactions_Status` |
| GET | `/api/v1/deletion-operations/{operationId}` | Bearer | — | 200: `deletion_DeletionOperationService_Status` |
| GET | `/api/v1/empty-file-creations/{id}` | Bearer | — | 200: `file_EmptyFileTransactions_Status` |
| POST | `/api/v1/empty-file-creations/{id}/recover` | Bearer | — | 200: `file_EmptyFileTransactions_Status` |
| GET | `/api/v1/files` | Bearer | — | 200: `file_dto_FilePageResponse` |
| POST | `/api/v1/files/empty` | Bearer | `file_EmptyFileTransactions_Request` | 200: `file_EmptyFileTransactions_Status`; 202: `file_EmptyFileTransactions_Status` |
| GET | `/api/v1/files/trash` | Bearer | — | 200: `folder_dto_TrashPageResponse` |
| DELETE | `/api/v1/files/{fileId}` | Bearer | — | 204: — |
| GET | `/api/v1/files/{fileId}` | Bearer | — | 200: `file_dto_FileResponse` |
| GET | `/api/v1/files/{fileId}/download` | Bearer | — | 200: binary attachment |
| PATCH | `/api/v1/files/{fileId}/move` | Bearer | `file_dto_MoveFileRequest` | 200: `file_dto_FileResponse` |
| PATCH | `/api/v1/files/{fileId}/name` | Bearer | `file_dto_RenameFileRequest` | 200: `file_dto_FileResponse` |
| DELETE | `/api/v1/files/{fileId}/permanent` | Bearer | — | 202: `deletion_DeletionOperationController_Accepted` |
| POST | `/api/v1/files/{fileId}/restore` | Bearer | — | 200: `file_dto_FileResponse` |
| GET | `/api/v1/files/{fileId}/share-links` | Bearer | — | 200: array of `sharing_ShareLinkService_Link` |
| POST | `/api/v1/files/{fileId}/share-links` | Bearer | `sharing_ShareLinkController_CreateLink` | 201: `sharing_ShareLinkService_CreatedLink` |
| DELETE | `/api/v1/files/{fileId}/share-links/{linkId}` | Bearer | — | 204: — |
| GET | `/api/v1/files/{id}/grants` | Bearer | — | 200: `sharing_GrantService_GrantList` |
| POST | `/api/v1/files/{id}/grants` | Bearer | `sharing_SharingController_CreateGrant` | 201: `sharing_GrantService_Grant` |
| DELETE | `/api/v1/files/{id}/grants/{grantId}` | Bearer | — | 204: — |
| PATCH | `/api/v1/files/{id}/grants/{grantId}` | Bearer | `sharing_SharingController_UpdateGrant` | 200: `sharing_GrantService_Grant` |
| POST | `/api/v1/files/{sourceId}/copy` | Bearer | `file_FileCopyTransactions_Request` | 200: `file_FileCopyTransactions_Status`; 202: `file_FileCopyTransactions_Status` |
| GET | `/api/v1/folder-copy-operations/{id}` | Bearer | — | 200: `folder_FolderCopyTransactions_Status` |
| POST | `/api/v1/folder-copy-operations/{id}/cancel` | Bearer | — | 200: `folder_FolderCopyTransactions_Status`; 202: `folder_FolderCopyTransactions_Status` |
| POST | `/api/v1/folder-copy-operations/{id}/discard` | Bearer | — | 200: `folder_FolderCopyTransactions_Status`; 202: `folder_FolderCopyTransactions_Status` |
| POST | `/api/v1/folder-copy-operations/{id}/execute` | Bearer | — | 200: `folder_FolderCopyTransactions_Status`; 202: `folder_FolderCopyTransactions_Status` |
| POST | `/api/v1/folder-copy-operations/{id}/recover` | Bearer | — | 200: `folder_FolderCopyTransactions_Status`; 202: `folder_FolderCopyTransactions_Status` |
| GET | `/api/v1/folders` | Bearer | — | 200: `folder_dto_FolderPageResponse` |
| POST | `/api/v1/folders` | Bearer | `folder_dto_CreateFolderRequest` | 201: `folder_dto_CreateFolderResponse` |
| DELETE | `/api/v1/folders/{folderId}` | Bearer | — | 204: — |
| GET | `/api/v1/folders/{folderId}` | Bearer | — | 200: `folder_dto_FolderResponse` |
| PATCH | `/api/v1/folders/{folderId}` | Bearer | `folder_dto_RenameFolderRequest` | 200: `folder_dto_FolderResponse` |
| GET | `/api/v1/folders/{folderId}/children` | Bearer | — | 200: `folder_dto_FolderPageResponse` |
| PATCH | `/api/v1/folders/{folderId}/move` | Bearer | `folder_dto_MoveFolderRequest` | 200: `folder_dto_FolderResponse` |
| GET | `/api/v1/folders/{id}/grants` | Bearer | — | 200: `sharing_GrantService_GrantList` |
| POST | `/api/v1/folders/{id}/grants` | Bearer | `sharing_SharingController_CreateGrant` | 201: `sharing_GrantService_Grant` |
| DELETE | `/api/v1/folders/{id}/grants/{grantId}` | Bearer | — | 204: — |
| PATCH | `/api/v1/folders/{id}/grants/{grantId}` | Bearer | `sharing_SharingController_UpdateGrant` | 200: `sharing_GrantService_Grant` |
| POST | `/api/v1/folders/{sourceId}/copy` | Bearer | `folder_FolderCopyTransactions_Request` | 200: `folder_FolderCopyTransactions_Status`; 202: `folder_FolderCopyTransactions_Status` |
| GET | `/api/v1/health` | Public | — | 200: `common_health_HealthResponse` |
| GET | `/api/v1/health/ready` | Public | — | 200: `common_health_HealthResponse` |
| GET | `/api/v1/notifications` | Bearer | — | 200: `sharing_SharingNotificationService_Page` |
| GET | `/api/v1/notifications/unread-count` | Bearer | — | 200: `sharing_SharingNotificationService_UnreadCount` |
| PATCH | `/api/v1/notifications/{id}/read` | Bearer | — | 204: — |
| GET | `/api/v1/permission-activity` | Bearer | — | 200: `sharing_PermissionActivityService_ActivityPage` |
| GET | `/api/v1/public/share-links/{linkId}` | Share token | — | 200: `sharing_ShareLinkService_PublicFile` |
| GET | `/api/v1/public/share-links/{linkId}/download` | Share token | — | 200: binary attachment |
| GET | `/api/v1/search` | Bearer | — | 200: `search_SearchService_SearchPage` |
| GET | `/api/v1/shared-with-me` | Bearer | — | 200: `sharing_ResourceReadService_SharedPage` |
| GET | `/api/v1/trash/folders` | Bearer | — | 200: `folder_dto_TrashPageResponse` |
| DELETE | `/api/v1/trash/folders/{folderId}/permanent` | Bearer | — | 202: `deletion_DeletionOperationController_Accepted` |
| POST | `/api/v1/trash/folders/{folderId}/restore` | Bearer | — | 200: `folder_dto_RestoreFolderResponse` |
| POST | `/api/v1/upload-sessions` | Bearer | `file_MultipartSessionService_Request` | 201: `file_MultipartSessionService_Session` |
| GET | `/api/v1/upload-sessions/{id}` | Bearer | — | 200: `file_MultipartSessionService_Session` |
| POST | `/api/v1/upload-sessions/{id}/cancel` | Bearer | — | 200: `file_MultipartCancellationTransactions_Cancellation`; 202: `file_MultipartCancellationTransactions_Cancellation` |
| POST | `/api/v1/upload-sessions/{id}/cancel/recover` | Bearer | — | 200: `file_MultipartCancellationTransactions_Cancellation`; 202: `file_MultipartCancellationTransactions_Cancellation` |
| GET | `/api/v1/upload-sessions/{id}/cancellation` | Bearer | — | 200: `file_MultipartCancellationTransactions_Cancellation` |
| POST | `/api/v1/upload-sessions/{id}/complete` | Bearer | — | 200: `file_MultipartSessionService_Session`; 202: `file_MultipartSessionService_Session` |
| POST | `/api/v1/upload-sessions/{id}/initiate` | Bearer | — | 200: `file_MultipartSessionService_Session`; 202: `file_MultipartSessionService_Session` |
| POST | `/api/v1/upload-sessions/{id}/part-urls` | Bearer | `file_MultipartSessionController_PartRequest` | 200: array of `file_MultipartTransferService_PartUrl` |
| GET | `/api/v1/upload-sessions/{id}/parts` | Bearer | — | 200: `storage_MultipartStorage_Page` |
| POST | `/api/v1/upload-sessions/{id}/recover` | Bearer | — | 200: `file_MultipartSessionService_Session`; 202: `file_MultipartSessionService_Session` |
| GET | `/api/v1/users/me` | Bearer | — | 200: `user_ProfileService_Profile` |
| PATCH | `/api/v1/users/me` | Bearer | `user_UserController_UpdateProfile` | 200: `user_ProfileService_Profile` |
| GET | `/api/v1/users/me/storage` | Bearer | — | 200: `storage_StorageUsageService_Usage` |
