package net.frankheijden.insights.tasks;

import io.papermc.lib.PaperLib;
import net.frankheijden.insights.Insights;
import net.frankheijden.insights.entities.ChunkLocation;
import net.frankheijden.insights.entities.ScanOptions;
import net.frankheijden.insights.enums.LogType;
import net.frankheijden.insights.managers.LogManager;
import net.frankheijden.insights.managers.ScanManager;
import net.frankheijden.insights.utils.MessageUtils;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.text.NumberFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class LoadChunksTask implements Runnable {

    private static final Insights plugin = Insights.getInstance();
    private static final ScanManager scanManager = ScanManager.getInstance();
    private final ScanOptions scanOptions;

    private transient Map<CompletableFuture<Chunk>, ChunkLocation> pendingChunks;
    private transient boolean run = true;
    private int taskID;
    private boolean cancelled;
    private ScanChunksTask scanChunksTask;

    private long startTime;
    private int totalChunks;

    public LoadChunksTask(ScanOptions scanOptions) {
        this.scanOptions = scanOptions;
    }

    public ScanOptions getScanOptions() {
        return scanOptions;
    }

    public ScanChunksTask getScanChunksTask() {
        return scanChunksTask;
    }

    public int getTotalChunks() {
        return totalChunks;
    }

    public long getStartTime() {
        return startTime;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void start() {
        this.startTime = System.currentTimeMillis();
        this.totalChunks = scanOptions.getChunkCount();
        this.pendingChunks = new HashMap<>();
        this.taskID = Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, this, 0, 1);

        if (scanOptions.isDebug()) {
            LogManager.log(LogType.DEBUG, "Started scan for " + NumberFormat.getIntegerInstance().format(totalChunks) + " " + (totalChunks == 1 ? "chunk" : "chunks") + "...", taskID);
        }
        sendMessage( scanOptions.getPath() + ".start", "%chunks%", NumberFormat.getIntegerInstance().format(totalChunks), "%world%", scanOptions.getWorld().getName());

        scanChunksTask = new ScanChunksTask(scanOptions, this);
        scanChunksTask.start(startTime);

        if (scanOptions.hasUUID()) {
            scanManager.putTask(scanOptions.getUUID(), this);

            Player player = Bukkit.getPlayer(scanOptions.getUUID());
            if (player != null) {
                if (plugin.getConfiguration().GENERAL_SCAN_NOTIFICATION) {
                    scanChunksTask.setupNotification(player);
                }
            }
        }
    }

    public int getTaskID() {
        return taskID;
    }

    private void sendMessage(String path, String... placeholders) {
        if (scanOptions.isConsole()) {
            MessageUtils.sendMessage(Bukkit.getConsoleSender(), path, placeholders);
        } else if (scanOptions.hasUUID()) {
            MessageUtils.sendMessage(scanOptions.getUUID(), path, placeholders);
        }
    }

    private void setChunkForceLoaded(int x, int z, boolean b) {
        try {
            Class<?> worldClass = Class.forName("org.bukkit.World");
            Object worldObject = worldClass.cast(scanOptions.getWorld());

            Method method = worldClass.getDeclaredMethod("setChunkForceLoaded", int.class, int.class, boolean.class);
            if (method != null) {
                method.invoke(worldObject, x, z, b);
            }
        } catch (NoSuchMethodException ignored) {

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    public void stop() {
        if (scanOptions.isDebug()) {
            LogManager.log(LogType.DEBUG, "Finished loading and generating " + NumberFormat.getIntegerInstance().format(totalChunks) + " " + (totalChunks == 1 ? "chunk" : "chunks") + ", saving world and continuing scan...", taskID);
        }

        cancelled = true;
        run = false;
        Bukkit.getScheduler().cancelTask(taskID);
        if (scanOptions.shouldSaveWorld()) {
            scanOptions.getWorld().save();
        }
    }

    public void forceStop() {
        cancelled = true;
        run = false;
        Bukkit.getScheduler().cancelTask(taskID);

        scanChunksTask.forceStop();

        if (scanOptions.isDebug()) {
            LogManager.log(LogType.DEBUG, "Task has been forcefully stopped.", taskID);
        }
    }

    @Override
    public void run() {
        // Check if loop has finished, if not, return
        if (!run) {
            return;
        }
        run = false;

        long loopTime = System.currentTimeMillis();

        // Check if any CompletableFutures have been done processing.
        // If so, send the chunks to the ScanChunksTask for further processing.
        int chunksProcessedLastTick = 0;
        Map<CompletableFuture<Chunk>, ChunkLocation> newPendingChunks = new HashMap<>();
        Set<ChunkLocation> chunksToUnload = new HashSet<>();
        for (CompletableFuture<Chunk> completableFuture: pendingChunks.keySet()) {
            ChunkLocation chunkLocation = pendingChunks.get(completableFuture);
            if (completableFuture.isDone()) {
                ++chunksProcessedLastTick;

                scanChunksTask.addChunk(completableFuture);
                chunksToUnload.add(chunkLocation);
            } else {
                newPendingChunks.put(completableFuture, chunkLocation);
            }
        }
        pendingChunks = newPendingChunks;

        // Unload any chunks which have been used
        for (ChunkLocation unload : chunksToUnload) {
            setChunkForceLoaded(unload.getX(), unload.getZ(), false);
            scanOptions.getWorld().unloadChunkRequest(unload.getX(), unload.getZ());
        }

        // Initalize how many chunks we may load this run
        int chunksToProcess = 1;
        if (chunksProcessedLastTick > 0 || pendingChunks.size() > 0) {
            int chunksExpectedToGetProcessed = (chunksProcessedLastTick - pendingChunks.size()) / 3 + 3;
            if (chunksExpectedToGetProcessed < chunksToProcess) {
                chunksToProcess = chunksExpectedToGetProcessed;
            }
        }

        // Load new chunks and addCompletableFuture them to the CompletableFuture list
        for (int i = 0; i < chunksToProcess; i++) {
            ChunkLocation chunkLocation = scanOptions.getChunkLocations().poll();
            if (chunkLocation == null) {
                stop();
                return;
            }

            if (System.currentTimeMillis() > loopTime + 45) {
                run = true;
                return;
            }

            setChunkForceLoaded(chunkLocation.getX(), chunkLocation.getZ(), true);
            pendingChunks.put(PaperLib.getChunkAtAsync(scanOptions.getWorld(), chunkLocation.getX(), chunkLocation.getZ(), true), chunkLocation);
        }
        run = true;
    }
}
