package dev.homescreenreader

/** Native helpers: libmobi-based Kindle unpacking. Speech recognition runs in ONNX Runtime. */
object NativeReader {
    init {
        System.loadLibrary("reader")
    }

    external fun unpackMobi(path: String, destination: String): String
}
