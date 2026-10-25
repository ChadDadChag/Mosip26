package io.mosip.registration.liveness.desktop;

import io.mosip.registration.liveness.analyzer.OnnxFaceAnalyzerFactory;
import io.mosip.registration.liveness.api.FaceAnalyzer;
import io.mosip.registration.liveness.api.Frame;
import io.mosip.registration.liveness.api.FrameAnalysis;
import io.mosip.registration.liveness.api.LivenessEngineException;
import io.mosip.registration.liveness.core.session.FaceAnalyzerFactory;

/**
 * The analyzer factory the engine uses, replaceable after an online model update (as Android's
 * {@code SwappableAnalyzerFactory}). New sessions get analyzers from the current factory; a replaced factory is closed,
 * releasing its ONNX sessions, once the analyzers created from it (those of a running session) are closed. So a running
 * session keeps its models, as it keeps its policy.
 */
final class SwappableAnalyzerFactory implements FaceAnalyzerFactory {

	static final class Entry {
		final OnnxFaceAnalyzerFactory factory;
		int users;
		boolean retired;

		Entry(OnnxFaceAnalyzerFactory factory) {
			this.factory = factory;
		}
	}

	private Entry current;

	SwappableAnalyzerFactory(OnnxFaceAnalyzerFactory initial) {
		if (initial == null) {
			throw new IllegalArgumentException("initial factory is required");
		}
		this.current = new Entry(initial);
	}

	synchronized OnnxFaceAnalyzerFactory current() {
		return current.factory;
	}

	/** Makes {@code next} current; the previous factory closes when its last analyzer does (at once if unused). */
	void swap(OnnxFaceAnalyzerFactory next) {
		if (next == null) {
			throw new IllegalArgumentException("next factory is required");
		}
		Entry old;
		synchronized (this) {
			old = current;
			current = new Entry(next);
			old.retired = true;
			if (old.users > 0) {
				return;
			}
		}
		old.factory.close();
	}

	@Override
	public FaceAnalyzer create() throws LivenessEngineException {
		Entry entry;
		synchronized (this) {
			entry = current;
			entry.users++;
		}
		try {
			return new Counted(this, entry.factory.create(), entry);
		} catch (LivenessEngineException | RuntimeException e) {
			release(entry);
			throw e;
		}
	}

	private void release(Entry entry) {
		synchronized (this) {
			entry.users--;
			if (!entry.retired || entry.users > 0) {
				return;
			}
		}
		entry.factory.close();
	}

	/** An analyzer that releases its factory when closed. */
	static final class Counted implements FaceAnalyzer {
		private final SwappableAnalyzerFactory owner;
		private final FaceAnalyzer analyzer;
		private final Entry entry;
		private boolean closed;

		Counted(SwappableAnalyzerFactory owner, FaceAnalyzer analyzer, Entry entry) {
			this.owner = owner;
			this.analyzer = analyzer;
			this.entry = entry;
		}

		@Override
		public FrameAnalysis analyze(Frame frame) throws LivenessEngineException {
			return analyzer.analyze(frame);
		}

		@Override
		public String modelVersion() {
			return analyzer.modelVersion();
		}

		@Override
		public void close() {
			synchronized (this) {
				if (closed) {
					return;
				}
				closed = true;
			}
			try {
				analyzer.close();
			} finally {
				owner.release(entry);
			}
		}
	}
}
