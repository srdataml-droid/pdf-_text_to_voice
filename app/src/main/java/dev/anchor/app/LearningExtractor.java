package dev.anchor.app;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Extract text from local files (PDF, TXT, MD) into learning chunks. No network access. */
public final class LearningExtractor {
    private static final int TARGET_CHUNK_TOKENS = 512;
    private static final int MAX_CHUNK_TOKENS = 768;
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z])");
    private static final String TAG = "LearningExtractor";

    private LearningExtractor() {}

    public static class ExtractResult {
        public final List<LearningChunk> chunks;
        public final int totalTokens;
        public final String error;

        ExtractResult(List<LearningChunk> chunks, int totalTokens, String error) {
            this.chunks = chunks;
            this.totalTokens = totalTokens;
            this.error = error;
        }

        public boolean success() { return error == null; }
    }

    public static ExtractResult extractFromUri(Context context, Uri uri, long sourceId) {
        try {
            String mime = context.getContentResolver().getType(uri);
            String path = uri.getPath();
            boolean isPdf = path != null && path.toLowerCase().endsWith(".pdf");
            boolean isText = path != null && (path.toLowerCase().endsWith(".txt") || path.toLowerCase().endsWith(".md"));

            if (isPdf) return extractPdf(context, uri, sourceId);
            
            if (mime == null) {
                if (isText) mime = "text/plain";
            }
            if ("text/plain".equals(mime) || mime != null && mime.startsWith("text/") || isText) return extractText(context, uri, sourceId);
            return new ExtractResult(new ArrayList<>(), 0, "Unsupported file type: " + mime);
        } catch (Exception e) {
            Log.e(TAG, "Extraction failed", e);
            return new ExtractResult(new ArrayList<>(), 0, e.getMessage());
        }
    }

    public static ExtractResult extractPdf(Context context, Uri uri, long sourceId) {
        List<LearningChunk> chunks = new ArrayList<>();
        int totalTokens = 0;
        int chunkIndex = 0;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return new ExtractResult(chunks, 0, "Could not open PDF stream");
            File temp = File.createTempFile("anchor_pdf_", ".pdf", context.getCacheDir());
            try (OutputStream out = new FileOutputStream(temp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            try (PdfReader reader = new PdfReader(temp);
                 PdfDocument pdf = new PdfDocument(reader)) {
                int pages = pdf.getNumberOfPages();
                StringBuilder pageText = new StringBuilder();
                for (int p = 1; p <= pages; p++) {
                    String text = PdfTextExtractor.getTextFromPage(pdf.getPage(p));
                    if (text != null && !text.trim().isEmpty()) {
                        pageText.append(text).append("\n");
                        if (pageText.length() > 5000) {
                            ExtractResult r = chunkText(pageText.toString(), sourceId, chunkIndex);
                            chunks.addAll(r.chunks);
                            chunkIndex += r.chunks.size();
                            totalTokens += r.totalTokens;
                            pageText.setLength(0);
                        }
                    }
                }
                if (pageText.length() > 0) {
                    ExtractResult r = chunkText(pageText.toString(), sourceId, chunkIndex);
                    chunks.addAll(r.chunks);
                    totalTokens += r.totalTokens;
                }
            } finally {
                temp.delete();
            }
        } catch (Exception e) {
            Log.e(TAG, "PDF extraction failed", e);
            return new ExtractResult(new ArrayList<>(), 0, "PDF extraction failed: " + e.getMessage());
        }
        return new ExtractResult(chunks, totalTokens, null);
    }

    public static ExtractResult extractText(Context context, Uri uri, long sourceId) {
        List<LearningChunk> chunks = new ArrayList<>();
        int totalTokens = 0;
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder all = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                all.append(line).append('\n');
            }
            ExtractResult r = chunkText(all.toString(), sourceId, 0);
            return new ExtractResult(r.chunks, r.totalTokens, null);
        } catch (Exception e) {
            Log.e(TAG, "Text extraction failed", e);
            return new ExtractResult(new ArrayList<>(), 0, "Text extraction failed: " + e.getMessage());
        }
    }

    private static ExtractResult chunkText(String text, long sourceId, int startIndex) {
        List<LearningChunk> chunks = new ArrayList<>();
        int totalTokens = 0;
        String[] sentences = SENTENCE_SPLIT.split(text);
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;
        int chunkIndex = startIndex;

        for (String sentence : sentences) {
            int sentTokens = estimateTokens(sentence);
            if (currentTokens + sentTokens > MAX_CHUNK_TOKENS && current.length() > 0) {
                LearningChunk ch = new LearningChunk();
                ch.sourceId = sourceId;
                ch.chunkIndex = chunkIndex++;
                ch.content = current.toString().trim();
                ch.tokenCount = currentTokens;
                ch.createdAt = System.currentTimeMillis();
                chunks.add(ch);
                totalTokens += currentTokens;
                current.setLength(0);
                currentTokens = 0;
            }
            current.append(sentence).append(' ');
            currentTokens += sentTokens;
            if (currentTokens >= TARGET_CHUNK_TOKENS) {
                LearningChunk ch = new LearningChunk();
                ch.sourceId = sourceId;
                ch.chunkIndex = chunkIndex++;
                ch.content = current.toString().trim();
                ch.tokenCount = currentTokens;
                ch.createdAt = System.currentTimeMillis();
                chunks.add(ch);
                totalTokens += currentTokens;
                current.setLength(0);
                currentTokens = 0;
            }
        }
        if (current.length() > 0) {
            LearningChunk ch = new LearningChunk();
            ch.sourceId = sourceId;
            ch.chunkIndex = chunkIndex++;
            ch.content = current.toString().trim();
            ch.tokenCount = currentTokens;
            ch.createdAt = System.currentTimeMillis();
            chunks.add(ch);
            totalTokens += currentTokens;
        }
        return new ExtractResult(chunks, totalTokens, null);
    }

    private static int estimateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }

    public static String extractTextFromFile(File file) throws Exception {
        if (file.getName().toLowerCase().endsWith(".pdf")) {
            try (PdfReader reader = new PdfReader(file);
                 PdfDocument pdf = new PdfDocument(reader)) {
                StringBuilder all = new StringBuilder();
                for (int p = 1; p <= pdf.getNumberOfPages(); p++) {
                    all.append(PdfTextExtractor.getTextFromPage(pdf.getPage(p))).append("\n");
                }
                return all.toString();
            }
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder all = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) all.append(line).append('\n');
            return all.toString();
        }
    }
}