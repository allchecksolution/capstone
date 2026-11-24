# Administrator account operations

The developer/operator provisions one bootstrap administrator. Actual administrators always sign in
through the app's ID/password screen; they never need a terminal. Only the bootstrap administrator
can list, create, edit, and deactivate administrator accounts. Both administrator roles retain identical
driver/reception/operations business permissions. The bootstrap designation is independent of role.
Initial passwords do not require a change at first login.

## Build and database selection

Use Java 21 and run `./gradlew.bat bootJar` from `apps/backend`. The artifact is `build/libs/app.jar`.
The CLI does not start an HTTP server and does not require the backend server to be running.
It connects to PostgreSQL and runs configured Flyway migrations, so select the intended database first.
Plain Java execution does not automatically import `.env`.

PowerShell, for the repository's running local PostgreSQL:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local"
java -jar build/libs/app.jar provision-admin --login-id=admin --role=MASTER
```

The CLI also activates `admin-provisioning`. Explicitly select `local` or `prod`; do not rely on the
default local profile while another profile is active. For production supply `SPRING_DATASOURCE_URL`,
`SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` through the operator environment.

The password prompt accepts the new login password, without echo. Success reports only the account ID
and role. Deliver the ID and initial password to the actual administrator outside application logs.
No session is issued. A second initialization fails with `BOOTSTRAP_ADMIN_ALREADY_EXISTS`.
Independent simultaneous initialization commands are serialized in PostgreSQL and protected by a unique index.

## Existing databases

V22 adds the designation without automatically choosing or altering an existing administrator.
To select an existing active administrator explicitly:

```powershell
java -jar build/libs/app.jar adopt-bootstrap-admin --login-id=existing-admin
```

Enter that account's current password. This preserves its ID, credentials, and role. The command fails
if a bootstrap administrator already exists. Alternatively provision a new bootstrap account explicitly.
Existing unselected administrators retain business access but cannot manage administrator accounts.

## Administrator app

Set `API_BASE_URL` to the backend API root, for example `http://127.0.0.1:8080/api/v1` in local debug
(Android emulator: `http://10.0.2.2:8080/api/v1`), and use HTTPS for deployment. Example from
`apps/frontend/admin`:

```powershell
flutter run --dart-define=API_BASE_URL=http://127.0.0.1:8080/api/v1 --dart-define=ALLCHECK_DEV_ACCESS=false
```

Sign in with the provisioned credentials. The bootstrap administrator can open administrator account
management from Home or More. Additional accounts use the same login screen. No public signup is exposed.
Both account types can open driver registration reviews and use the existing paginated list, detail,
and approval/rejection APIs. Search/status filtering in this app screen applies to the current page.

| Method and path | Behavior |
|---|---|
| `POST /api/v1/auth/sessions/admin` | Login with `loginId` and `password`; returns tokens and current user |
| `GET /api/v1/auth/accounts/me` | Current user, including `isBootstrapAdmin` |
| `GET /api/v1/admin-accounts?page=1&size=20` | Bootstrap-only paginated account list |
| `POST /api/v1/admin-accounts` | Bootstrap-only create with `loginId` and `initialPassword`; new account is `SUB` and not bootstrap |
| `PUT /api/v1/admin-accounts/{id}` | Bootstrap-only update with `expectedVersion` and optional `loginId`, `newPassword`; at least one change required |
| `DELETE /api/v1/admin-accounts/{id}?expectedVersion=...` | Bootstrap-only deactivation; never physical deletion |
| `PUT /api/v1/admin-accounts/me/password` | Any administrator changes own password with `currentPassword`, `newPassword` |

Updates and deactivation use the version returned by the account list. Refresh after a version conflict.
Creation retries cannot duplicate a login ID; a duplicate returns 409 and never replaces an existing password.
Deactivate retains the account, login ID uniqueness, and historical business references.
ID/password changes and deactivation revoke all target sessions and push-device bindings atomically.
Own password changes also revoke all sessions, so the app returns to login. Passwords and IDs are not trimmed
or case-normalized. No role or bootstrap designation can be set through these requests.

The bootstrap account cannot be deactivated, deleted, demoted, or modified through the target-account API,
including by itself. It may change its own password. Database constraints/trigger additionally preserve the
designation, active state, and account row. There is no HTTP reactivation or public password-recovery endpoint.

## Bootstrap password recovery

An operator with executable and database access can reset the designated bootstrap administrator:

```powershell
java -jar build/libs/app.jar recover-bootstrap-admin --login-id=admin
```

Enter a new password. The command preserves the account ID, login ID, role, and designation, resets the hash,
and revokes all old sessions and push bindings in one transaction. The administrator then uses the usual
login screen. CLI recovery is operational authority, not an additional permission granted to app accounts.
It cannot recover a database outage or replace backups.

All three commands support `--password-file=<UTF-8 path>` when no interactive console is available.
The file contents are used exactly, including trailing newlines and spaces; use no BOM and no final newline.
Command-line password arguments are not supported. Use restricted temporary files and remove them after use.
For adoption the file contains the current password; for creation/recovery it contains the new password.

Account mutations append identifier-only action history in `identity_admin_history` in the same transaction.
CLI actions have a null actor ID to distinguish operator actions from authenticated app administrators.
No raw credentials or password hashes are included in history.

## Verification

Backend tests use `PostgresTestSchema` and migrate only UUID-named test schemas:

```powershell
./gradlew.bat ktlintCheck test --tests 'com.allcheck.identity.*'
```

Run `flutter test` from `apps/frontend/shared` for adapter and widget regression coverage.
