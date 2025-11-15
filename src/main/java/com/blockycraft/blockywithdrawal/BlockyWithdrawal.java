package com.blockycraft.blockywithdrawal;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import org.bukkit.plugin.Plugin;
import com.blockycraft.blockyauth.BlockyAuth;
import com.blockycraft.blockywithdrawal.geoip.GeoIPManager;
import com.blockycraft.blockywithdrawal.lang.LanguageManager;


public class BlockyWithdrawal extends JavaPlugin {

    private static final Logger LOG = Logger.getLogger("Minecraft");

    // config
    private File dataDir;
    private File inboxDir;
    private File processedDir;
    private File errorDir;
    private int pollSeconds = 3;
    private int maxFilesPerCycle = 10;
    private int initialInventoryRetryDelaySeconds = 10;
    
    private int maxRetries = 5;
    private int taskId = -1;
    private BlockyAuth blockyAuth;
    
    private Map<String, Integer> retryCounts = new HashMap<String, Integer>();
    private Map<String, InventoryRetryState> inventoryRetries = new HashMap<String, InventoryRetryState>();

    private LanguageManager languageManager;
    private GeoIPManager geoIPManager;

    private static class InventoryRetryState {
        int attempt = 0;
        long nextAttemptTimestamp = 0;
    }

    private enum ProcessResult {
        SUCCESS,      // Succeeded, move to 'processed'
        RETRY,        // A "real" failure (no space, bad parse), increment retry counter
        SKIP,         // A temporary condition (user offline/unauthed), do nothing, leave in inbox
        DEFERRED      // A temporary failure due to full inventory, handled by a separate timer
    }

    // --- JSON regex ---
    private static final Pattern P_USERNAME =
        Pattern.compile("\"username\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_ACTION =
        Pattern.compile("\"action\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern P_ITEM =
        Pattern.compile("\\{\\s*\"id\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"name\"\\s*:\\s*\"([A-Za-z0-9_]+)\"\\s*,\\s*\"quantity\"\\s*:\\s*(\\d+)\\s*\\}");

    @Override
    public void onEnable() {
        Plugin blockyAuthInstance = getServer().getPluginManager().getPlugin("BlockyAuth");
        if (blockyAuthInstance == null || !(blockyAuthInstance instanceof BlockyAuth)) {
            LOG.severe("[Withdrawal] BlockyAuth not found! This plugin is required. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.blockyAuth = (BlockyAuth) blockyAuthInstance;

        dataDir = getDataFolder();
        if (!dataDir.exists()) dataDir.mkdirs();
        inboxDir = new File(dataDir, "inbox");
        processedDir = new File(dataDir, "processed");
        errorDir = new File(dataDir, "error");
        inboxDir.mkdirs();
        processedDir.mkdirs();
        errorDir.mkdirs();

        // Initialize managers
        languageManager = new LanguageManager(this);
        geoIPManager = new GeoIPManager();

        File props = new File(dataDir, "config.properties");
        Properties cfg = new Properties();
        if (props.exists()) {
            try {
                FileInputStream is = new FileInputStream(props);
                cfg.load(is);
                is.close();
            } catch (Exception e) {
                LOG.warning("[Withdrawal] Failed to read config.properties: " + e.getMessage());
            }
        } else {
            try {
                cfg.setProperty("poll_seconds", "3");
                cfg.setProperty("max_files_per_cycle", "10");
                cfg.setProperty("max_retries", "5");
                cfg.setProperty("initial_inventory_retry_delay_seconds", "10");
                cfg.setProperty("inbox_dir", "inbox");
                cfg.setProperty("processed_dir", "processed");
                cfg.setProperty("error_dir", "error");
                FileOutputStream os = new FileOutputStream(props);
                cfg.store(os, "Withdrawal plugin config");
                os.close();
            } catch (Exception e) {
                LOG.warning("[Withdrawal] Could not write default config: " + e.getMessage());
            }
        }

        pollSeconds = intProp(cfg, "poll_seconds", 3);
        maxFilesPerCycle = intProp(cfg, "max_files_per_cycle", 10);
        maxRetries = intProp(cfg, "max_retries", 5);
        initialInventoryRetryDelaySeconds = intProp(cfg, "initial_inventory_retry_delay_seconds", 10);
        inboxDir = new File(dataDir, cfg.getProperty("inbox_dir", "inbox"));
        processedDir = new File(dataDir, cfg.getProperty("processed_dir", "processed"));
        errorDir = new File(dataDir, cfg.getProperty("error_dir", "error"));
        inboxDir.mkdirs(); processedDir.mkdirs(); errorDir.mkdirs();

        long period = Math.max(1, pollSeconds) * 20L;
        taskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, new Runnable() {
            public void run() { tickScan(); }
        }, 40L, period);

        LOG.info("[Withdrawal] enabled. poll=" + pollSeconds + "s, max=" + maxFilesPerCycle + ", retries=" + maxRetries);
    }

    @Override
    public void onDisable() {
        try {
            getServer().getScheduler().cancelTasks(this);
        } catch (Throwable ignored) {}
        retryCounts.clear();
        inventoryRetries.clear();
        LOG.info("[Withdrawal] disabled.");
    }

    public LanguageManager getLanguageManager() {
        return languageManager;
    }

    public GeoIPManager getGeoIPManager() {
        return geoIPManager;
    }

    private void tickScan() {
        File[] files = inboxDir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".json");
            }
        });
        if (files == null || files.length == 0) return;

