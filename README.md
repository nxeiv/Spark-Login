# 🔐 SparkLogin v2.0.0

SparkLogin is a modern, lightweight authentication plugin for Paper 1.21.x, featuring **graphical dialog UI authentication** for a seamless login experience. Built for servers that want secure login protection without unnecessary bloat, it combines fast async storage, strong password security, session-based auto-login, and an intuitive pre-join dialog system.

## 🚀 Key Features

### 🎨 Dialog UI Authentication (Only in 1.21.11+)
- **Pre-join graphical dialogs** using Paper's Dialog API
- Beautiful, modern interface for registration and login
- No more chat-based commands - click buttons to authenticate
- Seamless integration with server join flow
- Full player lockdown until authentication completed

### 🔄 AuthMe Migration (in plugin version 2.0.0)
- **One-click migration** from AuthMeReloaded to SparkLogin
- Supports SHA256 and BCRYPT hash types
- Automatic SQLite and MySQL/MariaDB detection
- Preserves all player passwords - no resets required
- Auto-upgrades to BCrypt on first successful login
- Backup creation before migration
- Progress tracking with action bar display

### ⚡ Performance Optimizations
- **Account caching** with Caffeine for reduced database load
- Optimized HikariCP connection pooling for SMP servers
- SQLite performance tuning (WAL mode, memory-mapped I/O)
- Async database operations to prevent server lag
- Configurable cache TTL and size

### 🔒 Security Features
- BCrypt password hashing (configurable cost factor)
- Login attempt limits with cooldown protection
- Anti-bot join protection
- IP-based session validation
- Max accounts per IP enforcement
- Brute-force delay protection

### 🎮 Player Experience
- Session auto-login with IP + time checks
- Full pre-login restriction: movement, commands, chat, inventory, PvP, damage, interaction blocked
- MiniMessage messages, titles, actionbar, and sounds
- Teleport back after successful login
- PlaceholderAPI support
- In-game auth location setup

## 🛠 Player Commands

```text
/register <password> <confirmPassword>    - Register a new account
/login <password>                         - Login with your password
/changepassword <old> <new> <confirm>     - Change your password
/logout                                   - Log out and invalidate session
```

## 🛠 Admin Commands

```text
/ss reload                                 - Reload plugin configuration
/ss setauthlocation                       - Set authentication teleport location
/ss clearauthlocation                     - Clear authentication teleport location
/ss resetpassword <player>                - Reset a player's password
/sparklogin info <player>                         - View player account information
/ss suspicious <player>                   - Check player login history
/sparklogin migrate authme                        - Migrate accounts from AuthMe (in plugin version 2.0.0)
```

## 📦 Installation

1. Download the latest `SparkLogin-2.0.0.jar`
2. Place it in your server's `plugins/` folder
3. Restart your server
4. Configure `plugins/SparkLogin/config.yml` as needed
5. Enjoy modern dialog authentication!

## 🎯 Best For

- **SMP servers** looking for modern authentication
- **Cracked authentication servers** needing secure login
- **Public servers** with high player counts
- **Networks** requiring a clean, performant login system
- **Servers migrating from AuthMe** to a modern solution

## 📋 Requirements

- **Minecraft:** Paper 1.21.X
- **Java:** 21+
- **Dependencies:** None (shaded)

## ❤️ Support

If SparkLogin helps your server, consider leaving a rating or review.

## 📄 License

This plugin is developed by PavitraXD.

---

**Version 2.0.0** - Dialog UI Authentication & AuthMe Migration
