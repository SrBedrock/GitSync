package me.vrganj.gitsync;

import net.kyori.adventure.text.Component;
import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

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
import java.util.regex.Matcher;
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

public class GitSync extends JavaPlugin implements CommandExecutor {
    private static final Component PREFIX = text("[", DARK_GRAY).append(text("GitSync", DARK_GREEN)).append(text("] ", DARK_GRAY));
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
    private List<Pattern> whitelist, blacklist;

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

        whitelist = new ArrayList<>();
        blacklist = new ArrayList<>();

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
    public boolean onCommand(@NotNull final CommandSender sender, @NotNull final Command command, @NotNull final String label, @NotNull final String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help") || args[0].equals("?")) {
            sender.sendMessage(PREFIX.append(text("Usage: ", GRAY)).append(text("/gitsync <sync/push/reload>", GREEN)));
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

                    final var stream = new ZipInputStream(res.body());

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
                try {
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
                    try (final var paths = Files.walk(root.toPath())) {

                        for (final var p : paths.filter(Files::isRegularFile).toList()) {
                            final String relative = root.toPath().relativize(p).toString().replace('\\', '/');

                            if (isBlacklisted(relative) || !isWhitelisted(relative)) {
                                continue;
                            }

                            final byte[] localBytes = Files.readAllBytes(p);
                            final String localBase64 = Base64.getEncoder().encodeToString(localBytes);
                            final byte[] localHash = getFileHash(p.toFile());

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
                                final Matcher shaMatcher = Pattern.compile("\"sha\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
                                if (shaMatcher.find()) {
                                    remoteSha = shaMatcher.group(1);
                                }
                                final Matcher contentMatcher = Pattern.compile("\"content\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
                                if (contentMatcher.find()) {
                                    String contentEncoded = contentMatcher.group(1);
                                    // remove JSON escaped newlines
                                    contentEncoded = contentEncoded.replaceAll("\\\\n", "");
                                    contentEncoded = contentEncoded.replaceAll("\\\\r", "");
                                    remoteBytes = Base64.getDecoder().decode(contentEncoded);
                                }
                            }

                            boolean changed = true;
                            if (remoteBytes != null) {
                                final byte[] remoteHash = MessageDigest.getInstance("MD5").digest(remoteBytes);
                                changed = !Arrays.equals(localHash, remoteHash);
                            }

                            if (!changed) {
                                continue;
                            }

                            // Prepare payload
                            final String message = "Update " + relative;
                            final StringBuilder json = new StringBuilder();
                            json.append("{");
                            json.append("\"message\":\"").append(escapeJson(message)).append("\",");
                            json.append("\"content\":\"").append(localBase64).append("\"");
                            if (remoteSha != null) {
                                json.append(",\"sha\":\"").append(remoteSha).append("\"");
                            }
                            json.append("}");

                            final var putReq = HttpRequest.newBuilder(new URI("https://api.github.com/repos/" + repository + "/contents/" + encodedPath))
                                    .header("Accept", "application/vnd.github+json")
                                    .header("Authorization", token)
                                    .header("Content-Type", "application/json")
                                    .PUT(HttpRequest.BodyPublishers.ofString(json.toString()))
                                    .build();

                            final var putRes = HTTP_CLIENT.send(putReq, HttpResponse.BodyHandlers.ofString());

                            if (putRes.statusCode() == 201 || putRes.statusCode() == 200) {
                                sender.sendMessage(PREFIX.append(text("Uploaded ", GRAY).append(text(relative, GREEN))));
                            } else {
                                sender.sendMessage(PREFIX.append(text("Failed to upload ", GRAY).append(text(relative, RED))));
                            }
                        }
                    } catch (final SecurityException | IOException e) {
                        sender.sendMessage(PREFIX.append(text("Failed to read local files!", RED)));
                        getLogger().log(Level.SEVERE, "Failed to read local files!", e);
                        return;
                    }

                    sender.sendMessage(PREFIX.append(text("Finished push in ", GRAY)).append(text((System.currentTimeMillis() - start) + " ms", GREEN)));
                } catch (final InterruptedException | URISyntaxException | NoSuchAlgorithmException e) {
                    sender.sendMessage(PREFIX.append(text("Something went wrong during push!", RED)));
                    getLogger().log(Level.SEVERE, "Something went wrong during push", e);
                }
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

    private static String escapeJson(final String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String encodePath(final String p) {
        return Arrays.stream(p.split("/"))
                .map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));
    }
}
