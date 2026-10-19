VBook V23.1.4 translation bundle

This bundle is the original V23.1.4 extension source used as the prompt and
translation-behavior reference for Beo Ngoc Dich. The Android app uses a
native Kotlin client because VBook extension JavaScript APIs are not available
inside Android. EPUB/TXT translation, chapter batching, glossary handling,
story memory, and server synchronization are implemented natively.

The bundled private_preset.js intentionally contains empty keys. Enter the
9Router API key in the app settings; no API key is shipped in the APK.
