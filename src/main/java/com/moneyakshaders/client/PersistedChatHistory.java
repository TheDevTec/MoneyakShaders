package com.moneyakshaders.client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.moneyakshaders.MoneyakShaders;
import com.moneyakshaders.MoneyakShadersConfig;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;

/**
 * Persists the chat scrollback (received messages) across game restarts and server changes — so
 * the chat history is kept "forever" (capped by {@link MoneyakShadersConfig#chatHistorySize}).
 *
 * <p>Stores FULL text-component JSON (one compact JSON per line) in
 * {@code <gameDir>/moneyakshaders_chat.txt}, so colours/formatting survive a relog instead of
 * degrading to plain white log lines. Lines that fail to encode/decode (odd hover payloads, or
 * legacy plain-text lines from the old format) fall back to plain strings transparently.
 *
 * <p>Disk writes are batched: every chat append marks the deque dirty; the dirty bit is flushed by
 * the periodic {@link #maybeFlush()} hook (called from the existing tick mixin) and on shutdown.
 */
public final class PersistedChatHistory {
	private static final String FILE_NAME = "moneyakshaders_chat.txt";
	private static final long FLUSH_INTERVAL_MS = 30_000L;
	private static volatile Thread workerThread;
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread thread = new Thread(r, "moneyakshaders-chat-store");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		workerThread = thread;
		return thread;
	});
	private static final AtomicBoolean flushQueued = new AtomicBoolean();

	private static Path file;
	private static final Deque<String> messages = new ArrayDeque<>(); // component JSON (or legacy plain text) per line
	private static boolean loaded;
	private static volatile boolean dirty;
	private static volatile long lastFlushMs;

	private PersistedChatHistory() {
	}

	private static synchronized void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true; // mark first so a broken disk read doesn't loop on every call
		MinecraftClient mc = MinecraftClient.getInstance();
		if (mc == null) {
			return;
		}
		file = mc.runDirectory.toPath().resolve(FILE_NAME);
		if (Files.exists(file)) {
			try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				String line;
				while ((line = r.readLine()) != null) {
					messages.addLast(line);
				}
			} catch (IOException e) {
				MoneyakShaders.LOGGER.warn("[Optimized Loading] failed to read persisted chat history", e);
			}
			trimToCap();
		}
	}

	/** Snapshot of the persisted messages as Text components, OLDEST first. */
	public static synchronized List<Text> snapshot() {
		ensureLoaded();
		List<Text> out = new ArrayList<>(messages.size());
		for (String line : messages) {
			out.add(decode(line));
		}
		return out;
	}

	/** Append one received chat message (full component); marks dirty for the next flush. */
	public static void append(Text message) {
		if (message == null) {
			return;
		}
		// Component codec traversal and the eventual full-history rewrite used to run inside the chat
		// packet/render callback. The first message after 30 seconds could therefore serialize and write
		// thousands of lines on the render thread. Preserve FIFO ordering on one tiny daemon instead.
		Text stableCopy = message.copy();
		WORKER.execute(() -> appendEncoded(stableCopy));
	}

	private static synchronized void appendEncoded(Text message) {
		String line = encode(message);
		if (line == null || line.isEmpty()) {
			return;
		}
		ensureLoaded();
		messages.addLast(line);
		trimToCap();
		dirty = true;
	}

	/** Text → compact single-line component JSON; falls back to the plain string on encode failure. */
	private static String encode(Text message) {
		try {
			JsonElement el = TextCodecs.CODEC.encodeStart(JsonOps.INSTANCE, message).result().orElse(null);
			if (el != null) {
				String s = el.toString(); // Gson compact form — no raw newlines (they're escaped)
				if (s.indexOf('\n') < 0) {
					return s;
				}
			}
		} catch (Throwable ignored) {
		}
		String plain = message.getString();
		return plain.replace('\n', ' ');
	}

	/** One stored line → Text. JSON lines decode to full components; anything else becomes a literal. */
	private static Text decode(String line) {
		char c = line.isEmpty() ? ' ' : line.charAt(0);
		if (c == '{' || c == '"' || c == '[') {
			try {
				JsonElement el = JsonParser.parseString(line);
				Text t = TextCodecs.CODEC.parse(JsonOps.INSTANCE, el).result().orElse(null);
				if (t != null) {
					return t;
				}
			} catch (Throwable ignored) {
				// fall through to literal (legacy line that merely starts with a brace, or bad JSON)
			}
		}
		return Text.literal(line);
	}

	/** Flush the deque to disk if dirty and the flush interval has elapsed. Cheap when clean. */
	public static void maybeFlush() {
		if (!dirty) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastFlushMs < FLUSH_INTERVAL_MS) {
			return;
		}
		queueFlush();
	}

	private static void queueFlush() {
		if (!flushQueued.compareAndSet(false, true)) return;
		WORKER.execute(() -> {
			try {
				flushOnWorker();
			} finally {
				flushQueued.set(false);
			}
		});
	}

	/** Force-flush regardless of timing (call on game shutdown); normal frames never wait for I/O. */
	public static void flushNow() {
		if (Thread.currentThread() == workerThread) {
			flushOnWorker();
			return;
		}
		try {
			// FIFO barrier: all message encodes already submitted by the render thread complete first.
			Future<?> barrier = WORKER.submit(PersistedChatHistory::flushOnWorker);
			barrier.get(5L, TimeUnit.SECONDS);
		} catch (Throwable failure) {
			MoneyakShaders.LOGGER.warn("[Optimized Loading] timed out flushing chat history", failure);
		}
	}

	private static synchronized void flushOnWorker() {
		if (!loaded || file == null || !dirty) {
			return;
		}
		try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			for (String s : messages) {
				w.write(s);
				w.newLine();
			}
			dirty = false;
			lastFlushMs = System.currentTimeMillis();
		} catch (IOException e) {
			MoneyakShaders.LOGGER.warn("[Optimized Loading] failed to write persisted chat history", e);
		}
	}

	private static void trimToCap() {
		int cap = Math.max(100, MoneyakShadersConfig.get().chatHistorySize);
		while (messages.size() > cap) {
			messages.removeFirst();
		}
	}
}
