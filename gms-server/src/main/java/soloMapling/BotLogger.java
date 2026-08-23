package soloMapling;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import soloMapling.Environment.BotConfigFile;

/*
Shift rightclick in NostalgiaStory direction
Copy below into powershell

powershell -Command "Get-Content -Path 'BotLog.txt' -Wait'

 */

public class BotLogger {
    static final boolean log = true;
    // Per-tick bot brain traces are dropped by default: hundreds of bots x one state line per
    // tick flooded the disk (open/write/close per line on a NAS is brutal). Set bot_debug_log=true
    // in bot-config.properties to bring them back while debugging.
    private static final boolean debugLog = "true".equalsIgnoreCase(BotConfigFile.getString("bot_debug_log", "false"));
    private static final String LOG_FILE = "BotLog.txt";
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // Key events only: party joins/declines, trades, errors - the lines a server operator wants.
    public static void log(String message) {
        write(message);
    }

    // High-volume per-tick brain traces - dropped unless explicitly enabled.
    public static void debug(String message) {
        if (!debugLog) {
            return;
        }
        write(message);
    }

    private static void write(String message) {
        if (!log) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String timestamp = now.format(formatter);
        String logMessage = String.format("[%s]: %s", timestamp, message);

        try (PrintWriter out = new PrintWriter(new FileWriter(LOG_FILE, true))) {
            out.println(logMessage);
        } catch (IOException e) {
            System.err.println("Error writing to log file: " + e.getMessage());
        }
    }
}
