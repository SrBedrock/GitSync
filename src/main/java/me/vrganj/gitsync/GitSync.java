package me.vrganj.gitsync;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.NullMarked;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY;
import static net.kyori.adventure.text.format.NamedTextColor.DARK_GREEN;
import static net.kyori.adventure.text.format.NamedTextColor.GRAY;
import static net.kyori.adventure.text.format.NamedTextColor.GREEN;
import static net.kyori.adventure.text.format.NamedTextColor.RED;
import static net.kyori.adventure.text.format.NamedTextColor.YELLOW;

@NullMarked
public class GitSync extends JavaPlugin implements CommandExecutor {
    private static final Component PREFIX = text("[", DARK_GRAY).append(text("GitSync", DARK_GREEN)).append(text("] ", DARK_GRAY));
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
    private static final Gson GSON = new Gson();
    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB GitHub API limit
    private final List<Pattern> whitelist = new ArrayList<>();
    private final List<Pattern> blacklist = new ArrayList<>();

    private static Pattern parsePattern(final String pattern) {
        return Pattern.compile("^\\Q" + pattern.replace("*", "\\E.*\\Q").replace("?", "\\E.\\Q") + "\\E$");
    }

    private boolean isWhitelisted(final String path) {
        return whitelist.stream().anyMatch(pattern -> pattern.matcher(path).matches());
    }

    private boolean isBlacklisted(final String path) {
        return blacklist.stream().anyMatch(pattern -> pattern.matcher(path).matches());
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfig();

        Objects.requireNonNull(getCommand("gitsync")).setExecutor(this);
    }

    private void loadConfig() {
        reloadConfig();

        for (final String pattern : getConfig().getStringList("whitelist")) {
            whitelist.add(parsePattern(pattern));
        }

        for (final String pattern : getConfig().getStringList("blacklist")) {
            blacklist.add(parsePattern(pattern));
        }
    }

