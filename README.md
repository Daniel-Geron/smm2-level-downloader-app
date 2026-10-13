# Super Mario Maker 2 Level Downloader/Injector

This is a fork of [royaltongue/smm2-level-downloader-injector](https://github.com/royaltongue/smm2-level-downloader-injector) that adds an [Android app](#android-app-eden-emulator) for the Eden emulator.

# Attribution
Massive thanks to:

[royaltongue](https://github.com/royaltongue) - For the original downloader/injector and the pre-loaded save

[TheGreatRambler](https://github.com/TheGreatRambler) - For making the [MariOver API](https://github.com/TheGreatRambler/MariOver) and making any of this possible 

[DarkFlare](https://www.youtube.com/@DarkFlare) - For creating a cheat to mostly remove background music from Mario Maker 2 in the description of [this video](https://www.youtube.com/watch?v=vQd758VzOug)

# Description
Leveraging the MariOver API, this **very rudimentary** tool will download Mario Maker 2 levels from the official Nintendo servers, rename them to a slot within Coursebot, and "inject" the level into a Mario Maker 2 save.

Through this, it becomes possible to inject levels into your "My Courses" section of Coursebot, thus granting you full editor access.

# Assumptions
Mario Maker 2 will refuse to acknowledge replaced level data files if the save file does not already have a level in that slot. For convenience, I've included my save file that has all 120 "My Courses" and 60 "Downloaded Courses" slots filled. This means you can freely use this tool on any available slot.

You are free to not use this included save file, but keep in mind that level slots will remain empty until you first save a level within the game, **then** replace the level file.

# Known Bugs
## IMPORTANT
This tool only replaces the **level data** and not the **level metadata**. As such, you will notice the following:
* Level thumbnails will not be the correct one from your replaced level
* Level statistics will not be the correct ones from your replaced level
* The maker will not be the correct one from your replaced level
* After loading into a level, the level card will be entirely incorrect

Some of these could be fixed by downloading further data from the MariOver API, but no promises I will get to that


 ## Updates
 **2025-12-08**:

As a test, I wanted to see how quickly vibe-coding could accomplish this same task. Literally in one prompt, ChatGPT recreated my original script and added proper error catching, like if you enter an invalid level code to download. Within only a few more prompts, I was able to get a fully functioning GUI version with many nice features. While coding the script was a good bit of practice, using every tool available really does go a long way.

The GUI version is much nicer! It has the following features:

* Shows full level information before downloading, including course name, description, maker, and much, much more
* Shows the course thumbnail upon fetching information
* Can show full course overview thumbnail
* Can download either thumbnail
* Graphical save directory selection
* Proper error handling and warning when entering invalid level codes

### Screenshots of GUI Version
![Main Window](<GUI Images/Main Window.png>)

![Course Overview](<GUI Images/Course Overview.png>)

Anyway, I know people are against vibe-coding generally, so I wanted to be upfront about it. No guarantees on the coding, even the script I made myself.


# Android App (Eden emulator)
The `android/` folder contains an Android app that does everything on your phone for the [Eden](https://eden-emu.dev) Switch emulator. No PC or file juggling is needed.

![Android App](<GUI Images/Android App.png>)

## Setup
1) Download `app-release.apk` from the [latest release](https://github.com/Daniel-Geron/smm2-level-downloader-app/releases/latest) on your phone and install it (you may need to allow installing apps from unknown sources), or [build it yourself](#building-the-apk)
2) In Eden, start Super Mario Maker 2 once so the game creates its save, then close Eden completely (swipe it away from recent apps)
3) Open **SMM2 Level Injector** and tap **Select Eden folder**. The file picker opens on Eden's files; tap **Use this folder**, then **Allow**. If it doesn't open there, tap the ☰ menu in the picker and choose **Eden**
4) The app finds your Super Mario Maker 2 save automatically

## Injecting a level
1) **Close Eden completely**
2) Enter the level code (dashes and lowercase are fine) and optionally tap **Preview level** to see the level's name, maker and thumbnail
3) Enter the slot to overwrite, or tap **Pick…** to choose from a grid. Slots 0-119 are "My Courses" (editable), slots 120-179 are "Downloaded Courses"
4) Tap **Download & inject level**, then **Open Eden**. The level is in Coursebot

The file being replaced is backed up first, and you can restore it from the **Backups** section.

## Empty slots / pre-loaded save
As described under [Assumptions](#assumptions), the game ignores injected levels in slots that are empty in your save. The app warns you if you pick an empty slot. Tap **Install pre-loaded save** to download the pre-loaded save from this repository (~135 MB) and install it into Eden, which fills every slot. Your existing SMM2 save is backed up first and can be restored from **Backups**.

## How it works
Android doesn't let apps read other apps' private folders directly, but Eden exposes its files through Android's file picker (the "Eden" entry). The app asks for access to that folder once, then looks for the Super Mario Maker 2 save, which Eden keeps in one of these places:
* `nand/user/save/0000000000000000/<user id>/01009B90006DC000/`
* `nand/user/save/account/<user id>/01009B90006DC000/0/` (newer layout)

It then writes the downloaded level as `course_data_XXX.bcd` into that folder, exactly like the PC scripts do. If you moved Eden's save data to a custom folder, select that folder in the picker instead.

## Building the APK
Requires JDK 17+ and the Android SDK (or just open `android/` in Android Studio).
```
cd android
./gradlew assembleRelease
```
The APK is written to `android/app/build/outputs/apk/release/app-release.apk`. Copy it to your phone and install it (you may need to allow installing apps from unknown sources).

# How To Use (PC scripts)
0) **MAKE SURE YOUR EMULATOR IS CLOSED BEFORE INJECTING FILES**
1) Download and extract or clone this repository
2) Open `smm2-save-directory.txt` and set your where you would like these levels to be saved to

    a) If using an emulator such as Ryujinx, right click on Mario Maker 2 and select "Open User Save Directory". Copy this directory and make sure it is saved using forward-slash / and not back-slash \

    On Eden (PC), right click on Mario Maker 2 and select "Open Save Data Location". It is usually `%APPDATA%/eden/nand/user/save/0000000000000000/<user id>/01009B90006DC000`

    b) If using a homebrewed Switch, you can use the directory structure that I've shown in my Pre-loaded Save. You will need to then inject this save using Checkpoint
3) Open the directory in a terminal
4) `python -m venv venv` to set up the venv
5) If on Windows: `venv/Scripts/activate` to activate the env
6) `pip install -r requirements.txt`
7) `python smm2-level-downloader-injector.py` or `python smm2-level-downloader-gui.py`
8) Enter the level ID of the level you want to download in the format `ABCDEFGHI`, not case-sensitive
9) Select the level slot to inject this level

    a) Slots 0-119 are "My Courses" and have editor capabilities

    b) Slots 120-179 are "Downloaded Courses" and do not have editor capabilities
10) Open your emulator, or inject the save using Checkpoint, and the level should now be in Coursebot

# Bonus
If you are playing in Ryujinx, you can remove the background music from Mario Maker 2 while still keeping all of the shell bops and other sound effects

1) In Ryujinx, right click Mario Maker 2 and select "Open Mods Directory"
2) Copy entire "cheats" folder to that directory
3) Back in Ryujinx, right click Mario Maker 2 again and select "Manage Cheats"
4) Ensure the cheat is enabled, and save