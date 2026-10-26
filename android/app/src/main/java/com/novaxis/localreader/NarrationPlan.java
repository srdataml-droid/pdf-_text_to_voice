package com.novaxis.localreader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Disk-backed passage text and fixed-width offsets; no full-book text or index in native memory. */
final class NarrationPlan implements Closeable {
    static final int MAX_PASSAGES = 1000000, MAX_BATCH = 128, MAX_TEXT_BYTES = 4096;
    static final long MAX_BYTES = 256L * 1024 * 1024;
    private final RandomAccessFile text, index;
    private final int count;
    NarrationPlan(File folder) throws IOException {
        RandomAccessFile data = new RandomAccessFile(new File(folder, "text.bin"), "r");
        RandomAccessFile offsets = null;
        try {
            offsets = new RandomAccessFile(new File(folder, "index.bin"), "r");
            long size = offsets.length();
            if (size == 0 || size % 8 != 0 || size / 8 > MAX_PASSAGES || data.length() > MAX_BYTES) throw new IOException("Invalid narration index");
            count = (int) (size / 8); text = data; index = offsets;
        } catch (IOException error) { data.close(); if (offsets != null) offsets.close(); throw error; }
    }
    int size() { return count; }
    String text(int passage) throws IOException {
        if (passage < 0 || passage >= count) throw new IOException("Invalid passage position");
        index.seek(passage * 8L); long offset = index.readLong();
        if (offset < 0 || offset > text.length() - 4) throw new IOException("Invalid passage offset");
        text.seek(offset); int length = text.readInt();
        if (length < 0 || length > MAX_TEXT_BYTES || text.getFilePointer() + length > text.length()) throw new IOException("Invalid passage text");
        byte[] bytes = new byte[length]; text.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    @Override public void close() throws IOException { try { text.close(); } finally { index.close(); } }
    static void delete(File folder) {
        if (folder == null) return;
        new File(folder, "text.bin").delete(); new File(folder, "index.bin").delete(); folder.delete();
    }
    static final class Writer implements Closeable {
        final File folder;
        private final DataOutputStream text, index;
        private final int expected;
        private int written;
        private long bytes;
        private boolean closed, failed;
        Writer(File folder, int count) throws IOException {
            if (count < 1 || count > MAX_PASSAGES) throw new IOException("Book passage count is unsupported");
            if (!folder.mkdir()) throw new IOException("Narration storage could not be created");
            this.folder = folder; expected = count;
            DataOutputStream data = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(folder, "text.bin"))));
            try { index = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(folder, "index.bin")))); text = data; }
            catch (IOException error) { data.close(); delete(folder); throw error; }
        }
        void append(List<String> values) throws IOException {
            if (closed || failed || values.isEmpty() || values.size() > MAX_BATCH || written + values.size() > expected) throw new IOException("Invalid narration batch");
            for (String value : values) {
                byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
                if (utf8.length > MAX_TEXT_BYTES || bytes + 4 + utf8.length > MAX_BYTES) { failed = true; throw new IOException("Narration text exceeds the device safety limit"); }
                try { index.writeLong(bytes); text.writeInt(utf8.length); text.write(utf8); }
                catch (IOException error) { failed = true; throw error; }
                bytes += 4 + utf8.length; written++;
            }
        }
        void finish() throws IOException {
            if (closed || failed || written != expected) throw new IOException("Narration transfer is incomplete");
            close();
        }
        @Override public void close() throws IOException {
            if (closed) return; closed = true;
            try { text.close(); } finally { index.close(); }
        }
    }
}
