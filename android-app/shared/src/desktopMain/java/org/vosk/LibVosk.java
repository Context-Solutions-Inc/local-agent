package org.vosk;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Shadow of {@code com.alphacephei:vosk:0.3.45}'s {@code org.vosk.LibVosk}.
 *
 * <p>WHY THIS EXISTS — upstream packaging bug, macOS only.
 *
 * <p>The upstream class declares {@code public static native} bindings for the
 * whole Vosk C API and registers them <b>eagerly</b> in its static initializer
 * via JNA direct mapping ({@code Native.register(LibVosk.class, "vosk")}). JNA
 * resolves <i>every</i> declared native method at that moment. But the
 * {@code darwin/libvosk.dylib} bundled in the 0.3.45 jar (built Dec 2022, a
 * universal x86_64+arm64 binary that loads fine on Apple Silicon) is older than
 * the Linux/Windows natives in the same jar and is missing exactly one symbol —
 * {@code vosk_recognizer_set_grm} (the newest function; the darwin build predates
 * it). So on macOS the registration aborts with:
 *
 * <pre>Error looking up function 'vosk_recognizer_set_grm': dlsym(...): symbol not found</pre>
 *
 * which tears down the entire binding before any audio is captured — even though
 * {@link Recognizer} is only ever used through the no-grammar
 * {@code Recognizer(Model, float)} constructor (see {@code VoskDictation}); we
 * never call {@code setGrammar}. Linux/Windows are unaffected (their bundled
 * natives export the symbol). 0.3.45 is the last Vosk release and there is no
 * macOS wheel / brew / conda build to swap in, so there is no fixed upstream
 * artifact to bump to.
 *
 * <p>FIX — this class is byte-for-byte faithful to upstream 0.3.45 <b>except</b>
 * that it omits the unused {@code vosk_recognizer_set_grm} native declaration, so
 * JNA never looks the missing symbol up. Every other symbol it registers is
 * present in all three bundled natives (verified). Because Gradle puts a project
 * source set's compiled output ahead of dependency jars on the runtime/test
 * classpath, this {@code org.vosk.LibVosk} shadows the one in the jar on every
 * desktop platform; the behaviour is identical to upstream apart from the dropped
 * (unused) method.
 *
 * <p>PINNED to vosk 0.3.45 ({@code libs.versions.toml}). If that version is ever
 * bumped, re-verify this against the new upstream {@code LibVosk} (diff the native
 * method list) or delete this shadow if the macOS dylib is fixed upstream.
 * Tracked by {@code LibVoskShadowTest} ({@code shared/desktopTest}), which loads
 * the binding on the current OS and asserts registration succeeds.
 */
public class LibVosk {

    static {
        // Mirror of upstream: on Windows, stage the bundled MinGW runtime DLLs next
        // to the extracted native before registering; elsewhere register directly.
        if (Platform.isWindows()) {
            try {
                File extracted = Native.extractFromResourcePath(
                        "/win32-x86-64/empty", LibVosk.class.getClassLoader());
                File dir = extracted.getParentFile();
                new File(dir, extracted.getName() + ".x").createNewFile();
                unpackDll(dir, "libwinpthread-1");
                unpackDll(dir, "libgcc_s_seh-1");
                unpackDll(dir, "libstdc++-6");
            } catch (IOException ignored) {
                // Fall through to registration (upstream behaviour).
            } finally {
                Native.register(LibVosk.class, "libvosk");
            }
        } else {
            Native.register(LibVosk.class, "vosk");
        }
    }

    private static void unpackDll(File dir, String name) throws IOException {
        InputStream in = LibVosk.class.getResourceAsStream("/win32-x86-64/" + name + ".dll");
        Files.copy(in, new File(dir, name + ".dll").toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    public static native void vosk_set_log_level(int level);

    public static native Pointer vosk_model_new(String path);

    public static native void vosk_model_free(Pointer model);

    public static native Pointer vosk_spk_model_new(String path);

    public static native void vosk_spk_model_free(Pointer model);

    public static native Pointer vosk_recognizer_new(Model model, float sampleRate);

    public static native Pointer vosk_recognizer_new_spk(Pointer model, float sampleRate, Pointer spkModel);

    public static native Pointer vosk_recognizer_new_grm(Pointer model, float sampleRate, String grammar);

    public static native void vosk_recognizer_set_max_alternatives(Pointer recognizer, int maxAlternatives);

    public static native void vosk_recognizer_set_words(Pointer recognizer, boolean words);

    public static native void vosk_recognizer_set_partial_words(Pointer recognizer, boolean partialWords);

    public static native void vosk_recognizer_set_spk_model(Pointer recognizer, Pointer spkModel);

    public static native boolean vosk_recognizer_accept_waveform(Pointer recognizer, byte[] data, int length);

    public static native boolean vosk_recognizer_accept_waveform_s(Pointer recognizer, short[] data, int length);

    public static native boolean vosk_recognizer_accept_waveform_f(Pointer recognizer, float[] data, int length);

    public static native String vosk_recognizer_result(Pointer recognizer);

    public static native String vosk_recognizer_final_result(Pointer recognizer);

    public static native String vosk_recognizer_partial_result(Pointer recognizer);

    // NOTE: upstream also declares
    //   public static native void vosk_recognizer_set_grm(Pointer, String);
    // It is intentionally OMITTED here — its native symbol is absent from the
    // 0.3.45 macOS dylib, and JNA's eager Native.register would crash on it.
    // The app never calls it (no grammar mode). See the class javadoc.

    public static native void vosk_recognizer_reset(Pointer recognizer);

    public static native void vosk_recognizer_free(Pointer recognizer);

    public static void setLogLevel(LogLevel loglevel) {
        vosk_set_log_level(loglevel.getValue());
    }
}
