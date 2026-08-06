# 🔐 SparkLogin
**Modern graphical authentication for Paper servers.**  
No chat commands. No bloat. Just a clean dialog UI that gets players in fast and safe.

---

## ✨ Features

- **Dialog UI** — graphical login & register screens, no typing commands in chat
- **Smart version routing** — pre-join dialog on 1.21.11+, post-join dialog on 1.21.4–1.21.10
- **Full lockdown** — movement, chat, commands, inventory all blocked until authenticated
- **Session auto-login** — returning players skip the dialog if their session is still valid
- **BCrypt security** — configurable cost factor, brute-force delay, max attempt kicks
- **AuthMe migration** — one command imports all accounts, supports SHA256 & BCrypt hashes
- **Custom branding** — change the dialog title prefix to match your server
- **Async everything** — HikariCP + SQLite with WAL mode, zero server lag

---

## 🛠 Commands

**Players**
```
/login <password>
/register <password> <confirm>
/changepassword <old> <new> <confirm>
/logout
```

**Admins** `(sparklogin.admin)`
```
/sparklogin reload
/sparklogin info <player>
/sparklogin resetpassword <player>
/sparklogin unregister <player>
/sparklogin migrate authme
```

---

## ⚙️ Configuration Highlights

```yaml
branding:
  prefix: "SparkLogin"        # shown in all dialog titles

security:
  bcrypt-cost-factor: 12
  max-login-attempts: 3
  brute-force-delay-ms: 1500

session:
  enabled: true
  duration-hours: 12

registration:
  min-password-length: 6
  max-accounts-per-ip: 3
```

All dialog text is fully customizable in `lang/en_US.yml` using MiniMessage formatting.

---

## 📦 Installation

1. Drop `SparkLogin-3.0.0.jar` into your `plugins/` folder
2. Restart the server
3. Edit `plugins/SparkLogin/config.yml` and `lang/en_US.yml`
4. Done

---

## 📋 Requirements

| | |
|---|---|
| **Server** | Paper 1.21.4+ |
| **Java** | 21+ |
| **Dependencies** | None (all shaded) |

---

## 📄 License
Developed by **PavitraXD** — version `3.0.0`
