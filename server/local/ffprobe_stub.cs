// A stand-in for ffprobe on a development machine that has none, so the local test server (Start-LocalServer.ps1)
// can have audiobooks: it answers the one question the scanner asks (`-print_format json -show_format -show_chapters
// <file>`) for the WAV files server/local/make_fixture.py writes, by reading the length from the file's own header.
// It is never deployed; the server's image has the real ffprobe.
using System;
using System.Globalization;
using System.IO;

static class FfprobeStub
{
    static int Main(string[] args)
    {
        if (args.Length == 0) return 1;
        double seconds = 0;
        try
        {
            using (var f = new BinaryReader(File.OpenRead(args[args.Length - 1])))
            {
                if (new string(f.ReadChars(4)) == "RIFF")
                {
                    f.ReadInt32();
                    f.ReadChars(4);
                    int byteRate = 0;
                    while (f.BaseStream.Position + 8 <= f.BaseStream.Length)
                    {
                        string id = new string(f.ReadChars(4));
                        int size = f.ReadInt32();
                        if (id == "fmt ") { f.ReadBytes(8); byteRate = f.ReadInt32(); f.ReadBytes(size - 12); }
                        else if (id == "data") { if (byteRate > 0) seconds = (double)size / byteRate; break; }
                        else f.ReadBytes(size + (size & 1));
                    }
                }
            }
        }
        catch (Exception) { return 1; }
        Console.Write("{\"format\":{\"duration\":\"" + seconds.ToString("0.000", CultureInfo.InvariantCulture) + "\",\"tags\":{}},\"chapters\":[]}");
        return 0;
    }
}