        Arrays.sort(files, new Comparator<File>() {
            public int compare(File a, File b) {
                long d = a.lastModified() - b.lastModified();
                return d < 0 ? -1 : (d > 0 ? 1 : 0);
            }
        });

        int processedCount = 0;
        for (int i = 0; i < files.length && processedCount < maxFilesPerCycle; i++) {
            File f = files[i];
            String fname = f.getName();

            InventoryRetryState inventoryRetryState = inventoryRetries.get(fname);
            if (inventoryRetryState != null && System.currentTimeMillis() < inventoryRetryState.nextAttemptTimestamp) {
                continue; // Not time yet for this file
            }

            ProcessResult result = processFile(f);

            switch (result) {
                case SUCCESS:
                    moveTo(f, processedDir);
                    retryCounts.remove(fname);
                    inventoryRetries.remove(fname); // Clean up on success
                    processedCount++;
                    break;
                
                case RETRY:
                    inventoryRetries.remove(fname); // A hard error overrides an inventory retry
                    int retries = retryCounts.get(fname) == null ? 0 : retryCounts.get(fname);
                    retries++;

                    if (retries >= maxRetries) {
                        LOG.warning("[Withdrawal] File " + fname + " failed " + maxRetries +
                                    " times (e.g., bad file). Moving to error dir.");
                        moveTo(f, errorDir);
                        retryCounts.remove(fname);
                        processedCount++;
                    } else {
                        retryCounts.put(fname, retries);
                    }
                    break;
                    
                case SKIP:
                    // Do nothing, try again next tick
                    break;

                case DEFERRED:
                    // Do nothing, the timer is already set in processFile
                    break;
            }
        }
    }

    private ProcessResult processFile(File f) {
        String json;
        try {
            json = readUtf8(f);
        } catch (IOException e) {
            LOG.warning("[Withdrawal] read failed: " + f.getName() + " -> " + e.getMessage());
            return ProcessResult.RETRY;
        }

        String action = extractFirst(P_ACTION, json);
        if (action == null || !"withdrawal".equalsIgnoreCase(action)) {
            LOG.warning("[Withdrawal] invalid action in " + f.getName());
            return ProcessResult.RETRY;
        }

        String username = extractFirst(P_USERNAME, json);
        if (username == null || username.trim().isEmpty()) {
            LOG.warning("[Withdrawal] missing username in " + f.getName());
            return ProcessResult.RETRY;
        }

        ArrayList<ItemReq> requests = new ArrayList<ItemReq>();
        Matcher mi = P_ITEM.matcher(json);
        while (mi.find()) {
            try {
                String id = mi.group(1);
                String name = mi.group(2);
                int qty = Integer.parseInt(mi.group(3));
                if (qty > 0) requests.add(new ItemReq(id, name, qty));
            } catch (Exception ignored) {}
        }
        if (requests.isEmpty()) {
            LOG.warning("[Withdrawal] no items in " + f.getName());
            return ProcessResult.RETRY;
        }

        Player p = getServer().getPlayer(username);
        if (p == null || !p.isOnline()) {
            return ProcessResult.SKIP;
        }

        if (!blockyAuth.isAuthenticated(p)) {
            return ProcessResult.SKIP;
        }

        String lang = geoIPManager.getPlayerLanguage(p);

        if (!canFitAll(p.getInventory(), requests)) {
            InventoryRetryState state = inventoryRetries.get(f.getName());
            if (state == null) {
                state = new InventoryRetryState();
            }
            
            state.attempt++;
            long delaySeconds = (long) (initialInventoryRetryDelaySeconds * Math.pow(2, state.attempt - 1));
            state.nextAttemptTimestamp = System.currentTimeMillis() + (delaySeconds * 1000);
            
            inventoryRetries.put(f.getName(), state);

            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("seconds", String.valueOf(delaySeconds));
            p.sendMessage(languageManager.get(lang, "error.inventory_full_retry", placeholders));
            
            return ProcessResult.DEFERRED;
        }

        boolean credited = creditAll(p, requests);
        if (!credited) {
            LOG.warning("[Withdrawal] unexpected leftover when adding items for " + username);
            return ProcessResult.RETRY;
        }
        try { p.updateInventory(); } catch (Throwable ignored) {}

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("summary", summarize(requests));
        p.sendMessage(languageManager.get(lang, "success.withdrawal", placeholders));
        return ProcessResult.SUCCESS;
    }

    // ItemReq, resolveMaterial, canFitAll, creditAll, etc. remain unchanged
    // ... (rest of the file is the same)
    private static class ItemReq {
        final String id; final String name; final int qty;
        ItemReq(String id, String name, int qty) { this.id=id; this.name=name; this.qty=qty; }
    }

    private static Material resolveMaterial(ItemReq req) {
        if (req == null || req.id == null) return null;

        String[] parts = req.id.split(":");
        int baseId;
        try {
            baseId = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            LOG.warning("[Withdrawal] Invalid base ID format in request: " + req.id);
            return null;
        }

        Material material = null;
        if (req.name != null && !req.name.isEmpty()) {
            try {
                material = Material.getMaterial(req.name.toUpperCase());
            } catch (Throwable ignored) {}
        }

        if (material == null) {
            material = materialById(baseId);
        }

        return material;
    }

    private boolean canFitAll(Inventory inv, List<ItemReq> reqs) {
        ItemStack[] contents = inv.getContents();
        int emptySlots = 0;
        Map<Material, Integer> partialFree = new HashMap<Material, Integer>();

        for (ItemStack it : contents) {
            if (it == null || it.getTypeId() == 0) {
                emptySlots++;
                continue;
            }
            Material m = it.getType();
            int max = m.getMaxStackSize();
            int free = max - it.getAmount();
            if (free > 0) {
                Integer currentFree = partialFree.get(m);
                partialFree.put(m, (currentFree == null ? 0 : currentFree) + free);
            }
        }

        for (ItemReq r : reqs) {
            Material material = resolveMaterial(r);
            if (material == null) {
                LOG.warning("[Withdrawal] Cannot check fit for unresolved item " + r.name + " (ID: " + r.id + ").");
                return false; 
            }

            int remain = r.qty;
            int max = material.getMaxStackSize();

            Integer partial = partialFree.get(material);
            if (partial != null) {
                int canPlaceInPartial = Math.min(partial, remain);
                remain -= canPlaceInPartial;
                partialFree.put(material, partial - canPlaceInPartial);
            }

            if (remain <= 0) {
                continue;
            }

            int stacksNeeded = (remain + max - 1) / max; 
            if (emptySlots >= stacksNeeded) {
                emptySlots -= stacksNeeded;
            } else {
                return false; 
            }
        }
        return true; 
    }

    private boolean creditAll(Player p, List<ItemReq> reqs) {
        Inventory inv = p.getInventory();
        for (ItemReq r : reqs) {
            String[] parts = r.id.split(":");
            int baseId;
            short dataValue = 0; 
            try {
                baseId = Integer.parseInt(parts[0]);
                if (parts.length > 1) {
                    dataValue = Short.parseShort(parts[1]);
                }
            } catch (NumberFormatException e) {
                LOG.warning("[Withdrawal] Skipping item with invalid ID format: " + r.id);
                continue; 
            }

            Material material = null;
            if (r.name != null && !r.name.isEmpty()) {
                material = Material.getMaterial(r.name.toUpperCase());
            }
            if (material == null) {
                material = materialById(baseId);
            }

            if (material == null) {
                LOG.warning("[Withdrawal] Skipping unresolved item during credit: " + r.name + " (ID: " + r.id + ")");
                continue;
            }

            ItemStack toAdd = new ItemStack(material, r.qty, dataValue);
            HashMap<Integer, ItemStack> leftovers = inv.addItem(new ItemStack[]{ toAdd });

            if (leftovers != null && !leftovers.isEmpty()) {
                LOG.severe("[Withdrawal] CRITICAL: Failed to credit items to " + p.getName() + " even after fit check passed. Some items may be lost.");
                return false;
            }
        }
        return true;
    }

    private static Material materialById(int id) {
        try {
            for (Material m : Material.values()) {
                try {
                    if (m.getId() == id) return m;
                } catch (Throwable ignored) {} 
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String summarize(List<ItemReq> reqs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < reqs.size(); i++) {
            if (i > 0) sb.append(", ");
            ItemReq r = reqs.get(i);

            Material material = resolveMaterial(r);
            String displayName;

            if (material != null) {
                displayName = formatMaterialName(material);
            } else {
                displayName = r.name;
            }
            sb.append(r.qty).append("x ").append(displayName);
        }
        return sb.toString();
    }

    private static String formatMaterialName(Material material) {
        if (material == null) return "Unknown";
        String[] words = material.name().split("_");
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            if (word.isEmpty()) continue;
            formatted.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                formatted.append(word.substring(1).toLowerCase());
            }
            if (i < words.length - 1) {
                formatted.append(" ");
            }
        }
        return formatted.toString();
    }

    private static String extractFirst(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String readUtf8(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        InputStreamReader r = new InputStreamReader(in, Charset.forName("UTF-8"));
        StringBuilder sb = new StringBuilder((int) Math.min(8192, Math.max(256, f.length())));
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        r.close();
        return sb.toString();
    }

    private void moveTo(File src, File dstDir) {
        try {
            File dst = new File(dstDir, src.getName());
            if (!src.renameTo(dst)) {
                copyFile(src, dst);
                if (!src.delete()) src.deleteOnExit();
            }
        } catch (Exception e) {
            LOG.warning("[Withdrawal] move failed: " + src.getName() + " -> " + e.getMessage());
        }
    }

    private static void copyFile(File a, File b) throws IOException {
        InputStream in = new FileInputStream(a);
        OutputStream out = new FileOutputStream(b);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close(); out.close();
    }

    private static int intProp(Properties p, String key, int def) {
        try {
            String v = p.getProperty(key, String.valueOf(def));
            if (v == null) return def;
            v = v.trim();
            if (v.isEmpty()) return def;
            return Integer.parseInt(v);
        } catch (Exception ignored) {
            return def;
        }
    }
}