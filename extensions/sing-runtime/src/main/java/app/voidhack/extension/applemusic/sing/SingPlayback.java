package app.voidhack.extension.applemusic.sing;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

public final class SingPlayback {
    private static final String TAG = "VoidhackSing";
    private static final int MODEL_RATE = 44_100;
    private static final int CHANNELS = 2;
    private static final int PCM_16_BIT = 2;
    private static final int PCM_FLOAT = 4;
    private static final int WINDOW_FRAMES = MdxSpectrum.FRAMES;
    private static final int STEP_FRAMES = WINDOW_FRAMES - MODEL_RATE;
    private static final int OUTPUT_FRAMES = 4096;
    private static final int MAX_READY_WINDOWS = 2;
    private static final int MAX_POOLED_BUFFERS = 6;
    private static final long STARTUP_WAIT_MS = 180L;

    private static final ByteBuffer EMPTY = ByteBuffer.allocateDirect(0);
    private static final Map<Object, Stream> STREAMS = new WeakHashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(() -> {
            // Keep priority well below UI/RenderThread/SurfaceFlinger/AudioTrack while staying out
            // of Android's SP_BACKGROUND cgroup (nice < 10) so CPU inference stays responsive.
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND - 4);
            task.run();
        }, "apple-music-vocal-separation");
        thread.setPriority(Thread.NORM_PRIORITY - 2);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Consumer<String>> PREPARATION_LISTENERS = new ArrayList<>();

    private static volatile SingInference inference;
    private static volatile int mix;
    private static volatile long mixZeroSinceElapsedMs;
    private static volatile boolean preparing;
    private static volatile boolean supported;

    private SingPlayback() {}

    public static int mix() {
        return mix;
    }

    public static void setMix(int value) {
        int clamped = Math.max(-100, Math.min(100, value));
        int previous = mix;
        if (clamped == 0) {
            if (previous != 0) {
                mixZeroSinceElapsedMs = SystemClock.elapsedRealtime();
            }
        } else {
            mixZeroSinceElapsedMs = 0L;
        }
        mix = clamped;
    }

    public static boolean isReady() {
        return inference != null;
    }

    public static boolean isPreparing() {
        return preparing;
    }

    public static boolean streamSupported() {
        return supported;
    }

    public static boolean supportsFormat(int sampleRate, int channels, int encoding) {
        boolean compatible = isCompatibleFormat(sampleRate, channels, encoding);
        supported = compatible;
        return compatible;
    }

    private static boolean isCompatibleFormat(int rate, int channels, int encoding) {
        if (channels != CHANNELS || (encoding != PCM_16_BIT && encoding != PCM_FLOAT)) return false;
        return switch (rate) {
            case 32_000, MODEL_RATE, 48_000, 88_200, 96_000, 176_400, 192_000 -> true;
            default -> false;
        };
    }

    public static void prepare(Context context, Consumer<String> onComplete) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            if (isReady()) {
                onComplete.accept(null);
                return;
            }
            PREPARATION_LISTENERS.add(onComplete);
            if (preparing) return;
            preparing = true;
            WORKER.execute(() -> prepareModel(app));
        });
    }

    private static void prepareModel(Context context) {
        String error = null;
        try {
            SingInference opened = SingInference.open(context);
            inference = opened;
            Log.i(TAG, "Vocal separation model ready (backend=" + opened.activeBackend().id() + ")");
        } catch (Exception | LinkageError failure) {
            Log.w(TAG, "Could not prepare vocal separation model", failure);
            error = "model";
        }
        String result = error;
        MAIN.post(() -> {
            preparing = false;
            List<Consumer<String>> listeners = new ArrayList<>(PREPARATION_LISTENERS);
            PREPARATION_LISTENERS.clear();
            for (Consumer<String> listener : listeners) listener.accept(result);
        });
    }

    public static ByteBuffer queueInput(
            Object processor, ByteBuffer input, int sampleRate, int channels, int encoding) {
        boolean compatible = isCompatibleFormat(sampleRate, channels, encoding);
        if (input.hasRemaining()) supported = compatible;
        if (!compatible) return null;

        Stream stream;
        synchronized (STREAMS) {
            stream = STREAMS.get(processor);
            SingInference model = inference;
            if (stream == null && model != null && mix != 0 && input.hasRemaining()) {
                stream = new Stream(model, sampleRate, encoding);
                STREAMS.put(processor, stream);
                Log.i(TAG, "Vocal separation enabled for stereo PCM at " + sampleRate + " Hz");
            }
        }
        return stream == null ? null : stream.feed(input);
    }

    public static void finish(Object processor) {
        Stream stream = streamFor(processor);
        if (stream != null) stream.inputEnded = true;
    }

    public static ByteBuffer drain(Object processor) {
        Stream stream = streamFor(processor);
        return stream == null ? null : stream.drain();
    }

    public static boolean hasPendingOutput(Object processor) {
        Stream stream = streamFor(processor);
        return stream != null && stream.inputEnded
                && (stream.ready != null
                        || !stream.readyQueue.isEmpty()
                        || stream.pendingWindow != null
                        || stream.inputFrames > 0);
    }

    private static Stream streamFor(Object processor) {
        synchronized (STREAMS) {
            return STREAMS.get(processor);
        }
    }

    public static void flush(Object processor) {
        Stream stream;
        synchronized (STREAMS) {
            stream = STREAMS.remove(processor);
        }
        if (stream != null) stream.cancel();
    }

    private static final class Stream {
        private final SingInference model;
        private final int sampleRate;
        private final int windowFrames;
        private final int windowSamples;
        private final int stepFrames;
        private final int overlapFrames;
        private final int rampFrames;
        private final boolean floatPcm;
        private final int bytesPerSample;
        private final ByteBuffer output;
        private final Object poolLock = new Object();
        private final ArrayDeque<float[]> bufferPool = new ArrayDeque<>(MAX_POOLED_BUFFERS);
        private final ArrayDeque<Window> readyQueue = new ArrayDeque<>(MAX_READY_WINDOWS + 1);
        private float[] input;
        // These resamplers and scratch arrays belong to the inference worker.
        private StereoResampler toModel;
        private StereoResampler toSource;
        private float[] modelInput;
        private float[] modelVocals;
        private int inputFrames;
        private Future<?> pendingTask;
        private Window pendingWindow;
        private Window ready;
        private Window previous;
        private int outputFrame;
        private int outputEnd;
        private int outputBudgetFrames;
        private int leadDrainRemainder;
        private int deadlineMissCount;
        private boolean loggedFirstWindow;
        private boolean inputEnded;
        private boolean hasFullWindow;
        private boolean hasStartedOutput;
        private boolean inferenceFailed;
        private float currentMix;

        Stream(SingInference model, int sampleRate, int encoding) {
            this.model = model;
            this.sampleRate = sampleRate;
            windowFrames = (int) (((long) WINDOW_FRAMES * sampleRate + MODEL_RATE - 1) / MODEL_RATE);
            windowSamples = windowFrames * CHANNELS;
            // Start each window on a frame common to both sample grids so the overlap never drifts.
            int alignment = MODEL_RATE / StereoResampler.gcd(MODEL_RATE, sampleRate);
            int alignedStep = STEP_FRAMES - STEP_FRAMES % alignment;
            stepFrames = (int) ((long) alignedStep * sampleRate / MODEL_RATE);
            overlapFrames = windowFrames - stepFrames;
            rampFrames = sampleRate / 100;
            input = obtainBuffer();
            floatPcm = encoding == PCM_FLOAT;
            bytesPerSample = floatPcm ? Float.BYTES : Short.BYTES;
            output = ByteBuffer.allocateDirect(OUTPUT_FRAMES * CHANNELS * bytesPerSample)
                    .order(ByteOrder.LITTLE_ENDIAN);
        }

        private float[] obtainBuffer() {
            synchronized (poolLock) {
                float[] recycled = bufferPool.pollFirst();
                return recycled != null ? recycled : new float[windowSamples];
            }
        }

        private void recycleWindow(Window window) {
            if (window == null || !window.workerDone) return;
            synchronized (poolLock) {
                recycleArrayLocked(window.original);
                recycleArrayLocked(window.vocals);
            }
        }

        private void recycleArrayLocked(float[] array) {
            if (array != null && array.length == windowSamples && bufferPool.size() < MAX_POOLED_BUFFERS) {
                bufferPool.offerFirst(array);
            }
        }

        ByteBuffer feed(ByteBuffer source) {
            if (inputEnded) return drain();
            int consumedFrames = 0;
            while (source.hasRemaining()) {
                promote();
                scheduleFullWindow();
                int frames = consume(source);
                if (frames == 0) break;
                consumedFrames += frames;
                scheduleFullWindow();
            }
            promote();
            if (ready != null && consumedFrames > 0) {
                addOutputBudget(consumedFrames);
            }
            return drain();
        }

        private void addOutputBudget(int consumedFrames) {
            int divisor = SingBackendPolicy.outputPacingDivisor(
                    readyQueue.size(), MAX_READY_WINDOWS, pendingTask != null);
            int total = consumedFrames + leadDrainRemainder;
            int delta = total / divisor;
            leadDrainRemainder = total % divisor;
            if (delta == 0 && outputBudgetFrames == 0) {
                delta = 1;
            }
            outputBudgetFrames = Math.min(stepFrames, outputBudgetFrames + delta);
        }

        ByteBuffer drain() {
            promote();
            if (inputEnded) {
                scheduleRemaining();
                promote();
            }
            if (ready == null) {
                outputBudgetFrames = 0;
                leadDrainRemainder = 0;
                return EMPTY;
            }
            boolean allowFreeDrain = inputEnded || inputFrames >= windowFrames;
            int maxFrames = allowFreeDrain ? OUTPUT_FRAMES : Math.min(OUTPUT_FRAMES, outputBudgetFrames);
            if (maxFrames <= 0) return EMPTY;

            output.clear();
            int startFrame = outputFrame;
            render(output, Math.min(outputEnd, outputFrame + maxFrames), mix / 100f);
            int rendered = outputFrame - startFrame;
            if (allowFreeDrain) {
                outputBudgetFrames = 0;
            } else {
                outputBudgetFrames = Math.max(0, outputBudgetFrames - rendered);
            }
            hasStartedOutput = true;
            if (previous != null && outputFrame >= overlapFrames) {
                recycleWindow(previous);
                previous = null;
            }
            if (outputFrame == outputEnd) {
                recycleWindow(previous);
                previous = ready;
                ready = null;
                promote();
                scheduleFullWindow();
            }
            output.flip();
            return output;
        }

        private int consume(ByteBuffer source) {
            int frames = Math.min(windowFrames - inputFrames,
                    source.remaining() / (bytesPerSample * CHANNELS));
            if (frames == 0) return 0;

            ByteOrder order = source.order();
            source.order(ByteOrder.LITTLE_ENDIAN);
            try {
                int first = inputFrames * CHANNELS;
                int limit = first + frames * CHANNELS;
                if (floatPcm) {
                    for (int sample = first; sample < limit; sample++) {
                        float value = source.getFloat();
                        input[sample] = Float.isFinite(value) ? value : 0f;
                    }
                } else {
                    for (int sample = first; sample < limit; sample++) {
                        input[sample] = source.getShort() / 32768f;
                    }
                }
            } finally {
                source.order(order);
            }
            inputFrames += frames;
            return frames;
        }

        private void scheduleFullWindow() {
            if (inputFrames < windowFrames || pendingTask != null || readyQueue.size() >= MAX_READY_WINDOWS) {
                return;
            }
            float[] full = input;
            input = obtainBuffer();
            System.arraycopy(full, stepFrames * CHANNELS, input, 0, overlapFrames * CHANNELS);
            inputFrames = overlapFrames;
            submit(full, stepFrames);
            hasFullWindow = true;
        }

        private void scheduleRemaining() {
            if (pendingTask != null || inputFrames == 0) return;
            if (hasFullWindow && inputFrames == overlapFrames && previous != null) {
                if (ready != null || !readyQueue.isEmpty() || pendingWindow != null) return;
                ready = previous;
                previous = null;
                outputFrame = stepFrames;
                outputEnd = windowFrames;
            } else {
                submit(input, inputFrames);
            }
            input = null;
            inputFrames = 0;
        }

        private void submit(float[] pcm, int frames) {
            Window window = new Window(pcm, frames, SystemClock.elapsedRealtime());
            pendingWindow = window;
            pendingTask = WORKER.submit(() -> separate(window));
        }

        private void separate(Window window) {
            try {
                if (inferenceFailed
                        || SingBackendPolicy.shouldSkipIdleInference(
                                mix, mixZeroSinceElapsedMs, SystemClock.elapsedRealtime())) {
                    return;
                }
                long startMs = SystemClock.elapsedRealtime();
                float[] vocals = obtainBuffer();
                if (sampleRate == MODEL_RATE) {
                    model.separate(window.original, vocals);
                } else {
                    if (toModel == null) {
                        toModel = new StereoResampler(sampleRate, MODEL_RATE);
                        toSource = new StereoResampler(MODEL_RATE, sampleRate);
                        modelInput = new float[WINDOW_FRAMES * CHANNELS];
                        modelVocals = new float[modelInput.length];
                    }
                    toModel.resample(window.original, modelInput);
                    model.separate(modelInput, modelVocals);
                    toSource.resample(modelVocals, vocals);
                }
                // Publish vocals with volatile store so an early-promoted window smoothly ramps in.
                window.vocals = vocals;
                if (!loggedFirstWindow) {
                    loggedFirstWindow = true;
                    long elapsedMs = SystemClock.elapsedRealtime() - startMs;
                    Log.i(TAG, "First vocal window ready in " + elapsedMs + "ms ("
                            + CHANNELS + "ch, rate=" + sampleRate + " Hz)");
                }
            } catch (Exception | LinkageError failure) {
                inferenceFailed = true;
                Log.w(TAG, "Vocal separation failed for this stream", failure);
            } finally {
                window.workerDone = true;
            }
        }

        private void promote() {
            if (pendingTask != null && pendingTask.isDone()) {
                pendingTask = null;
                if (pendingWindow != null) {
                    readyQueue.offerLast(pendingWindow);
                    pendingWindow = null;
                }
            }
            if (ready != null) return;

            Window next = readyQueue.pollFirst();
            if (next != null) {
                ready = next;
                outputFrame = 0;
                outputEnd = next.frames;
                return;
            }

            // Never starve AudioTrack if inference lags or on startup: play original PCM seamlessly
            // and crossfade vocals in as soon as the worker publishes window.vocals.
            if (pendingWindow != null) {
                long waitedMs = SystemClock.elapsedRealtime() - pendingWindow.submittedAtMs;
                if (hasStartedOutput || waitedMs >= STARTUP_WAIT_MS) {
                    if (hasStartedOutput && pendingWindow.vocals == null) {
                        deadlineMissCount++;
                        if (deadlineMissCount == 1 || deadlineMissCount % 10 == 0) {
                            Log.w(TAG, "Vocal window missed deadline #" + deadlineMissCount
                                    + " (waited=" + waitedMs + "ms, " + CHANNELS + "ch @ " + sampleRate + " Hz)");
                        }
                    }
                    ready = pendingWindow;
                    pendingWindow = null;
                    outputFrame = 0;
                    outputEnd = ready.frames;
                }
            }
        }

        void cancel() {
            if (pendingTask != null) pendingTask.cancel(false);
            pendingTask = null;
            pendingWindow = null;
            readyQueue.clear();
            outputBudgetFrames = 0;
            leadDrainRemainder = 0;
            synchronized (poolLock) {
                bufferPool.clear();
            }
        }

        private void render(ByteBuffer destination, int limit, float level) {
            float[] readyOriginal = ready.original;
            float[] readyVocals = ready.vocals;
            float targetMix = readyVocals == null ? 0f : level;
            Window prev = previous;
            float[] prevOriginal = prev != null ? prev.original : null;
            float[] prevVocals = prev != null ? prev.vocals : null;

            int ramp = Math.min(rampFrames, limit - outputFrame);
            float increment = ramp == 0 ? 0f : (targetMix - currentMix) / ramp;
            int rampEnd = outputFrame + ramp;
            int prevOffset = stepFrames * CHANNELS;
            while (outputFrame < limit) {
                currentMix = outputFrame < rampEnd ? currentMix + increment : targetMix;
                float crossfade = prevOriginal == null || outputFrame >= overlapFrames
                        ? 1f : outputFrame / (float) overlapFrames;
                int sample = outputFrame * CHANNELS;
                for (int channel = 0; channel < CHANNELS; channel++) {
                    int index = sample + channel;
                    float value = mixSample(readyOriginal[index], readyVocals, index, currentMix);
                    if (crossfade < 1f) {
                        int prevIndex = prevOffset + index;
                        float retained = mixSample(prevOriginal[prevIndex], prevVocals, prevIndex, currentMix);
                        value = retained + (value - retained) * crossfade;
                    }
                    if (floatPcm) {
                        destination.putFloat(Math.max(-1f, Math.min(1f, value)));
                    } else {
                        int linear = Math.round(value * 32768f);
                        destination.putShort((short) Math.max(Short.MIN_VALUE,
                                Math.min(Short.MAX_VALUE, linear)));
                    }
                }
                outputFrame++;
            }
            currentMix = targetMix;
        }

        private static float mixSample(float input, float[] vocals, int index, float level) {
            if (vocals == null || level == 0f) return input;
            float voice = vocals[index];
            return level < 0f ? input + level * voice : input + level * (voice - input);
        }
    }

    private static final class Window {
        final float[] original;
        final int frames;
        final long submittedAtMs;
        volatile float[] vocals;
        volatile boolean workerDone;

        Window(float[] original, int frames, long submittedAtMs) {
            this.original = original;
            this.frames = frames;
            this.submittedAtMs = submittedAtMs;
        }
    }
}
