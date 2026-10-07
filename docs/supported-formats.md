# Supported books and reading behavior

| Format | Support | Reading unit | Notes |
| --- | --- | --- | --- |
| PDF | Selectable text | Lines grouped by page | Image-only/scanned pages have no text to read; OCR is not included. |
| EPUB | Non-DRM books | Ordered text segments | Scripts and remote resources are not needed for extraction. |
| TXT | UTF-8 text | Paragraphs and sentences | Plain-text files only. |

The reader imports book files from the device and stores them in local IndexedDB. Playback progress is saved locally as you read. Speech is generated on the device with an installed model and its available voice. The current passage is highlighted while it plays.

Choose Piper, Kokoro, or Kitten Nano first. The voice picker then lists the voices available for that model. Each model has different speech quality, speed, memory use, and voice choices. Model files are installed separately from books and are not included in library backups.

DRM removal, OCR, online content, and remote speech services are outside the supported scope. For privacy and offline operation, the app's normal reading flow does not connect to a network service.

Long sentences are split into passages of up to 350 characters to keep local speech generation bounded. Highlighting follows these passages, rather than individual spoken words.
