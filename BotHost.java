package de.bothost;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

/**
 * Startet einen externen Bot (Node.js, Python oder JAR) aus dem Ordner plugins/BotHost/bot/
 * und überwacht ihn. Die Konsolenausgabe des Bots wird in die Server-Konsole übertragen.
 */
public class BotHost extends JavaPlugin {

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase().contains("win");
    private static final int LOG_BUFFER_SIZE = 50;

    private final Deque<String> logBuffer = new ConcurrentLinkedDeque<>();
    private volatile Process process;
    private volatile Thread supervisor;
    private volatile boolean stopping;
    private volatile long startedAt;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        File dir = botDir();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            getLogger().warning("Bot-Ordner konnte nicht erstellt werden: " + dir.getAbsolutePath());
        }
        if (getConfig().getBoolean("auto-start", true)) {
            startBot();
        }
    }

    @Override
    public void onDisable() {
        stopBot();
    }

    // ------------------------------------------------------------------ Steuerung

    private File botDir() {
        return new File(getDataFolder(), getConfig().getString("bot-folder", "bot"));
    }

    public boolean isRunning() {
        Thread t = supervisor;
        return t != null && t.isAlive();
    }

    public synchronized void startBot() {
        if (isRunning()) return;
        stopping = false;
        Thread t = new Thread(this::superviseLoop, "BotHost-Supervisor");
        t.setDaemon(true);
        supervisor = t;
        t.start();
    }

    public synchronized void stopBot() {
        stopping = true;
        Process p = process;
        if (p != null && p.isAlive()) {
            List<ProcessHandle> children = p.descendants().toList();
            children.forEach(ProcessHandle::destroy);
            p.destroy();
            try {
                if (!p.waitFor(10, TimeUnit.SECONDS)) {
                    children.forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Thread t = supervisor;
        if (t != null) {
            t.interrupt();
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        process = null;
        supervisor = null;
    }

    // ------------------------------------------------------------------ Überwachung

    private void superviseLoop() {
        File dir = botDir();
        String[] cmds = resolveCommands(dir);
        String installCmd = cmds[0];
        String startCmd = cmds[1];

        if (startCmd == null) {
            getLogger().warning("Kein Bot gefunden in " + dir.getPath()
                    + ". Lade deine Bot-Dateien dort hoch (z.B. index.js + package.json, bot.py oder eine .jar) "
                    + "oder setze 'start-command' in der config.yml.");
            return;
        }

        if (installCmd != null && getConfig().getBoolean("auto-install", true)) {
            getLogger().info("Installiere Abhängigkeiten: " + installCmd);
            try {
                Process ip = launch(installCmd, dir);
                pipeOutput(ip);
                if (!ip.waitFor(10, TimeUnit.MINUTES)) {
                    ip.destroyForcibly();
                    getLogger().warning("Installation hat zu lange gedauert und wurde abgebrochen.");
                } else if (ip.exitValue() != 0) {
                    getLogger().warning("Installation endete mit Code " + ip.exitValue() + " - der Bot wird trotzdem gestartet.");
                } else {
                    markInstalled(dir);
                }
            } catch (IOException e) {
                getLogger().severe("Installation fehlgeschlagen: " + e.getMessage());
            } catch (InterruptedException e) {
                return;
            }
        }

        int restarts = 0;
        while (!stopping) {
            try {
                getLogger().info("Starte Bot: " + startCmd);
                Process p = launch(startCmd, dir);
                process = p;
                startedAt = System.currentTimeMillis();
                pipeOutput(p);
                int code = p.waitFor();
                if (stopping) break;
                getLogger().warning("Bot wurde beendet (Exit-Code " + code + ").");
            } catch (IOException e) {
                getLogger().severe("Bot konnte nicht gestartet werden: " + e.getMessage()
                        + " (Ist Node.js / Python / Java auf dem Server installiert?)");
                return;
            } catch (InterruptedException e) {
                break;
            }

            if (!getConfig().getBoolean("auto-restart", true)) break;
            if (System.currentTimeMillis() - startedAt > 60_000) restarts = 0;
            restarts++;
            if (restarts > getConfig().getInt("max-restarts", 5)) {
                getLogger().severe("Der Bot ist zu oft hintereinander abgestürzt. Automatischer Neustart wird abgebrochen. "
                        + "Nutze /bothost start, nachdem du den Fehler behoben hast.");
                break;
            }
            int delay = Math.max(1, getConfig().getInt("restart-delay-seconds", 10));
            getLogger().info("Neustart in " + delay + " Sekunden (Versuch " + restarts + ")...");
            try {
                Thread.sleep(delay * 1000L);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private Process launch(String command, File dir) throws IOException {
        ProcessBuilder pb = WINDOWS
                ? new ProcessBuilder("cmd", "/c", command)
                : new ProcessBuilder("sh", "-c", command);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        ConfigurationSection env = getConfig().getConfigurationSection("environment");
        if (env != null) {
            for (String key : env.getKeys(false)) {
                String value = env.getString(key, "");
                if (value != null && !value.isBlank()) {
                    pb.environment().put(key, value);
                }
            }
        }
        return pb.start();
    }

    private void pipeOutput(Process p) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                getLogger().info("[Bot] " + line);
                logBuffer.addLast(line);
                while (logBuffer.size() > LOG_BUFFER_SIZE) logBuffer.pollFirst();
            }
        } catch (IOException ignored) {
            // Stream wurde geschlossen, weil der Prozess beendet wurde
        }
    }

    // ------------------------------------------------------------------ Automatische Erkennung

    /** Liefert {installCommand, startCommand}. Einträge können null sein. */
    private String[] resolveCommands(File dir) {
        String install = trimToNull(getConfig().getString("install-command", ""));
        String start = trimToNull(getConfig().getString("start-command", ""));
        if (start != null) {
            return new String[]{install, start};
        }
        if (!dir.isDirectory()) {
            return new String[]{null, null};
        }

        // Node.js
        File pkg = new File(dir, "package.json");
        if (pkg.isFile()) {
            if (install == null && !new File(dir, "node_modules").isDirectory()) install = "npm install";
            String main = "index.js";
            boolean hasStartScript = false;
            try {
                JsonObject json = JsonParser.parseString(Files.readString(pkg.toPath())).getAsJsonObject();
                JsonElement scripts = json.get("scripts");
                if (scripts != null && scripts.isJsonObject() && scripts.getAsJsonObject().has("start")) {
                    hasStartScript = true;
                }
                JsonElement m = json.get("main");
                if (m != null && m.isJsonPrimitive()) main = m.getAsString();
            } catch (Exception e) {
                getLogger().warning("package.json konnte nicht gelesen werden: " + e.getMessage());
            }
            return new String[]{install, hasStartScript ? "npm start" : "node " + quote(main)};
        }

        // Python
        for (String name : new String[]{"main.py", "bot.py", "index.py", "app.py", "run.py"}) {
            if (new File(dir, name).isFile()) {
                String py = WINDOWS ? "python" : "python3";
                if (install == null && new File(dir, "requirements.txt").isFile()
                        && !new File(dir, ".bothost_installed").exists()) {
                    install = py + " -m pip install -r requirements.txt";
                }
                return new String[]{install, py + " " + quote(name)};
            }
        }

        // Java
        File[] jars = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".jar"));
        if (jars != null && jars.length > 0) {
            return new String[]{install, "java -jar " + quote(jars[0].getName())};
        }
        return new String[]{install, null};
    }

    private void markInstalled(File dir) {
        try {
            new File(dir, ".bothost_installed").createNewFile();
        } catch (IOException ignored) {
        }
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    // ------------------------------------------------------------------ Befehle

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase();
        switch (sub) {
            case "start" -> {
                if (isRunning()) {
                    sender.sendMessage("§eDer Bot läuft bereits.");
                } else {
                    reloadConfig();
                    startBot();
                    sender.sendMessage("§aBot wird gestartet...");
                }
            }
            case "stop" -> {
                sender.sendMessage("§eBot wird gestoppt...");
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    stopBot();
                    sender.sendMessage("§cBot gestoppt.");
                });
            }
            case "restart" -> {
                sender.sendMessage("§eBot wird neu gestartet...");
                getServer().getScheduler().runTaskAsynchronously(this, () -> {
                    stopBot();
                    reloadConfig();
                    startBot();
                    sender.sendMessage("§aBot neu gestartet.");
                });
            }
            case "status" -> {
                Process p = process;
                if (isRunning() && p != null && p.isAlive()) {
                    long secs = (System.currentTimeMillis() - startedAt) / 1000;
                    sender.sendMessage("§aBot läuft §7(seit " + secs / 3600 + "h " + (secs % 3600) / 60 + "m, PID " + p.pid() + ")");
                } else if (isRunning()) {
                    sender.sendMessage("§eBot wird gerade gestartet oder neu gestartet...");
                } else {
                    sender.sendMessage("§cBot läuft nicht.");
                }
            }
            case "log" -> {
                if (logBuffer.isEmpty()) {
                    sender.sendMessage("§7Noch keine Ausgabe vom Bot.");
                } else {
                    List<String> lines = logBuffer.stream().toList();
                    int from = Math.max(0, lines.size() - 15);
                    for (String line : lines.subList(from, lines.size())) {
                        sender.sendMessage("§8[Bot] §7" + line);
                    }
                }
            }
            default -> sender.sendMessage("§7Nutzung: /" + label + " <start|stop|restart|status|log>");
        }
        return true;
    }
}
