// Portable bootstrap: extracts beside the EXE, starts the bundled application,
// and removes only the unique directory created by this process on normal exit.
using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text;
using System.Windows.Forms;

internal static class PortableLauncher
{
    private const string Prefix = ".shizi-run-";

    [STAThread]
    private static int Main(string[] args)
    {
        string folder = null;
        string parent = Path.GetDirectoryName(Assembly.GetExecutingAssembly().Location);
        try
        {
            folder = Path.Combine(parent, Prefix + Guid.NewGuid().ToString("N"));
            if (Directory.Exists(folder)) throw new IOException("Temporary folder already exists.");
            Directory.CreateDirectory(folder);
            string runtime = Path.Combine(folder, "ShiziRuntime.exe");
            using (Stream input = Assembly.GetExecutingAssembly().GetManifestResourceStream("ShiziRuntime.exe"))
            using (FileStream output = new FileStream(runtime, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                if (input == null) throw new IOException("Application resource is missing.");
                input.CopyTo(output);
            }
            StringBuilder arguments = new StringBuilder();
            foreach (string argument in args)
            {
                if (arguments.Length > 0) arguments.Append(' ');
                arguments.Append(Quote(argument));
            }
            ProcessStartInfo start = new ProcessStartInfo(runtime, arguments.ToString());
            start.WorkingDirectory = folder;
            start.UseShellExecute = false;
            start.CreateNoWindow = true;
            start.EnvironmentVariables["TEMP"] = folder;
            start.EnvironmentVariables["TMP"] = folder;
            using (Process process = Process.Start(start))
            {
                process.WaitForExit();
                return process.ExitCode;
            }
        }
        catch (Exception error)
        {
            if (Array.IndexOf(args, "--headless") < 0)
                MessageBox.Show("无法启动拾字。请将 EXE 放在 D 盘等可写文件夹中重试。\n\n" + error.Message,
                    "拾字", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
        finally
        {
            // Guard cleanup against touching any path outside this launcher's
            // exact, randomly named extraction directory.
            if (folder != null)
            {
                string absolute = Path.GetFullPath(folder);
                string expectedParent = Path.GetFullPath(parent);
                string name = Path.GetFileName(absolute);
                if (String.Equals(Path.GetDirectoryName(absolute), expectedParent, StringComparison.OrdinalIgnoreCase)
                    && name.StartsWith(Prefix, StringComparison.Ordinal)
                    && name.Length == Prefix.Length + 32)
                {
                    for (int attempt = 0; attempt < 15; attempt++)
                    {
                        try { if (Directory.Exists(absolute)) Directory.Delete(absolute, true); break; }
                        catch (IOException) { System.Threading.Thread.Sleep(200); }
                        catch (UnauthorizedAccessException) { System.Threading.Thread.Sleep(200); }
                    }
                }
            }
        }
    }

    private static string Quote(string argument)
    {
        StringBuilder result = new StringBuilder("\"");
        int slashes = 0;
        foreach (char character in argument)
        {
            if (character == '\\') { slashes++; continue; }
            if (character == '"') result.Append('\\', slashes * 2 + 1);
            else result.Append('\\', slashes);
            result.Append(character);
            slashes = 0;
        }
        result.Append('\\', slashes * 2);
        result.Append('"');
        return result.ToString();
    }
}
