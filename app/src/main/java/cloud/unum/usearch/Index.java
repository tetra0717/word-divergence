package cloud.unum.usearch;

/**
 * Minimal pinned Java binding surface for USearch 2.26.0.
 *
 * The JNI implementation is built directly from the upstream USearch v2.26.0
 * source by app/src/main/cpp/CMakeLists.txt. Keeping this surface minimal avoids
 * depending on an upstream fat-JAR publication that may be missing from a release.
 */
public final class Index implements AutoCloseable {
    private long c_ptr;

    static {
        System.loadLibrary("usearch_jni");
    }

    private Index(long ptr) {
        if (ptr == 0) throw new IllegalStateException("Failed to open USearch index");
        this.c_ptr = ptr;
    }

    public static Index viewFromPath(String path) {
        return new Index(c_createFromFile(path, true));
    }

    public long size() {
        ensureOpen();
        return c_size(c_ptr);
    }

    public long dimensions() {
        ensureOpen();
        return c_dimensions(c_ptr);
    }

    public long[] search(float[] vector, long count) {
        ensureOpen();
        return c_search_f32(c_ptr, vector, count);
    }

    public float[] get(long key) {
        ensureOpen();
        return c_get(c_ptr, key);
    }

    @Override
    public void close() {
        if (c_ptr != 0) {
            c_destroy(c_ptr);
            c_ptr = 0;
        }
    }

    private void ensureOpen() {
        if (c_ptr == 0) throw new IllegalStateException("Index already closed");
    }

    private static native long c_createFromFile(String path, boolean view);
    private static native void c_destroy(long ptr);
    private static native long c_size(long ptr);
    private static native long c_dimensions(long ptr);
    private static native long[] c_search_f32(long ptr, float[] vector, long count);
    private static native float[] c_get(long ptr, long key);
}
