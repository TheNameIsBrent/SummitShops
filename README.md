# 🏪 OneBlock Advanced Chest Shops

A production-ready **Spigot/Paper 1.21** plugin providing island-based chest shops with multi-currency support, persistent holograms, and both YAML and MariaDB storage backends.

---

## ✨ Features

- **Island-locked shops** — Only members of the same SuperiorSkyblock2 island can use or edit a shop
- **Multi-currency** — Supports both Vault economy and EcoBits custom currencies simultaneously, configurable per-shop
- **Real chest stock** — The chest *is* the inventory; no hidden stock system
- **BUY & SELL modes** — Players buy items *from* the chest or sell items *into* it
- **Live holograms** — FancyHolograms displays item, price, mode, and live stock count above every shop; updates after every transaction
- **Chest GUI editor** — Fully click-driven editor (no commands needed) with chat-based price input
- **Dual storage** — Toggle between flat YAML (`shops.yml`) and MariaDB with HikariCP connection pooling
- **Admin commands** — Give shop items, edit any player's shops, teleport to shops, force-delete, and reload
- **Dupe-safe** — Atomic item+currency transfers; hopper/dropper access to shop chests is blocked; shift-click is cancelled in GUIs

---

## 📦 Dependencies

| Plugin | Required | Purpose |
|--------|----------|---------|
| [Paper 1.21](https://papermc.io) | ✅ | Server API |
| [SuperiorSkyblock2](https://github.com/BG-Software-LLC/SuperiorSkyblock2) | ✅ | Island membership & access control |
| [FancyHolograms](https://github.com/FancyMcPlugins/FancyHolograms) | ✅ | Hologram display |
| [eco](https://github.com/Auxilor/eco) | ✅ | EcoBits currency backend |
| [Vault](https://github.com/MilkBowl/Vault) | ⚠️ Soft | Fallback economy (required if using `VAULT` currencies) |

---

## 🚀 Building from Source

### Prerequisites

- Java 21+
- Maven 3.8+
- The four dependency JARs (SuperiorSkyblock2, FancyHolograms, eco, Vault)

### 1. Install dependency JARs to local Maven repo

```bash
mvn install:install-file \
    -Dfile=FancyHolograms-2_8_0.jar \
    -DgroupId=de.oliver \
    -DartifactId=FancyHolograms \
    -Dversion=2.8.0 \
    -Dpackaging=jar

mvn install:install-file \
    -Dfile=SuperiorSkyblock2-2025_2_1.jar \
    -DgroupId=com.bgsoftware \
    -DartifactId=SuperiorSkyblockAPI \
    -Dversion=2025.2 \
    -Dpackaging=jar

mvn install:install-file \
    -Dfile=eco-6_77_2-all.jar \
    -DgroupId=com.willfp \
    -DartifactId=eco \
    -Dversion=6.77.2 \
    -Dpackaging=jar

mvn install:install-file \
    -Dfile=VaultAPI-1.7.1.jar \
    -DgroupId=com.github.MilkBowl \
    -DartifactId=VaultAPI \
    -Dversion=1.7.1 \
    -Dpackaging=jar
```

### 2. Build

```bash
mvn clean package
```

The shaded plugin JAR will be at `target/oneblock-shops-1.0.0.jar`. Drop it into your server's `plugins/` folder alongside all dependency plugins.

---

## ⚙️ Configuration

### `config.yml`

```yaml
# Storage backend: YAML or MYSQL
storage: YAML

# MariaDB settings (only used when storage: MYSQL)
mysql:
  host: localhost
  port: 3306
  database: oneblock_shops
  username: root
  password: password
  pool-size: 10

# Define currencies available for shops
currencies:
  - id: vault_money
    provider: VAULT
    display-name: "&aCoins"

  - id: island_crystals
    provider: ECOBITS
    display-name: "&bCrystals"
    ecobits-key: "ecobits:island_crystals"   # must match EcoBits' registered key

default-currency: vault_money
```

**Adding a new EcoBits currency:**
1. Find the `NamespacedKey` EcoBits registered for it (check EcoBits config or use `/ecobits info`)
2. Add an entry under `currencies` with `provider: ECOBITS` and `ecobits-key: "namespace:key"`
3. Run `/shop reload`

---

## 🛠️ Admin Commands

All commands require the `oneblockshops.admin` permission (default: `op`).

| Command | Description |
|---------|-------------|
| `/shop give <player>` | Gives the shop placement block item |
| `/shop edit <player>` | Opens a paginated GUI listing all of a player's shops; clicking any entry opens the editor |
| `/shop tp <player>` | Teleports to the player's first shop |
| `/shop delete <shopId>` | Force-removes a shop by UUID; also removes its hologram |
| `/shop reload` | Reloads `config.yml` and re-registers all currencies |

---

## 🖱️ Player Usage

### Creating a shop
1. Receive a shop block from an admin (`/shop give <you>`)
2. Place it **on your island** — the plugin automatically detects the island and creates the shop
3. Right-click the chest to open the **GUI editor**

### GUI Editor (right-click the chest)

```
┌──┬──┬──┬──┬──┬──┬──┬──┬──┐
│  │  │📦│  │💰│  │🔄│  │  │   Row 1
│  │  │  │  │  │  │  │  │  │   Row 2 (padding)
│  │  │  │🌻│  │  │  │  │  │   Row 3
└──┴──┴──┴──┴──┴──┴──┴──┴──┘
     11    13    15
                       22
```

| Slot | Icon | Action |
|------|------|--------|
| 11 | Item | Place a cursor item here to set what the shop trades |
| 13 | Gold Nugget | Click → type a price in chat |
| 15 | Emerald/Redstone | Toggle BUY ↔ SELL mode |
| 22 | Sunflower | Cycle through available currencies |

### Buying from a shop (left-click)
- Shop must be in **BUY** mode
- You must be a member of the shop's island
- The item is taken from the chest; you are charged the price

### Selling to a shop (left-click)
- Shop must be in **SELL** mode
- You must be a member of the shop's island
- Your item is placed in the chest; you receive the price

---

## 🏗️ Architecture

```
src/main/java/com/oneblock/shops/
├── OneBlockShopsPlugin.java          # Main plugin class
├── shop/
│   ├── Shop.java                     # Data model
│   ├── ShopMode.java                 # BUY / SELL enum
│   ├── ShopManager.java              # In-memory cache + lifecycle
│   ├── ShopService.java              # Transaction logic + island checks
│   └── TransactionResult.java        # Result codes
├── storage/
│   ├── StorageProvider.java          # Interface
│   ├── YamlStorage.java              # YAML backend (shops.yml)
│   └── MariaDBStorage.java           # MariaDB backend (HikariCP)
├── economy/
│   ├── CurrencyProvider.java         # Interface
│   ├── VaultProvider.java            # Vault implementation
│   ├── EcoBitsProvider.java          # EcoBits via eco PersistentDataKey
│   └── CurrencyRegistry.java         # Loads config, resolves providers
├── hologram/
│   └── HologramService.java          # FancyHolograms create/update/remove
├── gui/
│   ├── ShopEditorGUI.java            # 3-row chest GUI editor
│   └── ChatInputListener.java        # One-shot chat capture for price input
├── commands/
│   ├── ShopCommand.java              # /shop command tree
│   └── ShopListGUI.java              # Paginated shop list for /shop edit
├── listeners/
│   ├── ShopListener.java             # Place/break/interact events
│   └── ChunkLoadListener.java        # Re-spawn holograms on chunk load
└── util/
    ├── ItemUtils.java                # NBT-safe inventory helpers
    └── ShopItemFactory.java          # Creates/identifies the shop block item
```

---

## 🗄️ Database Schema (MariaDB mode)

```sql
CREATE TABLE IF NOT EXISTS shops (
    id         VARCHAR(36)  NOT NULL PRIMARY KEY,
    owner_uuid VARCHAR(36)  NOT NULL,
    world      VARCHAR(64)  NOT NULL,
    x          DOUBLE       NOT NULL,
    y          DOUBLE       NOT NULL,
    z          DOUBLE       NOT NULL,
    island_id  VARCHAR(36),
    item       MEDIUMTEXT,               -- Base64 BukkitObjectOutputStream
    price      DOUBLE       NOT NULL DEFAULT 0,
    mode       VARCHAR(8)   NOT NULL DEFAULT 'BUY',
    currency   VARCHAR(64)  NOT NULL DEFAULT 'vault_money',
    created_at BIGINT       NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

Items are serialized to Base64 via `BukkitObjectOutputStream` preserving full NBT, enchantments, and custom model data.

---

## 🔒 Permissions

| Permission | Default | Description |
|-----------|---------|-------------|
| `oneblockshops.admin` | op | Full access to all `/shop` admin commands |
| `oneblockshops.use` | true | Allows using (buying/selling at) shops |
| `oneblockshops.create` | true | Allows placing the shop block item |

---

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/my-feature`)
3. Commit your changes (`git commit -m 'Add my feature'`)
4. Push to the branch (`git push origin feature/my-feature`)
5. Open a Pull Request

---

## 📄 License

MIT — see [LICENSE](LICENSE) for details.