    private static byte[] getFileHash(final File file) {
        try {
            final byte[] buffer = new byte[1024];

            final var digest = MessageDigest.getInstance("MD5");

            try (final var stream = new FileInputStream(file)) {
                int read;

                do {
                    read = stream.read(buffer);

                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                } while (read != -1);
            }

            return digest.digest();
        } catch (final NoSuchAlgorithmException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help") || args[0].equals("?")) {
            sender.sendMessage(PREFIX.append(text("Usage: ", GRAY)).append(text("/gitsync <pull/push/reload>", GREEN)));
            return true;
        }

        if (args[0].equalsIgnoreCase("pull")) {
            if (!sender.hasPermission("gitsync.pull")) {
                sender.sendMessage(PREFIX.append(text("Insufficient permissions (gitsync.pull)", RED)));
                return false;
            }

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                try {
                    sender.sendMessage(PREFIX.append(text("Fetching repository zipball...", GRAY)));
                    final long start = System.currentTimeMillis();

                    final var repository = getConfig().getString("repository");

                    if (repository == null) {
                        sender.sendMessage(PREFIX.append(text("Missing repository in config!", RED)));
                        return;
                    }

                    final var token = getConfig().getString("token");

                    if (token == null) {
                        sender.sendMessage(PREFIX.append(text("Missing token in config!", RED)));
                        return;
                    }

                    final var request = HttpRequest.newBuilder(new URI("https://api.github.com/repos/" + repository + "/zipball/master"))
                            .header("Accept", "application/vnd.github+json")
                            .header("Authorization", token)
                            .GET()
                            .build();

                    final var res = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());

                    if (res.statusCode() >= 400) {
                        sender.sendMessage(PREFIX.append(text("Failed to fetch. Probably invalid token.", RED)));
                        return;
                    }

                    try (final var stream = new ZipInputStream(res.body())) {
                        ZipEntry entry;

                        while ((entry = stream.getNextEntry()) != null) {
                            if (entry.isDirectory()) {
                                continue;
                            }

                            final var path = StringUtils.substringAfter(entry.getName(), "/");

                            if (!isBlacklisted(path) && isWhitelisted(path)) {
                                final var file = new File(getDataFolder().getAbsoluteFile().getParentFile(), path);
                                final byte[] oldHash = file.exists() ? getFileHash(file) : null;

                                try {
                                    Files.createDirectories(file.getParentFile().toPath());
                                } catch (final IOException e) {
                                    sender.sendMessage(PREFIX.append(text("Failed to create parent directories for ", RED).append(text(path, RED))));
                                    getLogger().log(Level.SEVERE, "Failed to create parent directories for " + path, e);
                                    continue;
                                }

                                Path tempPath = null;
                                try {
                                    // create a temp file in the same directory to ensure same filesystem
                                    tempPath = Files.createTempFile(file.getParentFile().toPath(), "gitsync-", ".tmp");

                                    try (final var out = Files.newOutputStream(tempPath)) {
                                        stream.transferTo(out);
                                    }

                                    // compute hash from the temp file
                                    final byte[] newHash = getFileHash(tempPath.toFile());

                                    // if the file changed (or didn't exist), move the temp into place
                                    if (!Arrays.equals(oldHash, newHash)) {
                                        try {
                                            // try atomic move first
                                            Files.move(tempPath, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                                        } catch (final AtomicMoveNotSupportedException atomicEx) {
                                            // fallback to non-atomic replace
                                            Files.move(tempPath, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                                        }

                                        sender.sendMessage(PREFIX.append(text("Overwriting ", GRAY).append(text(path, GREEN))));

                                        // set to null so final cleanup doesn't try to delete it
                                        tempPath = null;
                                    }
                                } catch (final IOException e) {
                                    sender.sendMessage(PREFIX.append(text("Failed to write file ", RED).append(text(path, RED))));
                                    getLogger().log(Level.SEVERE, "Failed to write file " + path, e);
                                } finally {
                                    // cleanup leftover temp file if it still exists
                                    if (tempPath != null) {
                                        try {
                                            Files.deleteIfExists(tempPath);
                                        } catch (final IOException ignored) {
                                        }
                                    }
                                }
                            }
                        }
                    }

                    sender.sendMessage(PREFIX.append(text("Finished sync in ", GRAY)).append(text((System.currentTimeMillis() - start) + " ms", GREEN)));
                } catch (final IOException | InterruptedException | URISyntaxException e) {
                    sender.sendMessage(PREFIX.append(text("Something went wrong!", RED)));
                    getLogger().log(Level.SEVERE, "Something went wrong during pull", e);
                }
            });

            return true;
        } else if (args[0].equalsIgnoreCase("push")) {
            if (!sender.hasPermission("gitsync.push")) {
                sender.sendMessage(PREFIX.append(text("Insufficient permissions (gitsync.push)", RED)));
                return false;
            }

            Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
                sender.sendMessage(PREFIX.append(text("Starting push to repository...", GRAY)));
                final long start = System.currentTimeMillis();

                final var repository = getConfig().getString("repository");

                if (repository == null) {
                    sender.sendMessage(PREFIX.append(text("Missing repository in config!", RED)));
                    return;
                }

                final var token = getConfig().getString("token");

                if (token == null) {
                    sender.sendMessage(PREFIX.append(text("Missing token in config!", RED)));
                    return;
                }

                final File root = getDataFolder().getAbsoluteFile().getParentFile();

                // Note: This implementation makes sequential HTTP requests for each file.
                // For repositories with many files, this may be slow due to network latency
                // and could hit GitHub API rate limits (5000 requests/hour for authenticated requests).
                getLogger().info("Starting push operation. Note: Files are uploaded sequentially which may be slow for large repositories.");

                try (final var paths = Files.walk(root.toPath())) {

                    paths.filter(Files::isRegularFile).forEach(p -> {
                        try {
                            final String relative = root.toPath().relativize(p).toString().replace('\\', '/');

                            if (isBlacklisted(relative) || !isWhitelisted(relative)) {
                                return;
                            }

                            // Check file size before reading (GitHub API has 100MB limit)
                            final long fileSize = Files.size(p);
                            if (fileSize > MAX_FILE_SIZE) {
                                sender.sendMessage(
                                    PREFIX.append(
                                        text("Skipping ", GRAY)
                                            .append(text(relative, YELLOW))
                                            .append(text(" (exceeds GitHub's 100MB limit: " + (fileSize / 1024 / 1024) + "MB)", YELLOW))
                                    )
                                );
                                getLogger().warning("File " + relative + " exceeds GitHub's 100MB limit (" + (fileSize / 1024 / 1024) + "MB) and was skipped.");
                                return;
                            }

                            final byte[] localBytes = Files.readAllBytes(p);
                            final String localBase64 = Base64.getEncoder().encodeToString(localBytes);
                            final byte[] localHash = MessageDigest.getInstance("MD5").digest(localBytes);

                            // GET remote file to obtain sha and remote content
                            final String encodedPath = encodePath(relative);
                            final var getReq = HttpRequest.newBuilder(new URI("https://api.github.com/repos/" + repository + "/contents/" + encodedPath))
                                    .header("Accept", "application/vnd.github+json")
                                    .header("Authorization", token)
                                    .GET()
                                    .build();

                            String remoteSha = null;
                            byte[] remoteBytes = null;

                            final var getRes = HTTP_CLIENT.send(getReq, HttpResponse.BodyHandlers.ofString());

                            if (getRes.statusCode() == 200) {
                                final String body = getRes.body();
                                try {
                                    final JsonObject json = GSON.fromJson(body, JsonObject.class);
                                    if (json.has("sha")) {
                                        remoteSha = json.get("sha").getAsString();
                                    }
                                    if (json.has("content")) {
                                        String contentEncoded = json.get("content").getAsString();
                                        // Remove newlines from base64 content
                                        contentEncoded = contentEncoded.replace("\n", "").replace("\r", "");
                                        remoteBytes = Base64.getDecoder().decode(contentEncoded);
                                    }
                                } catch (Exception e) {
                                    getLogger().log(Level.WARNING, "Failed to parse JSON response for " + relative, e);
                                }
                            }

                            boolean changed = true;
                            if (remoteBytes != null) {
                                final byte[] remoteHash = MessageDigest.getInstance("MD5").digest(remoteBytes);
                                changed = !Arrays.equals(localHash, remoteHash);
                            }

                            if (!changed) {
                                return;
                            }

                            // Prepare payload using Gson
                            final String message = "Update " + relative;
                            final JsonObject payload = new JsonObject();
                            payload.addProperty("message", message);
                            payload.addProperty("content", localBase64);
                            if (remoteSha != null) {
                                payload.addProperty("sha", remoteSha);
                            }

                            final var putReq = HttpRequest.newBuilder(new URI("https://api.github.com/repos/" + repository + "/contents/" + encodedPath))
                                    .header("Accept", "application/vnd.github+json")
                                    .header("Authorization", token)
                                    .header("Content-Type", "application/json")
                                    .PUT(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                                    .build();

                            final var putRes = HTTP_CLIENT.send(putReq, HttpResponse.BodyHandlers.ofString());

                            if (putRes.statusCode() == 201 || putRes.statusCode() == 200) {
                                sender.sendMessage(PREFIX.append(text("Uploaded ", GRAY).append(text(relative, GREEN))));
                            } else {
                                String responseSnippet = putRes.body();
                                if (responseSnippet != null && responseSnippet.length() > 200) {
                                    responseSnippet = responseSnippet.substring(0, 200) + "...";
                                }
                                sender.sendMessage(
                                        PREFIX.append(
                                                text("Failed to upload ", GRAY)
                                                        .append(text(relative, RED))
                                                        .append(text(" (HTTP " + putRes.statusCode() + ")", RED))
                                                        .append(text(": " + (responseSnippet != null ? responseSnippet : ""), DARK_GRAY))
                                        )
                                );
                            }
                        } catch (final IOException | URISyntaxException | InterruptedException |
                                       NoSuchAlgorithmException e) {
                            sender.sendMessage(PREFIX.append(text("Failed to process file!", RED)));
                            getLogger().log(Level.SEVERE, "Failed to process file!", e);
                        }
                    });
                } catch (final SecurityException | IOException e) {
                    sender.sendMessage(PREFIX.append(text("Failed to read local files!", RED)));
                    getLogger().log(Level.SEVERE, "Failed to read local files!", e);
                    return;
                }

                sender.sendMessage(PREFIX.append(text("Finished push in ", GRAY)).append(text((System.currentTimeMillis() - start) + " ms", GREEN)));
            });

            return true;
        } else if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("gitsync.reload")) {
                sender.sendMessage(PREFIX.append(text("Insufficient permissions (gitsync.reload)", RED)));
                return false;
            }

            reloadConfig();
            loadConfig();

            sender.sendMessage(PREFIX.append(text("Configuration reloaded!", GREEN)));
            return true;
        }

        return false;
    }

    private static String encodePath(final String p) {
        return Arrays.stream(p.split("/"))
                .map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));
    }
}
