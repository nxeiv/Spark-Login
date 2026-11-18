<div align="center">

# SparkLogin

**A graphical authentication plugin for Paper servers.**

Cottage-maintained compatibility build based on upstream SparkLogin 3.0.0.

</div>

---

## Compatibility

| Component | Requirement |
|---|---|
| Minecraft server | Paper 26.3+ |
| Java runtime / build JDK | Java 25 |
| UI | Paper Dialog API |
| Database | SQLite (embedded) |

This fork targets Paper's 26.3 API generation. It is not intended to load on older 1.21.x servers.

## Features

- Graphical login and registration dialogs.
- Pre-join authentication through Paper's connection configuration API.
- Session-based auto-login with optional IP-change validation.
- BCrypt password hashing and automatic upgrade of supported legacy AuthMe hashes.
- Configurable login attempts, delay, password length, and account-per-IP limits.
- AuthMe account migration with a database backup.
- Configurable dialog text using Adventure MiniMessage.
- SQLite storage with HikariCP connection pooling.

## Commands

**Players**
```
/changepassword <old> <new> <confirm>
/accounts
```

**Administrators** (`sparklogin.admin`)
```
/sparklogin reload
/sparklogin info <player>
/sparklogin resetpassword <player>
/sparklogin unregister <player>
/sparklogin migrate authme
```

## Configuration

Configuration is generated in `plugins/SparkLogin/config.yml`. Dialog text is stored in `plugins/SparkLogin/lang/en_US.yml`.

Important settings:

```yaml
security:
  bcrypt-cost-factor: 12
  max-login-attempts: 3
  brute-force-delay-ms: 1500
  require-login-on-ip-change: true

registration:
  min-password-length: 6
  max-password-length: 64
  max-accounts-per-ip: 3
  kick-on-limit: true

session:
  enabled: true
  duration-hours: 12
  persistent: true
```

## Build

The project uses Maven. Build with JDK 25:

```bash
mvn --batch-mode clean verify
```

The shaded plugin is written to `target/SparkLogin-3.1.0.jar`.

## Upgrade notes

1. Stop the server before replacing the plugin.
2. Back up the entire `plugins/SparkLogin/` directory, including `database.db` and configuration files.
3. Replace the old JAR with `SparkLogin-3.1.0.jar`.
4. Start the server and check the startup log for successful database initialization.
5. Test registration, login, session restore, password change, and admin account management before inviting players back.

Do not delete the backup until the new build has been checked on the live server.

## Credits

Original project: [PavitraXD/Spark-Login](https://github.com/pavitraxd/Spark-Login). This fork keeps the upstream project identity and adds a Cottage-maintained Paper 26.3 compatibility build.
