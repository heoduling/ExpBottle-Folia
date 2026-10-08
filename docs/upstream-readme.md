# ExpBottle 🍾💧

[![Java CI with Maven](https://github.com/wilddip/ExpBottle/actions/workflows/release.yml/badge.svg)](https://github.com/wilddip/ExpBottle/actions/workflows/release.yml)

Tired of losing your precious XP? 😤 ExpBottle to the rescue! 🚀 This Spigot plugin lets players bottle up their experience to use later or send to friends. All with style, custom messages, and slick MiniMessage formatting! ✨

## 🔥 Core Features

*   **XP Bottling & Retrieval**: `/exp get [amount|amountL|all]` - Turn XP into tradable bottles.
*   **Direct XP Transfer**: `/exp send <player> <amount|amountL|all>` - Zap XP directly to others!
*   **Bulk Bottling**: `/exp split <amount|amountL>` - Create multiple bottles of a fixed size.
*   **Admin Gifting**: `/exp give <player> <amount|amountL> [count]` - Give bottles to players (works from console too!).
*   **Universal Bottle Use**: Standard experience bottles with stored XP work as expected.
*   **Seamless Interaction**: Use chests, furnaces, etc., normally while holding a custom EXP bottle.
*   **Deep Customization**:
    *   🌍 **Full Localization**: Edit `lang/<locale_code>.yml` files (e.g., `en_US.yml`, `ru_RU.yml`). Supports **MiniMessage** for awesome text formatting!
    *   🗣️ **Smart Localization**: Automatically detects player's language and uses the right translation from `lang/` files.
    *   🔊 **Sound Notifications**: Customizable sound alert when receiving XP from another player.
    *   📦 **Inventory Management**: Configure behavior for full inventories (`drop` or `error`).
    *   ✂️ **Splitting Control**: Set min/max levels/XP and steps for `/exp split`.
*   **Permission-Based**: Fine-grained control over who can do what.
*   **Instant Reload**: `/exp reload` - Apply config changes without a server restart.

## 🎮 Commands & Permissions

All commands start with `/exp` (or your configured alias).

| Command                                     | Description                                                                                                | Permission          | Aliases     |
| :------------------------------------------ | :--------------------------------------------------------------------------------------------------------- | :------------------ | :---------- |
| `/exp`                                      | Shows your current XP info.                                                                                | `expbottle.use`     | _(none)_    |
| `/exp get [amount[L] \| all]`               | Withdraws XP into a bottle. `amount` = XP, `amountL` = levels. Defaults to `all`.                          | `expbottle.get`     | `g`         |
| `/exp send <player> [amount[L] \| all]`     | Sends XP directly to another player. Format as above.                                                      | `expbottle.send`    | _(none)_    |
| `/exp split <amount[L]>`                    | Creates multiple bottles of a fixed `amount` or `amountL`.                                                 | `expbottle.split`   | `s`         |
| `/exp give <player> <amount[L]> [count]`    | Gives specified `count` of bottles with `amount` or `amountL` XP to a player. Works from console!          | `expbottle.give`    | _(none)_    |
| `/exp help`                                 | Shows this help menu.                                                                                      | `expbottle.help`    | `h`         |
| `/exp reload`                               | Reloads the plugin configuration.                                                                          | `expbottle.reload`  | _(none)_    |

**Understanding Amounts:**
*   `100` = 100 experience points.
*   `10L` = 10 experience levels.
*   `all` = All your current experience (not applicable for `/split` or `/give` amount per bottle).

## ⚙️ Configuration

ExpBottle is highly configurable!

*   **`config.yml`**:
    *   `default_language`: e.g., `en_US`, `ru_RU`.
    *   `command.name` & `command.aliases`: Customize your command.
    *   `bottle_lore`: Define the look of your XP bottles. Use MiniMessage!
        *   Placeholders: `<player>`, `<amount_xp>`, `<full_levels>`, `<amount_lvl>`.
    *   `sound_settings.*`: Configure sounds for different actions (see details below).
    *   `command.get.inventory_full_action`: Action on full inventory (`drop` or `error`).
    *   `command.split.*`: Parameters for the split command (min levels, step, etc.).
    *   _(... and other general settings if any)_

*   **Sound Settings (`sound_settings`) Details**:
    *   `get`:
        *   `enabled: true|false` - Sound when using `/exp get`.
        *   `name: "SOUND_NAME"` - Bukkit sound name (e.g., `ENTITY_ITEM_PICKUP`).
        *   `volume: 1.0`
        *   `pitch: 1.0`
    *   `send_receive`:
        *   `enabled: true|false` - Sound for the recipient of `/exp send` or `/exp give`.
        *   `name: "SOUND_NAME"` (e.g., `ENTITY_PLAYER_LEVELUP`).
        *   `volume: 1.0`
        *   `pitch: 1.0`
    *   `split`:
        *   `enabled: true|false` - Sound when using `/exp split`.
        *   `name: "SOUND_NAME"` (e.g., `BLOCK_AMETHYST_BLOCK_CHIME`).
        *   `volume: 1.0`
        *   `pitch: 1.0`

*   **`lang/` Directory (e.g., `lang/en_US.yml`)**:
    *   All player-facing messages are here! The plugin smartly picks the right file based on each player's client language!
    *   Uses **MiniMessage** for colors, formatting, and hover/click events.
    *   Example MiniMessage: `<green>Success! <yellow><player></yellow> received <blue><amount_xp></blue> XP.</green>`
    *   Placeholders are generally `<placeholder_name>`.

## 📦 Installation

1.  Download the latest `ExpBottle-X.X.X.jar` from the [Releases page](https://github.com/wilddip/ExpBottle/releases).
2.  Drop it into your Spigot server's `plugins` folder.
3.  Restart or load the plugin on your server.
4.  Done! Configure as needed. 🎉

## 🙏 Authors & Credits

*   Original concept & initial versions: [BrannStroom](https://github.com/Brannstroom)
*   Current maintainer & refactor: [wilddip](https://github.com/wilddip)

## 🛠️ Building from Source

Feeling adventurous? Use Maven:
```bash
mvn clean package
```
Find your fresh JAR in the `target` directory. 