## DISCLAIMER: Downloading levels by code depends on the [MariOver](https://github.com/TheGreatRambler/MariOver) server. If the server is down or having issues, you can only use levels saved on your device and levels from the [level archive](#level-archive) (uploaded up to February 2022).
# SMM2 Level Injector for Eden (Android)

An Android app, styled after Super Mario Maker 2's menus, that downloads any Super Mario Maker 2 level by its course code and puts it into your Coursebot in the [Eden](https://eden-emu.dev) Switch emulator. Levels placed in "My Courses" can be opened in the editor. Everything happens on your phone; no PC is needed.

You can also browse an archive of 26.6 million levels (everything uploaded up to February 2022) with search and filters, and inject them without a code or the level server.

**[Download the latest APK](https://github.com/Daniel-Geron/smm2-level-downloader-app/releases/latest)**

![Android App](<GUI Images/Android App.png>)

# Setup
1) Download `smm2_level_downloader_injector_1.2.apk` from the [latest release](https://github.com/Daniel-Geron/smm2-level-downloader-app/releases/latest) on your phone and install it (you may need to allow installing apps from unknown sources), or [build it yourself](#building-the-apk)
2) In Eden, start Super Mario Maker 2 once so the game creates its save, then close Eden completely (swipe it away from recent apps)
3) Open **SMM2 Level Injector** and tap **Select Eden folder**. The file picker opens on Eden's files; tap **Use this folder**, then **Allow**. If it doesn't open there, tap the ☰ menu in the picker and choose **Eden**
4) The app finds your Super Mario Maker 2 save automatically
5) **Recommended:** install the [pre-loaded save](#empty-slots--pre-loaded-save) so every slot has a course in it and can be replaced. Otherwise you can only inject into slots that already have a course

# Injecting a level
1) **Close Eden completely**
2) Enter the level code (dashes and lowercase are fine) and optionally tap **Preview level** to see the level's name, maker and thumbnail
3) Enter the slot to overwrite, or tap **Pick…** to choose from a grid. Slots 0-119 are "My Courses" (editable), slots 120-179 are "Downloaded Courses" (play only)
4) Tap **Download & inject level**, then **Open Eden**. The level is in Coursebot

The file being replaced is backed up first, and you can restore it from the **Backups** tab.

The status at the top right shows whether the level server is online, busy or down, so you know before entering a code. Tap it to check again.

**Saving levels on your device (optional):** in the **Saved** tab, turn on **Save downloaded levels on this device** and every level you preview or inject is kept on your phone. If the server is down later, you can still preview and inject those levels. Saved levels are listed with **Use** and **Delete** buttons. It's off by default.

If the server is down and the level isn't saved on your device, the app tries the level archive (below) instead, as long as the level was uploaded before February 2022.

# Level archive
![Level archive](<GUI Images/Level Archive.png>)

Open the **Archive** tab to scroll through levels without needing a code. It uses TheGreatRambler's [mm2_level dataset](https://huggingface.co/datasets/TheGreatRambler/mm2_level), a snapshot of all 26.6 million levels uploaded to Course World up to February 2022. Levels uploaded after that aren't in it.

1) **Build the level index** (needed to browse). This downloads about 2 GB of level details (names, styles, tags, likes, clear rates…) and needs about 4 GB of space. It runs in the background with a notification, and you can stop it and continue later. You can browse the levels indexed so far while it runs
2) Search by name, and tap **Filters** to sort (most liked, most played, newest…) and filter by style, theme, difficulty, tag and clear rate
3) Tap a level to see its details and description, then **Inject…** it into a slot. The level itself is fetched from the archive when you inject it, a few KB per level
4) **Optional:** **Download full archive** (85 GB) so levels can be injected with no internet at all. You can choose internal storage or an SD card

The archive has no thumbnails or maker names. The level server isn't involved at any point, so the archive keeps working while the server is down.

# Empty slots / pre-loaded save
Super Mario Maker 2 ignores injected levels in slots that are empty in your save, so you can only replace slots that already have a course in them. The app warns you if you pick an empty slot.

**Using the pre-loaded save is recommended.** To make every slot usable, tap **Install pre-loaded save** (in the **Download** tab, or the **Backups** tab). It downloads a save from this repository (~135 MB) that has all 120 "My Courses" and 60 "Downloaded Courses" slots filled, and installs it into Eden. This replaces your SMM2 save (courses, progress and settings); your existing save is backed up first and can be restored from the **Backups** tab.

