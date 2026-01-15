package mozaki.redmineUpster.service;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class SseEmitterService {
	private final Map<Long, CopyOnWriteArrayList<SseEmitter>> runEmitters = new ConcurrentHashMap<>();

	public SseEmitter createEmitter(Long runId) {
		SseEmitter emitter = new SseEmitter(300000L); // 5分タイムアウト
		runEmitters.computeIfAbsent(runId, k -> new CopyOnWriteArrayList<>()).add(emitter);

		emitter.onCompletion(() -> removeEmitter(runId, emitter));
		emitter.onTimeout(() -> removeEmitter(runId, emitter));
		emitter.onError(e -> removeEmitter(runId, emitter));

		return emitter;
	}

	public void sendLog(Long runId, String level, String message) {
		CopyOnWriteArrayList<SseEmitter> emitters = runEmitters.get(runId);
		if (emitters == null || emitters.isEmpty()) {
			return;
		}

		Map<String, Object> event = Map.of(
				"level", level,
				"message", message,
				"createdAt", System.currentTimeMillis());

		for (SseEmitter emitter : emitters) {
			try {
				emitter.send(SseEmitter.event().name("log").data(event));
			} catch (IOException e) {
				removeEmitter(runId, emitter);
			}
		}
	}

	public void sendProgress(Long runId, int total, int processed, String currentItem) {
		CopyOnWriteArrayList<SseEmitter> emitters = runEmitters.get(runId);
		if (emitters == null || emitters.isEmpty()) {
			return;
		}

		int percentage = total > 0 ? (processed * 100 / total) : 0;
		Map<String, Object> event = Map.of(
				"total", total,
				"processed", processed,
				"percentage", percentage,
				"currentItem", currentItem != null ? currentItem : "");

		for (SseEmitter emitter : emitters) {
			try {
				emitter.send(SseEmitter.event().name("progress").data(event));
			} catch (IOException e) {
				removeEmitter(runId, emitter);
			}
		}
	}

	public void sendComplete(Long runId, String status) {
		CopyOnWriteArrayList<SseEmitter> emitters = runEmitters.get(runId);
		if (emitters == null || emitters.isEmpty()) {
			return;
		}

		Map<String, Object> event = Map.of("status", status);

		for (SseEmitter emitter : emitters) {
			try {
				emitter.send(SseEmitter.event().name("complete").data(event));
				emitter.complete();
			} catch (IOException e) {
				// ignore
			}
		}
		runEmitters.remove(runId);
	}

	private void removeEmitter(Long runId, SseEmitter emitter) {
		CopyOnWriteArrayList<SseEmitter> emitters = runEmitters.get(runId);
		if (emitters != null) {
			emitters.remove(emitter);
			if (emitters.isEmpty()) {
				runEmitters.remove(runId);
			}
		}
	}
}
