# Third-party notices

The Android app's course-file encryption (`android/app/src/main/java/io/github/daniel_geron/smm2injector/archive/CourseCrypto.kt`) uses the file format and key table from [MariOver](https://github.com/TheGreatRambler/MariOver)'s `SMM2/encryption.py`, which is under the MIT License:

```
MIT License

Copyright (c) 2022 TheGreatRambler

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Level data in the app's level archive comes from TheGreatRambler's [mm2_level dataset](https://huggingface.co/datasets/TheGreatRambler/mm2_level), licensed [CC BY-NC-SA 4.0](https://creativecommons.org/licenses/by-nc-sa/4.0/). The app downloads it directly from Hugging Face; none of it is stored in this repository.