# Known limitations
Only the **level data** is replaced, not the **level metadata**. As a result:
* The thumbnail in Coursebot is still the one from the course that used to be in that slot
* Level statistics and the maker are not carried over from the downloaded level

Downloading by code depends on the MariOver server, which sometimes goes down. Levels saved on your device and levels in the archive still work when it's down.

# How it works
Levels are downloaded through TheGreatRambler's [MariOver API](https://github.com/TheGreatRambler/MariOver).

The archive stores levels decrypted, so the app re-encrypts each one into a course file the game accepts, using the same format and keys as the game. The archive files are Parquet files on Hugging Face. The app reads them with HTTP range requests, so it only downloads the parts it needs.

Android doesn't let apps read other apps' private folders directly, but Eden exposes its files through Android's file picker (the "Eden" entry). The app asks for access to that folder once, then looks for the Super Mario Maker 2 save, which Eden keeps in one of these places:
* `nand/user/save/0000000000000000/<user id>/01009B90006DC000/`
* `nand/user/save/account/<user id>/01009B90006DC000/0/` (newer layout)

It then writes the downloaded level as `course_data_XXX.bcd` into that folder. If you moved Eden's save data to a custom folder, select that folder in the picker instead.

# Building the APK
Requires JDK 17+ and the Android SDK (or just open `android/` in Android Studio).
```
cd android
./gradlew assembleRelease
```
The APK is written to `android/app/build/outputs/apk/release/app-release.apk`.

# PC scripts (original tool)
The original Python scripts from [royaltongue's repository](https://github.com/royaltongue/smm2-level-downloader-injector) are still included, for PC emulators (Ryujinx, Eden for PC) or a homebrewed Switch. `smm2-level-downloader-gui.py` is a GUI version with level info and thumbnails; `smm2-level-downloader-injector.py` is the command-line version.

0) **MAKE SURE YOUR EMULATOR IS CLOSED BEFORE INJECTING FILES**
1) Download and extract or clone this repository
2) Open `smm2-save-directory.txt` and set the folder the levels should be saved to, using forward slashes `/`:
    * **Ryujinx:** right click Mario Maker 2 and select "Open User Save Directory"
    * **Eden (PC):** right click Mario Maker 2 and select "Open Save Data Location" (usually `%APPDATA%/eden/nand/user/save/0000000000000000/<user id>/01009B90006DC000`)
    * **Homebrewed Switch:** use the folder structure in `Pre-loaded Save/Homebrew`, then inject the save using Checkpoint
3) In a terminal in the repository folder:
    ```
    python -m venv venv
    venv/Scripts/activate        (Windows)
    pip install -r requirements.txt
    python smm2-level-downloader-gui.py
    ```
4) Enter the level code (`ABCDEFGHI`, not case-sensitive) and the slot to replace (0-179)
5) Open your emulator, or inject the save using Checkpoint, and the level is in Coursebot

To use the pre-loaded save on PC, copy the files from `Pre-loaded Save/Emulator` into your save folder.

**Ryujinx bonus:** to remove the background music while keeping sound effects, right click Mario Maker 2 → "Open Mods Directory", copy the `cheats` folder there, then right click → "Manage Cheats" and enable the cheat.

# Credits
* [royaltongue](https://github.com/royaltongue) - The original downloader/injector and the pre-loaded save. This repository is a fork of [royaltongue/smm2-level-downloader-injector](https://github.com/royaltongue/smm2-level-downloader-injector)
* [TheGreatRambler](https://github.com/TheGreatRambler) - The [MariOver API](https://github.com/TheGreatRambler/MariOver), which makes all of this possible, and the [mm2_level dataset](https://huggingface.co/datasets/TheGreatRambler/mm2_level) (CC BY-NC-SA 4.0) behind the level archive. The course-file encryption is based on MariOver's (MIT License, see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md))
* [Kinnay](https://github.com/kinnay) - [NintendoClients wiki](https://github.com/kinnay/NintendoClients/wiki/Data-Store-Codes#super-mario-maker-2), for converting between course codes and the archive's data IDs
* [Nunito](https://github.com/googlefonts/nunito) - The app's rounded font (SIL Open Font License)
* [DarkFlare](https://www.youtube.com/@DarkFlare) - The Ryujinx cheat that removes background music, from the description of [this video](https://www.youtube.com/watch?v=vQd758VzOug)
