package com.blockycraft.blockywithdrawal;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import com.blockycraft.blockywithdrawal.lang.LanguageManager;
import com.blockycraft.blockywithdrawal.geoip.GeoIPManager;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;
import org.json.JSONArray;

public class BlockyWithdrawal extends JavaPlugin {

    private LanguageManager languageManager;
    private GeoIPManager geoIPManager;
    private File inboxDir;
    private File errorDir;
    private int initialRetryDelay = 10;

    private static class RetryState {
        int delaySeconds;
        long nextTryMillis;
        RetryState(int delay, long nextTryMs) { this.delaySeconds = delay; this.nextTryMillis = nextTryMs;}
    }
    private Map<String, RetryState> userRetryMap = new HashMap<>();

    private enum WithdrawalResult {
        SUCCESS,
        INVENTORY_FULL,
        OTHER_ERROR
    }

    public void onEnable() {
        languageManager = new LanguageManager(this);
        geoIPManager = new GeoIPManager();
        setupDirs();
        scheduleWithdrawalProcessing();
    }

    public void onDisable() {}

    private void setupDirs() {
        inboxDir = new File(getDataFolder(), "inbox");
        errorDir = new File(getDataFolder(), "error");
        if (!inboxDir.exists()) inboxDir.mkdirs();
        if (!errorDir.exists()) errorDir.mkdirs();
    }

    private void scheduleWithdrawalProcessing() {
        Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> processWithdrawals(), 0L, 20L);
    }

    private void processWithdrawals() {
        File[] userDirs = inboxDir.listFiles(File::isDirectory);
        if (userDirs == null) return;

        long now = System.currentTimeMillis();

        for (File userDir : userDirs) {
            String username = userDir.getName();
            File[] withdrawals = userDir.listFiles((dir, name) -> name.endsWith(".json"));
            if (withdrawals == null || withdrawals.length == 0) {
                userDir.delete();
                continue;
            }

            Player player = Bukkit.getPlayer(username);
            if (player == null || !player.isOnline()) continue;

            RetryState retry = userRetryMap.get(username);
            if (retry == null) {
                retry = new RetryState(initialRetryDelay, 0L);
                userRetryMap.put(username, retry);
            }
            if (retry.nextTryMillis > now) continue;

            boolean errorOtherThanFull = false;
            String failReason = "";

            for (File jsonFile : withdrawals) {
                WithdrawalResult result = processWithdrawalFilePartial(player, jsonFile);
                if (result == WithdrawalResult.SUCCESS) {
                    // Gera resumo do saque p/ mensagem de sucesso
                    JSONObject obj = readJson(jsonFile);
                    StringBuilder summary = new StringBuilder();
                    JSONArray items = obj.optJSONArray("items");
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.getJSONObject(i);
                            summary.append(item.optString("name")).append(" x").append(item.optInt("quantity"));
                            if (i < items.length() - 1) summary.append(", ");
                        }
                    }
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("summary", summary.toString());
                    String lang = geoIPManager.getPlayerLanguage(player);
                    String msg = languageManager.get(lang, "success.withdrawal", placeholders);
                    player.sendMessage(msg);

                    jsonFile.delete();
                    retry.delaySeconds = initialRetryDelay;
                    retry.nextTryMillis = 0L;
                } else if (result == WithdrawalResult.INVENTORY_FULL) {
                    // Arquivo permanece para próxima rodada
                } else {
                    // ERRO: move para error
                    moveFile(jsonFile, new File(errorDir, jsonFile.getName()));
                    errorOtherThanFull = true;
                    failReason = "Unknown error during withdrawal.";
                }
            }

            int pendingCount = userDir.listFiles((dir, name) -> name.endsWith(".json")).length;
            String lang = geoIPManager.getPlayerLanguage(player);

            if (pendingCount == 0) {
                userDir.delete();
                userRetryMap.remove(username);
            } else if (errorOtherThanFull) {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("reason", failReason);
                String msg = languageManager.get(lang, "error.withdrawal", placeholders);
                player.sendMessage(msg);
                userDir.delete();
                userRetryMap.remove(username);
            } else {
                Map<String, String> placeholders = new HashMap<>();
                placeholders.put("count", String.valueOf(pendingCount));
                placeholders.put("seconds", String.valueOf(retry.delaySeconds));
                String msg = languageManager.get(lang, "withdrawal.inventory_full_pending", placeholders);
                player.sendMessage(msg);

                retry.nextTryMillis = System.currentTimeMillis() + retry.delaySeconds * 1000L;
                retry.delaySeconds *= 2;
            }
        }
    }

    private void moveFile(File src, File dest) {
        src.renameTo(dest);
    }

    private WithdrawalResult processWithdrawalFilePartial(Player player, File file) {
        try {
            String content = readFile(file);
            JSONObject obj = new JSONObject(content);
            JSONArray items = obj.getJSONArray("items");
            Inventory inv = player.getInventory();

            List<JSONObject> remainingItems = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                ItemStack stack = parseItem(item);

                HashMap<Integer, ItemStack> notAdded = inv.addItem(stack);
                if (!notAdded.isEmpty()) {
                    for (ItemStack rest : notAdded.values()) {
                        JSONObject remainItem = new JSONObject();
                        remainItem.put("id", item.getString("id"));
                        remainItem.put("name", item.optString("name", ""));
                        remainItem.put("quantity", rest.getAmount());
                        remainingItems.add(remainItem);
                    }
                }
            }
            if (remainingItems.isEmpty()) {
                return WithdrawalResult.SUCCESS;
            } else {
                JSONObject newObj = new JSONObject();
                for (String key : obj.keySet()) {
                    if (!key.equals("items")) newObj.put(key, obj.get(key));
                }
                newObj.put("items", new JSONArray(remainingItems));
                writeFile(file, newObj.toString());
                return WithdrawalResult.INVENTORY_FULL;
            }
        } catch (Exception e) {
            System.out.println("Erro no processamento de saque: " + e.getMessage());
            return WithdrawalResult.OTHER_ERROR;
        }
    }

    private ItemStack parseItem(JSONObject item) {
        int id = Integer.parseInt(item.getString("id"));
        int qty = item.getInt("quantity");
        return new ItemStack(id, qty);
    }

    private String readFile(File file) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();
        return sb.toString();
    }

    private void writeFile(File file, String content) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8));
        writer.write(content);
        writer.close();
    }

    private JSONObject readJson(File file) {
        try {
            String txt = readFile(file);
            return new JSONObject(txt);
        } catch (Exception e) {
            return new JSONObject();
        }
    }
}
